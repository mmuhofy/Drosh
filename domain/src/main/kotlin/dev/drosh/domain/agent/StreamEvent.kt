package dev.drosh.domain.agent

/**
 * Sealed event types for the provider's token-stream.
 *
 * Normalizes OpenAI-compatible SSE chunks into a provider-agnostic format.
 * The AgentRuntime consumes these to drive the multi-step loop.
 *
 * Based on IrisCode's StreamChunk (data/remote/ProviderAdapter.kt:16) pattern,
 * adapted for Drosh's simpler single-provider model.
 */
sealed class StreamEvent {
    data class TextDelta(val text: String) : StreamEvent()
    data class ReasoningDelta(val text: String) : StreamEvent()
    data object ReasoningComplete : StreamEvent()

    data class ToolCallStart(
        val index: Int,
        val id: String,
        val name: String
    ) : StreamEvent()

    data class ToolCallDelta(val index: Int, val argsDelta: String) : StreamEvent()
    data object StreamEnd : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}

/**
 * Accumulated tool call — assembled from ToolCallStart + ToolCallDelta stream.
 */
data class AccumulatedToolCall(
    val index: Int,
    val id: String,
    val name: String,
    val argumentsBuilder: StringBuilder = StringBuilder()
) {
    val argumentsJson: String get() = argumentsBuilder.toString()

    fun appendArgs(delta: String) {
        argumentsBuilder.append(delta)
    }
}

/**
 * Request to the provider — simplified to endpoint + model + standard fields.
 */
data class StreamRequest(
    val apiKey: String,
    val model: String,
    val messages: List<LlmStep>,
    val tools: List<ToolDef>,
    val systemPrompt: String?,
    val baseUrl: String,
    val timeoutSec: Long = 120L
)