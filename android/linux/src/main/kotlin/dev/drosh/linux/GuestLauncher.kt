package dev.drosh.linux

/**
 * A guest isolation launcher: turns Drosh's session parameters into the argv
 * and the environment of the binary that execs the user's shell inside the
 * Ubuntu rootfs.
 *
 * One implementation exists:
 *
 *  - [ChrootNgRunner] — drives `libchrootng.so` (ptrace-free, seccomp/SIGSYS,
 *    built and published by the chroot-ng project).
 *
 * The interface describes the *session*, not the backend's quirks: chroot-ng
 * needs nothing beyond the program and its arguments. Which of the two shapes
 * a call is — an interactive session on a PTY, or a one-shot command on pipes
 * — is what the method says.
 */
interface GuestLauncher {
    /**
     * Whether this engine's runtime is where it is expected to be.
     *
     * A missing runtime is a packaging fact, not a user error: the guest
     * binary is not in this repository, it is fetched by the build, and a build
     * that skipped the fetch has no way to run the guest. The app checks this
     * before it starts a session, so the answer is a sentence in the UI rather
     * than an `ENOENT` in a log.
     */
    val isAvailable: Boolean
        get() = true

    /** The exec image for an interactive session, which runs on a PTY. */
    fun build(spec: GuestSpec): GuestCommand

    /** The exec image for a one-shot command, which runs on pipes. */
    fun buildCommand(spec: GuestSpec, command: String): GuestCommand
}

/**
 * What a guest session needs. The backend decides how to satisfy it — the
 * same session means a different argv for proot and for chroot-ng.
 */
data class GuestSpec(
    /** Where the guest starts, as a guest path; null means the default home. */
    val workingDirectory: String? = null,
    /** The shell to run. */
    val shell: String = "/bin/zsh",
    /** A command to run instead of the shell (the agent's shell tool). */
    val command: String = "",
    /** Extra environment variables for the guest. */
    val environment: Map<String, String> = emptyMap(),
    /** Arguments passed to the shell. */
    val shellArgs: List<String> = emptyList(),
)

/**
 * The resolved exec image for a guest session: what to run, with what argv, in
 * what directory, and with which environment.
 */
data class GuestCommand(
    val executable: String,
    val argv: List<String>,
    val cwd: String,
    val environment: List<String>,
)
