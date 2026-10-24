package dev.drosh.domain.terminal

/**
 * Where a command is in its lifecycle, as the shell reports it.
 *
 * The app's own vocabulary, not any one terminal's. The Rust engine's
 * `OSC 133` marks and the block engine's inference both land here, so a
 * consumer never has to know which terminal produced the answer.
 */
sealed class CommandStatus {
    /** No command has been observed since the session started. */
    data object Idle : CommandStatus()

    /** A command is executing. */
    data object Running : CommandStatus()

    /** Finished with exit code 0. */
    data object Success : CommandStatus()

    /** Finished with a non-zero exit code. */
    data class Failure(val exitCode: Int) : CommandStatus()

    /**
     * Finished, but the shell could not report an exit code, or the command
     * was aborted (Ctrl-C). Callers must not paint this as an error.
     */
    data object Indeterminate : CommandStatus()
}

/**
 * How much a shell is able to tell the app.
 *
 * `dash`/`sh` have no pre-exec hook at all, so exit status is unreachable
 * without wrapping every command. Rather than pretend, the capability is
 * reported and the consumer degrades.
 */
enum class ShellIntegrationLevel {
    /** Prompt boundaries are known, but no exit code. */
    PROMPT_ONLY,

    /** Full marks, including the exit code. */
    FULL,

    /** Nothing emitted; boundaries are inferred heuristically. */
    NONE,
}

/**
 * Immutable snapshot of what the shell last told the app.
 *
 * @property status where the command is in its lifecycle
 * @property command the command line, when the shell reported one
 * @property startedAtElapsedMs monotonic start, or 0 if never marked
 * @property endedAtElapsedMs monotonic end of the last command, or 0
 * @property exitCode the last exit code, when the shell reported one
 * @property level how much the shell can say
 * @property cwd the last working directory the shell reported (`OSC 7`)
 */
data class CommandSnapshot(
    val status: CommandStatus = CommandStatus.Idle,
    val command: String = "",
    val startedAtElapsedMs: Long = 0L,
    val endedAtElapsedMs: Long = 0L,
    val exitCode: Int? = null,
    val level: ShellIntegrationLevel = ShellIntegrationLevel.NONE,
    val cwd: String = "",
) {
    /**
     * Wall time of the last completed command, or of the running one.
     *
     * The clock is the caller's: domain is pure Kotlin and reads no Android
     * clock, so a caller that wants a live number passes its own monotonic
     * time.
     */
    fun elapsedMs(nowElapsedMs: Long): Long {
        val start = startedAtElapsedMs
        if (start <= 0L) return 0L
        val end = if (status is CommandStatus.Running) nowElapsedMs else endedAtElapsedMs
        return (end - start).coerceAtLeast(0L)
    }

    val isRunning: Boolean get() = status is CommandStatus.Running
}
