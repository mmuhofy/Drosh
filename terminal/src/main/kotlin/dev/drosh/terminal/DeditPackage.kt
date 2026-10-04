/*
 * Copyright (C) 2026 Drosh contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package dev.drosh.terminal

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * The `dedit` command, as a real Debian package rather than a shell function.
 *
 * ### Why a package and not a shell function
 *
 * The first attempt injected an `editor` function from the shell-integration
 * script. It never ran, for a reason worth writing down: the integration
 * reaches zsh through `$ENV`, and zsh does not read `$ENV` in native mode —
 * measured, not assumed. Since Drosh always launches the shell with
 * `--login`, and `--login` is not the problem (a non-login interactive shell
 * ignores `$ENV` too), the function was simply never defined and the user's
 * `editor foo.txt` fell through to Ubuntu's `/usr/bin/editor`, which is nano.
 *
 * A packaged executable has none of that fragility. It does not care which
 * shell is running, whether the shell is interactive, or whether any startup
 * file was sourced — so `dedit` works from a script, a `Makefile` target, a
 * `sudo` pipeline or a bare `sh -c`, all of which a shell function cannot.
 *
 * ### Why the script has a host-absolute shebang
 *
 * The kernel resolves a shebang before PRoot is involved, so a guest-absolute
 * `#!/bin/sh` would be looked up on the Android host, where `/bin/sh` does not
 * exist. Termux solves this the same way — 40k+ files in termux-packages ship
 * `#!/data/data/com.termux/files/usr/bin/bash` — and Drosh can too, because
 * the rootfs is itself a real host directory under the app's own files dir.
 *
 * The path is computed at runtime rather than hardcoded because
 * `context.filesDir` is not guaranteed to be `/data/data/<pkg>/files` on
 * secondary-user installs.
 *
 * The alternative — shipping a native binary — was rejected: it would add an
 * NDK target to the build for a program whose entire job is to validate one
 * argument and write forty bytes to a file descriptor.
 */
object DeditPackage {

    const val NAME = "dedit"
    const val VERSION = "1.0.0"
    const val ARCHITECTURE = "all"

    /** The path the guest command announces itself on. Matches TerminalEmulator. */
    private const val OSC_NUMBER = "1339"

    /** Largest file `dedit` will hand to the app; matches GuestFileLimits. */
    private const val MAX_EDIT_BYTES_KB = 2048

    /**
     * Where the unpacked package tree is staged.
     *
     * Outside the rootfs so `dpkg-deb` is the only thing that puts files in
     * `/usr`, and so a failed build never leaves a half-written `/usr/bin/dedit`
     * behind.
     */
    fun stagingDir(context: Context): File =
        File(File(context.filesDir, "ubuntu"), "dedit-src")

    /**
     * Writes the unpacked `dedit` package tree.
     *
     * Returns false if it could not be written, in which case the caller should
     * leave the command uninstalled rather than install something broken.
     */
    fun stage(context: Context, hostShellPath: String): Boolean = try {
        val root = stagingDir(context)
        root.deleteRecursively()

        val control = File(root, "DEBIAN")
        val bin = File(root, "usr/bin")
        val doc = File(root, "usr/share/doc/$NAME")
        control.mkdirs()
        bin.mkdirs()
        doc.mkdirs()

        File(control, "control").writeText(CONTROL)

        val script = File(bin, NAME)
        script.writeText(command(hostShellPath))
        script.setExecutable(true, false)
        script.setReadable(true, false)

        File(doc, "copyright").writeText(COPYRIGHT)

        // dpkg-deb refuses a tree whose DEBIAN dir is group/world writable, and
        // files created under an app's files dir can be, depending on the OEM.
        control.setWritable(false, false)
        control.setWritable(true, true)

        true
    } catch (e: Exception) {
        Timber.e(e, "dedit: could not stage the package tree")
        false
    }

    /**
     * The `dedit` script.
     *
     * POSIX sh only, run by dash on Ubuntu: no `local`, no arrays, no `[[ ]]`.
     * Its whole job is to turn one argument into either a warning or a single
     * escape sequence, so it stays readable rather than clever.
     *
     * Validation is deliberately here rather than in the app. The app is the
     * authority on the rootfs and will refuse a bad path anyway, but a guest
     * command that fails silently is worse than one that says why — the user is
     * looking at a terminal, not at a snackbar.
     */
    private fun command(hostShellPath: String): String = """
        #!$hostShellPath
        #
        # dedit — open a file in Drosh's native editor.
        #
        # Part of Drosh. Announces the path on OSC $OSC_NUMBER and lets the
        # emulator hand it to the app; there is no in-terminal editor here on
        # purpose, so `dedit` composes with pipes and scripts instead of
        # competing with them.

        __dedit_fail() {
          printf 'dedit: %s\n' "${'$'}1" >&2
          exit "${'$'}2"
        }

        case "${'$'}#" in
          0)
            printf 'usage: dedit <file>\n' >&2
            printf '       dedit --help\n' >&2
            exit 2
            ;;
          1)
            case "${'$'}1" in
              -h|--help)
                printf 'usage: dedit <file>\n\n'
                printf 'Opens <file> in Drosh'"'"'s native code editor.\n\n'
                printf 'The path may be relative to the current directory or\n'
                printf 'absolute, and may point at a file that does not exist\n'
                printf 'yet — it is created when you save. Only files inside\n'
                printf 'the Ubuntu filesystem can be edited.\n'
                exit 0
                ;;
              -*)
                __dedit_fail "unknown option: ${'$'}1" 2
                ;;
            esac
            ;;
          *)
            __dedit_fail "expected exactly one file, got ${'$'}#" 2
            ;;
        esac

        __dedit_target="${'$'}1"

        # A leading ~ is the shell'"'"'s job to expand, and it only does so
        # unquoted. Left alone here it would be taken as a literal directory
        # named "~", which is never what the user meant.
        #
        # `${x#~/}` is NOT correct here: POSIX does not expand `~` inside a `#`
        # pattern, so the pattern is the literal string "~/" and fails to match,
        # leaving the tilde in place and tripping the parent-directory check with
        # a nonsense path. `#?` drops exactly one character -- the tilde -- and
        # carries no such caveat.
        case "${'$'}__dedit_target" in
          "~"/*) __dedit_target="${'$'}HOME/${'$'}__dedit_target#?}" ;;
        esac

        case "${'$'}__dedit_target" in
          /*) ;;
          *) __dedit_target="${'$'}PWD/${'$'}__dedit_target" ;;
        esac

        # BEL and ESC would terminate the escape sequence before the path was
        # complete, silently opening the wrong file. Strip rather than refuse:
        # no one names a file with a control character in it on purpose.
        __dedit_target=$(printf '%s' "${'$'}__dedit_target" | tr -d '\007\033')

        if [ -z "${'$'}__dedit_target" ]; then
          __dedit_fail "empty path" 2
        fi

        # Refuse the paths the app cannot open, rather than letting it report a
        # failure the user reads as a broken editor.
        case "${'$'}__dedit_target" in
          /sdcard|/sdcard/*|/storage|/storage/*|/data|/data/*|/proc|/proc/*|/sys|/sys/*)
            __dedit_fail "'${'$'}__dedit_target' is outside the Ubuntu filesystem — only files under / can be edited" 1
            ;;
        esac

        if [ -d "${'$'}__dedit_target" ]; then
          __dedit_fail "'${'$'}__dedit_target' is a directory" 1
        fi

        if [ -e "${'$'}__dedit_target" ]; then
          if [ ! -r "${'$'}__dedit_target" ]; then
            __dedit_fail "permission denied: ${'$'}__dedit_target" 1
          fi
          if [ ! -w "${'$'}__dedit_target" ]; then
            printf 'dedit: warning: %s is read-only; saving will fail\n' \
              "${'$'}__dedit_target" >&2
          fi
          __dedit_size=$(wc -c < "${'$'}__dedit_target" 2>/dev/null || printf '0')
          if [ "${'$'}__dedit_size" -gt $(( ${MAX_EDIT_BYTES_KB} * 1024 )) ] 2>/dev/null; then
            __dedit_fail "file is larger than ${MAX_EDIT_BYTES_KB} KB and cannot be edited here" 1
          fi
        else
          # Creating a file is the point of `dedit newfile.txt`. The only thing
          # worth checking is that the parent exists, because a typo in a
          # directory name otherwise surfaces as a save failure much later.
          __dedit_parent=$(dirname "${'$'}__dedit_target")
          if [ ! -d "${'$'}__dedit_parent" ]; then
            __dedit_fail "no such directory: ${'$'}__dedit_parent" 1
          fi
        fi

        # /dev/tty, not stdout: a redirect or a pipe must not swallow the
        # request, exactly as the OSC 133 marks are written.
        if ! printf '\033]$OSC_NUMBER;%s\a' "${'$'}__dedit_target" > /dev/tty 2>/dev/null; then
          __dedit_fail "could not reach Drosh's terminal — is this running inside a Drosh session?" 1
        fi

        exit 0
    """.trimIndent() + "\n"

    /**
     * `Architecture: all`, not `arm64`.
     *
     * The payload is one POSIX sh script. There is nothing architecture-specific
     * in it, and declaring `all` means it satisfies a dependency on any arch
     * instead of only the one the rootfs happens to be.
     */
    private val CONTROL = """
        Package: $NAME
        Version: $VERSION
        Section: utils
        Priority: optional
        Architecture: $ARCHITECTURE
        Maintainer: Drosh <drosh@localhost>
        Installed-Size: 4
        Depends: coreutils
        Description: Open a file in Drosh's native code editor
         dedit announces a path to the Drosh app, which opens it in the
         built-in editor. Unlike editor(1) from the system it is a terminal
         client: it draws nothing itself, so it composes with pipes and
         scripts instead of taking over the screen.
    """.trimIndent() + "\n"

    private val COPYRIGHT = """
        Drosh — dedit

        Copyright (C) 2026 Drosh contributors

        This program is free software: you can redistribute it and/or modify
        it under the terms of the GNU General Public License as published by
        the Free Software Foundation, either version 3 of the License, or
        (at your option) any later version.
    """.trimIndent() + "\n"
}