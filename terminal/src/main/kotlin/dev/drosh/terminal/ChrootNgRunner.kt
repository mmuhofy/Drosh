package dev.drosh.terminal

import java.io.File

/**
 * Drives `libchrootng.so` — the ptrace-free chroot/bind emulator from the
 * `mmuhofy/chroot-ng` submodule (fork of `sylirre/fake-chroot-ng`).
 *
 * chroot-ng intercepts only path-bearing syscalls in-process (seccomp
 * `RET_TRAP` → `SIGSYS`) and runs guest programs with its own userland ELF
 * loader, so it works on static / Go / Rust / raw-syscall binaries where
 * `LD_PRELOAD` tricks and proot's per-syscall ptrace both fail. See the fork's
 * README for the full option reference.
 *
 * CLI shape (from chroot-ng's README):
 *   chroot-ng [options] <rootfs> <program> [args...]
 *
 * chroot-ng builds its own guest environment and only carries `TERM` /
 * `COLORTERM` from the process it was launched with — so every other guest
 * variable is spelled out as `-E VAR=VAL` on the argv rather than in the
 * process environment. The process environment we hand it therefore carries
 * only `TERM` (so chroot-ng picks it up) and nothing that would leak the
 * Android app's namespace into the guest.
 *
 * The runtime launch path is CI-built and APK-packaged but still pending
 * on-device validation (docs/TODO.md). [TerminalManager] defaults to proot
 * until that is done.
 */
class ChrootNgRunner(
    private val bootstrap: UbuntuBootstrap,
    private val nativeLibDir: String
) : GuestLauncher {

    private val chrootNgPath: String get() = "$nativeLibDir/libchrootng.so"
    private val rootfsPath: String get() = bootstrap.rootfsDir.absolutePath

    override fun build(
        guestWorkDir: String?,
        shell: String,
        ptyMode: Boolean,
        startCommand: String,
        environmentHooks: Map<String, String>,
        shellArgs: List<String>,
    ): GuestCommand {
        val wd = when {
            guestWorkDir != null -> guestWorkDir
            File(rootfsPath, "home").exists() -> "/home"
            else -> "/root"
        }

        val argv = mutableListOf<String>().apply {
            add(chrootNgPath)
            add("-u") // fake root identity (uid/gid 0)
            add("-l") // emulate hardlinks where the host refuses link(2)
            add("-R") // ahead-of-time svc rewriting
            if (guestWorkDir != null) {
                add("-w"); add(guestWorkDir)
            }
            add(rootfsPath)
            // Guest env is spelled out as -E flags (chroot-ng ignores the
            // inherited process env apart from TERM/COLORTERM).
            add("-E"); add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            add("-E"); add("HOME=/home")
            add("-E"); add("LANG=C.UTF-8")
            add("-E"); add("TMPDIR=/tmp")
            add("-E"); add("SHELL=$shell")
            for ((k, v) in environmentHooks) {
                add("-E"); add("$k=$v")
            }
            if (startCommand.isNotBlank()) {
                add(shell); add("-c"); add(startCommand)
            } else {
                add(shell); addAll(shellArgs); add("--login")
            }
        }

        return GuestCommand(
            executable = chrootNgPath,
            argv = argv,
            cwd = rootfsPath,
            // Only TERM is inherited by chroot-ng from here.
            environment = listOf("TERM=xterm-256color"),
        )
    }

    override fun buildBashCommand(
        command: String,
        guestWorkDir: String?,
        shell: String,
    ): GuestCommand {
        val wd = guestWorkDir ?: if (File(rootfsPath, "home").exists()) "/home" else "/root"
        val argv = mutableListOf<String>().apply {
            add(chrootNgPath)
            add("-u"); add("-l"); add("-R")
            if (guestWorkDir != null) {
                add("-w"); add(guestWorkDir)
            }
            add(rootfsPath)
            add("-E"); add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            add("-E"); add("HOME=/home")
            add("-E"); add("LANG=C.UTF-8")
            add("-E"); add("TMPDIR=/tmp")
            add("-E"); add("SHELL=$shell")
            // chroot-ng runs a single program; wrap the command the same way
            // ProotRunner does for one-shot execution.
            add(shell); add("-c"); add("stdbuf -oL $command")
        }
        return GuestCommand(
            executable = chrootNgPath,
            argv = argv,
            cwd = rootfsPath,
            environment = listOf("TERM=xterm-256color"),
        )
    }
}
