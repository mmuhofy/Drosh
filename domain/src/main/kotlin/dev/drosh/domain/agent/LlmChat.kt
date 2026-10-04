package dev.drosh.domain.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * One wire protocol, one adapter.
 *
 * Implementations live in `agent/provider/` and return [LlmStreamEvent], so
 * nothing above this interface knows whether it is talking to Gemini or to
 * something OpenAI-shaped.
 *
 * ## The credential is an argument, not a field
 *
 * [stream] takes the credential per call rather than the adapter holding one.
 * An adapter is a singleton in Hilt; a key stored on it would outlive the user
 * signing out of that provider and would make the adapter untestable without
 * reflection.
 *
 * ## No `countTokens`
 *
 * There is deliberately no token-counting method. Nothing counts tokens
 * accurately without the provider's tokenizer, and a guessed count used for
 * truncation decisions is worse than none. Token counts come back in
 * [LlmStreamEvent.Finished] as [TokenUsage] and are used for display and for
 * deciding when to compact, never as a hard input limit.
 */
interface ChatAdapter {

    /**
     * The wire protocol this adapter speaks.
     *
     * An adapter is a protocol handler, not a vendor: one [OpenAiCompatAdapter][
     * dev.drosh.domain.agent.ProviderKind.OPENAI_COMPAT] serves OpenRouter,
     * OpenAI and anything else speaking Chat Completions, because adding a
     * vendor is a row in the catalog rather than another class.
     */
    val kind: ProviderKind

    /**
     * Send one request and stream the response.
     *
     * [provider] is passed per call rather than held as a field so a single
     * adapter instance serves every configured endpoint of its protocol — the
     * user can add a second OpenAI-compatible base URL without a second object.
     *
     * The flow emits [LlmStreamEvent]s and completes normally when the stream
     * ends. Failures are reported as [LlmStreamEvent.Failed] rather than thrown,
     * so the loop can classify and retry them without catching around every
     * collection.
     *
     * Cancellation follows the collecting coroutine, as with any cold [Flow];
     * implementations must not swallow it.
     */
    fun stream(
        provider: LlmProvider,
        request: LlmRequest,
        credential: LlmCredential,
    ): Flow<LlmStreamEvent>
}

/**
 * One request to the model.
 *
 * @param messages conversation history, oldest first
 * @param tools tool declarations; empty disables tool calling entirely
 */
data class LlmRequest(
    val model: String,
    val messages: List<LlmMessage>,
    val tools: List<ToolDefinition> = emptyList(),
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    /** Sent as a system/developer instruction where the protocol supports it. */
    val systemPrompt: String? = null,
)

/** Model-facing conversation history. Distinct from [ChatMessage]. */
sealed interface LlmMessage {

    data class User(val text: String) : LlmMessage

    data class Assistant(
        val text: String,
        val toolCalls: List<LlmToolCall> = emptyList(),
    ) : LlmMessage

    /**
     * The result of one tool call, fed back so the model can react.
     *
     * [content] is the text the model sees: [ToolResult.toResponseString], after
     * [ToolOutputTrimmer] has clipped it.
     */
    data class ToolResultMessage(
        val callId: String,
        val name: String,
        val content: String,
    ) : LlmMessage
}

data class LlmToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
)

/** A tool declaration as sent to the model. */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)
