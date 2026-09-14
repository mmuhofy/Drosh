package com.iris.irisshell.domain.agent

/**
 * Sealed result type for agent tool execution.
 *
 * Mirrored from AGENT.md §240–246 — Iris Code compatibility target.
 */
sealed class ToolResult {
    data class Success(val output: String) : ToolResult()
    data class Error(val message: String, val cause: Throwable? = null) : ToolResult()
    data class Cancelled(val reason: String) : ToolResult()
    data class AwaitingApproval(val eventId: String) : ToolResult()
}
