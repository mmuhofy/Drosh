package dev.drosh.domain.agent

/**
 * Result of a single tool invocation.
 *
 * The agent loop feeds these back into the conversation history so the model
 * can react to what happened. [toResponseString] is the exact text that goes
 * back to the model — keep it explicit and short, the model reads it verbatim.
 *
 * Success            tool ran, [output] is available to the model
 * Error              tool failed, [message] is surfaced to the model
 * Cancelled          tool was blocked or the user declined it
 * AwaitingApproval   tool is waiting on the user; the loop is suspended and
 *                     resumes once the approval resolves. The normal way to ask
 *                     is [ToolContext.awaitApproval], which lets a tool block
 *                     and then continue; this variant exists for a tool that
 *                     discovers it needs consent only after doing work and
 *                     cannot usefully resume in the same call.
 *
 * Note: `terminal/TerminalManager.executeCommand` also returns this type, so
 * it lives in `:domain` rather than `:agent` — `:terminal` has no dependency on
 * the agent module.
 */
sealed class ToolResult {

    data class Success(val output: String) : ToolResult()

    data class Error(val message: String, val cause: Throwable? = null) : ToolResult()

    data class Cancelled(val reason: String) : ToolResult()

    data class AwaitingApproval(val approvalId: String) : ToolResult()

    /**
     * True when the result is final — no further resolution is expected.
     * [AwaitingApproval] is deliberately false: the loop is parked, not done.
     */
    val isTerminal: Boolean
        get() = this !is AwaitingApproval

    fun toResponseString(): String = when (this) {
        is Success -> output
        is Error -> "ERROR: $message"
        is Cancelled -> "CANCELLED: $reason"
        is AwaitingApproval -> "AWAITING_APPROVAL: $approvalId"
    }
}
