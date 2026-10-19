package dev.drosh.terminal

/**
 * A guest isolation launcher: turns Drosh's session parameters into the argv +
 * environment of the binary that actually execs the user's shell inside the
 * Ubuntu rootfs.
 *
 * Two implementations exist today:
 *  - [ProotRunner] — drives `proot` (ptrace-based, from the bootstrap).
 *  - [ChrootNgRunner] — drives `libchrootng.so` (ptrace-free, seccomp/SIGSYS,
 *    built from the `mmuhofy/chroot-ng` submodule).
 *
 * [TerminalManager] picks one via [TerminalManager.guestBackend]; the default is
 * `proot` so behaviour is unchanged until chroot-ng is device-verified
 * (see docs/TODO.md — chroot-ng is CI-built and APK-packaged, but its runtime
 * launch path is still pending on-device validation).
 */
interface GuestLauncher {
    fun build(
        guestWorkDir: String? = null,
        shell: String = "/bin/zsh",
        ptyMode: Boolean = true,
        startCommand: String = "",
        environmentHooks: Map<String, String> = emptyMap(),
        shellArgs: List<String> = emptyList(),
    ): GuestCommand

    fun buildBashCommand(command: String, guestWorkDir: String? = null, shell: String = "/bin/zsh"): GuestCommand
}

/**
 * The resolved exec image for a guest session.
 *
 * Unchanged shape from the old `ProotRunner.ProotCommand` so both launchers
 * (and every caller in [TerminalManager]) share one type.
 */
data class GuestCommand(
    val executable: String,
    val argv: List<String>,
    val cwd: String,
    val environment: List<String>,
)
