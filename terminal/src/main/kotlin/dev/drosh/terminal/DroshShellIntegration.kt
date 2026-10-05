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
import java.io.File

/**
 * Installs OSC 133 shell integration for the shell Drosh is about to launch.
 *
 * The terminal emulator interprets the marks, so this side only has to make
 * the shell emit them. Nothing is written to the user's home directory and no
 * shell configuration file is modified: Drosh controls the launch argv and
 * environment, so it can point the shell at a script it owns.
 *
 * Per shell:
 *  - **zsh** sources `$ENV` before the user's `.zshrc`, so marks are installed
 *    without touching that file.
 *  - **bash** reads `--rcfile`, which sources the user's `.bashrc` first and
 *    then installs the marks, so an existing `PROMPT_COMMAND` still runs.
 *  - **anything else** gets no marks. The emulator reports
 *    [com.termux.terminal.ShellIntegrationLevel.NONE] and the terminal keeps
 *    working; only the optional features are unavailable.
 *
 * `dash`/`sh` have no pre-exec hook of any kind, so exit status is not
 * reachable for them without wrapping every command. That is not attempted.
 */
object DroshShellIntegration {

    const val SCRIPT_FILE_NAME = "dev_drosh_shell_integration"

    data class Plan(
        /** Extra environment entries for the terminal session. */
        val environment: Map<String, String> = emptyMap(),
        /** Extra argv entries appended after the shell path. */
        val shellArgs: List<String> = emptyList(),
    )

    fun install(context: Context, shellPath: String): Plan {
        val name = shellPath.substringAfterLast('/')
        val script = when (name) {
            "zsh" -> writeScript(context, "zsh", ZSH)
            "bash" -> writeScript(context, "bash", BASH)
            else -> return Plan()
        }
        return when (name) {
            "zsh" -> Plan(environment = mapOf("ENV" to script.absolutePath))
            // The bash script sources the user's own .bashrc first.
            "bash" -> Plan(shellArgs = listOf("--rcfile", script.absolutePath))
            else -> Plan()
        }
    }

    /**
     * The `editor` command, appended to both shell scripts.
     *
     * A shell function rather than an executable dropped into the rootfs, and
     * that is the whole reason for the shape. A script would need a shebang
     * the *host* kernel can resolve before PRoot is ever entered, and inside a
     * PRoot guest the only resolvable shebangs are host paths — which run the
     * interpreter outside the guest, where `/dev/tty` is not the PTY this
     * terminal owns and the escape sequence would be written into the void.
     * A function is already inside the guest shell, so [__drosh_osc]'s
     * already-open fd on the guest's `/dev/tty` is simply reused.
     *
     * It also means no binary is planted in `/usr/local/bin`, nothing is
     * shadowed on the user's `$PATH`, and removing the feature removes the
     * command with it.
     *
     * The relative-path case is resolved here rather than in the app because
     * `$PWD` is the only authority on where the user actually is; OSC 7
     * reports the same thing but only once the prompt is redrawn, which has not
     * happened yet when a command runs.
     *
     * POSIX sh only — this text is pasted into a zsh script and a bash script
     * alike, so `local`, arrays and zsh globbing syntax are all avoided.
     */
    private val EDITOR_FUNCTION = """
        # `editor <file>` — open a file in Drosh's native editor.
        editor() {
          if [ "${'$'}#" -lt 1 ]; then
            printf 'editor: usage: editor <file>\n' >&2
            return 2
          fi
          __drosh_editor_target="${'$'}1"
          case "${'$'}__drosh_editor_target" in
            /*) ;;
            *) __drosh_editor_target="${'$'}{PWD:-/}/${'$'}__drosh_editor_target" ;;
          esac
          # BEL and ESC would close the OSC before the path was complete.
          __drosh_editor_target=${'$'}(printf '%s' "${'$'}__drosh_editor_target" | tr -d '\007\033')
          if [ -z "${'$'}__drosh_editor_target" ]; then
            printf 'editor: empty path\n' >&2
            return 1
          fi
          __drosh_osc "${'$'}(printf '\033]1339;%s\a' "${'$'}__drosh_editor_target")"
        }
    """

    private fun writeScript(context: Context, suffix: String, body: String): File {
        val dir = File(context.filesDir, "shell-integration").apply { mkdirs() }
        val file = File(dir, "$SCRIPT_FILE_NAME.$suffix")
        file.writeText(body.trimIndent() + "\n")
        file.setReadable(true, false)
        return file
    }

    /**
     * zsh.
     *
     * Marks are written to `/dev/tty` rather than stdout so that a redirected
     * command cannot capture them; the fd is opened once, and if that fails the
     * integration is skipped entirely rather than risking stray bytes in
     * command output.
     *
     * `A` and `B` are injected into `PROMPT` rather than printed, so they are
     * re-emitted whenever the prompt is redrawn — on resize, on SIGCHLD, on
     * reset-prompt. They are wrapped in `%{ %}` so the line editor does not
     * count the escape bytes as printable width, which would misplace the
     * cursor on the prompt line.
     *
     * Existing `precmd`/`preexec` functions are wrapped rather than replaced,
     * and this script runs first, because `$?` is the only place the previous
     * command's status is still intact.
     */
    private val ZSH = """
        # Drosh shell integration: OSC 133 (FinalTerm) prompt/command marks
        # and OSC 7 (working directory). Injected via ${'$'}ENV; the user's
        # .zshrc is never modified.
        if [ -z "${'$'}{DROSH_SHELL_INTEGRATION:-}" ]; then
          export DROSH_SHELL_INTEGRATION=1

          exec {__drosh_tty_fd}<>/dev/tty 2>/dev/null
          if [ -z "${'$'}{__drosh_tty_fd}" ]; then
            unset __drosh_tty_fd
            return 0 2>/dev/null || true
          fi

__drosh_osc() {
            builtin print -rnu -- "${'$'}{__drosh_tty_fd}" -- "${'$'}1" 2>/dev/null
          }

          __drosh_editor_function

          __drosh_wrap_prompt() {
            case "${'$'}PROMPT" in
              *$'\e]133;A'*) return 0 ;;
            esac
            PROMPT=${'$'}'%{\e]133;A\a%}'"${'$'}PROMPT"${'$'}'%{\e]133;B\a%}'
          }

          __drosh_precmd_drosh() {
            # Must be first: ${'$'}? is the previous command's status and any
            # earlier statement would clobber it.
            local __drosh_status=${'$'}?
            __drosh_osc ${'$'}'\e]133;D;'"${'$'}__drosh_status"${'$'}'\a'
            __drosh_osc ${'$'}'\e]7;file://'"${'$'}PWD"${'$'}'\a'
            __drosh_wrap_prompt
          }

          __drosh_preexec_drosh() {
            __drosh_osc ${'$'}'\e]133;C\a'
          }

          if (( ${'$'}{+functions[precmd]} )); then
            functions[_drosh_outer_precmd]=${'$'}functions[precmd]
            precmd() {
              __drosh_precmd_drosh
              _drosh_outer_precmd "${'$'}@"
            }
          else
            precmd() { __drosh_precmd_drosh }
          fi

          if (( ${'$'}{+functions[preexec]} )); then
            functions[_drosh_outer_preexec]=${'$'}functions[preexec]
            preexec() {
              __drosh_preexec_drosh
              _drosh_outer_preexec "${'$'}@"
            }
          else
            preexec() { __drosh_preexec_drosh }
          fi
        fi
    """

    /**
     * bash.
     *
     * `C` is emitted from `PS0`, which bash expands after reading a line and
     * before running it. That is the documented mechanism and the one Windows
     * Terminal uses. `PS0` writes to stderr, verified here rather than assumed,
     * so `ls > out.txt` is not polluted with mark bytes.
     *
     * `D` goes to the tty through `PROMPT_COMMAND`, prepended so `${'$'}?` is
     * still the command's status.
     *
     * Requires bash 5.1+ for `PS0`. On older bash the marks degrade to `A`,
     * `B` and `D`: no command start, so the exit code arrives without a
     * matching start. The emulator reports that as FULL only once it has seen
     * a command mark, so this cannot be mistaken for working integration.
     */
    private val BASH = """
        # Drosh shell integration: OSC 133 (FinalTerm) prompt/command marks
        # and OSC 7 (working directory). Loaded via --rcfile, after the user's
        # own .bashrc, which is never modified.

        [ -f "${'$'}HOME/.bashrc" ] && . "${'$'}HOME/.bashrc"

        if [ -z "${'$'}{DROSH_SHELL_INTEGRATION:-}" ]; then
          export DROSH_SHELL_INTEGRATION=1

          exec {__drosh_tty_fd}<>/dev/tty 2>/dev/null
          if [ -z "${'$'}{__drosh_tty_fd}" ]; then
            unset __drosh_tty_fd
            return 0 2>/dev/null || true
          fi

          __drosh_osc() {
            builtin printf '%s' "${'$'}1" >&"${'$'}__drosh_tty_fd" 2>/dev/null
          }

          __drosh_editor_function

          # Command finished. Must capture ${'$'}? before anything else runs.
          __drosh_precmd() {
            local __drosh_status=${'$'}?
            __drosh_osc ${'$'}'\033]133;D;'"${'$'}__drosh_status"${'$'}'\a'
            __drosh_osc ${'$'}'\033]7;file://'"${'$'}PWD"${'$'}'\a'
          }

          if [ -n "${'$'}{PROMPT_COMMAND:-}" ]; then
            PROMPT_COMMAND="__drosh_precmd;${'$'}PROMPT_COMMAND"
          else
            PROMPT_COMMAND="__drosh_precmd"
          fi

          # PS0 is printed after the line is read, before execution.
          # Zero-width markers are meaningless here, PS0 is not a prompt.
          PS0=${'$'}'\033]133;C\a'
        fi
    """
}
