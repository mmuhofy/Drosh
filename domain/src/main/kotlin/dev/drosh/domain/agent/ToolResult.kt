package dev.drosh.domain.agent

/**
 * Sealed result type for agent tool execution.
 *
 * Success      → tool ran, output is available for LLM context
 * Error        → tool failed, message is surfaced to agent
 * Cancelled    → tool was blocked (e.g. PLAN mode blocks write_file)
 * AwaitingApproval → tool triggered a blocking UI flow, agent must pause until resolved
 */
sealed class ToolResult {

    data class Success(val output: String) : ToolResult()

    data class Error(val message: String, val cause: Throwable? = null) : ToolResult()

    data class Cancelled(val reason: String) : ToolResult()

    data class AwaitingApproval(val eventId: String) : ToolResult()

    val isTerminal: Boolean
        get() = this is Success || this is Error || this is Cancelled

    fun toResponseString(): String = when (this) {
        is Success -> output
        is Error -> "ERROR: $message"
        is Cancelled -> "CANCELLED: $reason"
        is AwaitingApproval -> "AWAITING_APPROVAL: $eventId"
    }
}