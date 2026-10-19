package dev.drosh.domain.agent

/**
 * One reconstructed terminal line, for the read-only shell view.
 *
 * A phone terminal, not a shell: it shows what ran and what came back, and
 * nothing can be typed into it. The distinction is deliberate — a control that
 * looks like an input and is not is worse than no control.
 */
sealed interface TerminalLine {

    /** The seam between two commands, as a real terminal shows it. */
    data object Divider : TerminalLine

    /** An echo of the command itself. */
    data class Command(val text: String) : TerminalLine

    /** A line of output, already split. */
    data class Output(val text: String) : TerminalLine

    /**
     * The exit status, rendered where a real terminal would leave the cursor.
     *
     * Distinct from [Output] so the view can colour it without parsing text.
     *
     * [durationMs] rides along so the view can show how long the command took
     * without reaching back into the transcript. Null when the run was cut short
     * before a duration was recorded, which is the honest answer for a command the
     * process was killed during — showing 0.0s there would claim it was instant.
     */
    data class Status(
        val code: Int,
        val failed: Boolean,
        val durationMs: Long? = null,
    ) : TerminalLine
}

/**
 * Reconstructs a terminal view from a transcript.
 *
 * ## Why this is not a terminal
 *
 * A real PTY per agent chat costs a PRoot process each: memory on a device that
 * already runs one for the user's session, and battery while it sits idle. The
 * questions a terminal view is asked to answer — *what did it just run, and what
 * did it say* — are already answered by the tool rows.
 *
 * So this is a projection, not an emulation. It cannot be typed into, and it has
 * no prompt, because offering either would promise interactivity the underlying
 * session does not have.
 *
 * ## Why the exit code is parsed out
 *
 * [ShellTool] prefixes its result with `exit N` before the output. Carrying that
 * as structured data rather than leaving it as a line of prose is what lets the
 * view mark a failure in colour and a reader tell "it failed" from "it printed
 * something that looks like an error".
 */
object TerminalProjection {

    private const val EXIT_PREFIX = "exit "

    fun project(messages: List<ChatMessage>): List<TerminalLine> {
        val lines = mutableListOf<TerminalLine>()
        var seenCommand = false

        messages.forEach { message ->
            when (message) {
                is ChatMessage.ToolCall -> {
                    if (message.name != SHELL_TOOL) return@forEach

                    // Only finished calls: a command still running has no result to
                    // show, and showing its output as it streams is the tool row's
                    // job, not this view's.
                    if (message.state != ToolCallState.Succeeded &&
                        message.state != ToolCallState.Failed &&
                        message.state != ToolCallState.Cancelled
                    ) {
                        return@forEach
                    }

                    if (seenCommand) lines += TerminalLine.Divider
                    seenCommand = true

                    lines += TerminalLine.Command(
                        message.summary.ifBlank { "(no command recorded)" },
                    )

                    val body = message.finalOutput ?: message.output.joinToString("\n")
                    body.split("\n").forEach { line ->
                        line.toTerminalLine()?.let { lines += it }
                    }

                    val status = message.state.toExitStatus(message.durationMs)
                    if (status != null) lines += status
                }

                else -> Unit
            }
        }

        return lines
    }

    /** Whether there is anything to show at all. */
    fun isEmpty(messages: List<ChatMessage>): Boolean =
        messages.none { it is ChatMessage.ToolCall && it.name == SHELL_TOOL }

    /**
     * Map one line of a tool result.
     *
     * The `exit N` prefix is dropped: the status is emitted once, structurally,
     * from the call's state after the output. Leaving the prefix as an output line
     * would show the same fact twice, once as prose and once as a status.
     *
     * `ERROR:` and `CANCELLED:` are kept as output, not turned into a second
     * status. They carry the *reason* — "user declined", "exit code 127" — and the
     * state already says whether it failed. A tool that turned both into status
     * markers rendered two failure lines for one failure, which reads as two
     * separate things having gone wrong.
     */
    private fun String.toTerminalLine(): TerminalLine? = when {
        startsWith(EXIT_PREFIX) -> null
        else -> TerminalLine.Output(this)
    }

    private fun ToolCallState.toExitStatus(durationMs: Long?): TerminalLine.Status? = when (this) {
        ToolCallState.Succeeded -> TerminalLine.Status(0, failed = false, durationMs = durationMs)
        ToolCallState.Failed -> TerminalLine.Status(-1, failed = true, durationMs = durationMs)
        ToolCallState.Cancelled -> TerminalLine.Status(-1, failed = true, durationMs = durationMs)
        else -> null
    }

    /** Must match [ShellTool.NAME]. */
    const val SHELL_TOOL = "shell"
}
