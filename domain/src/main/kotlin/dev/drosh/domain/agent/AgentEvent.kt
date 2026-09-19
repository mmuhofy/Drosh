package dev.drosh.domain.agent

/**
 * LLM conversation step — the message history format.
 */
sealed class LlmStep {
    data class User(val text: String) : LlmStep()
    data class Assistant(
        val text: String,
        val toolCalls: List<ToolCall> = emptyList()
    ) : LlmStep()
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: Map<String, Any>
    ) : LlmStep()
    data class ToolResult(
        val callId: String,
        val name: String,
        val result: String
    ) : LlmStep()
}

/**
 * UI-facing agent events — emitted during turn execution.
 * Mirrors IrisCode's AgentEvent (domain/agent/AgentEvent.kt:87).
 */
sealed class AgentEvent {
    data class TextChunk(val text: String) : AgentEvent()

    data class ThinkingChunk(
        val eventId: String,
        val text: String,
        val elapsedMs: Long
    ) : AgentEvent()

    data class ThinkingComplete(val eventId: String, val totalMs: Long) : AgentEvent()

    object TurnComplete : AgentEvent()

    data class ToolCallStarted(val toolName: String, val args: Map<String, Any>) : AgentEvent()

    data class ToolCallCompleted(val toolName: String, val result: ToolResult) : AgentEvent()

    data class BashStarted(
        val eventId: String,
        val command: String
    ) : AgentEvent()

    data class BashOutput(
        val eventId: String,
        val line: String
    ) : AgentEvent()

    data class BashCompleted(
        val eventId: String,
        val output: String,
        val exitCode: Int
    ) : AgentEvent()

    data class TokenUsageUpdate(
        val inputTokens: Int,
        val outputTokens: Int,
        val totalTokens: Int
    ) : AgentEvent()

    data class ProviderError(
        val message: String,
        val retryable: Boolean,
        val attempt: Int = 0
    ) : AgentEvent()

    data class DoomLoopDetected(
        val toolName: String,
        val arguments: Map<String, Any>
    ) : AgentEvent()

    data class SessionStateChanged(val state: SessionState) : AgentEvent()

    object Cancelled : AgentEvent()

    data class TurnStarted(val turnNumber: Int) : AgentEvent()

    data class ProgressUpdate(val text: String) : AgentEvent()

    data class FatalError(val message: String, val cause: Throwable? = null) : AgentEvent()
}

/**
 * Token usage tracking.
 */
data class TokenUsage(
    val input: Int,
    val output: Int,
    val total: Int
)