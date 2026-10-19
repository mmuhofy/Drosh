package dev.drosh.domain.agent

import kotlinx.serialization.json.JsonObject

/**
 * Normalized token-stream events produced by a [ChatAdapter].
 *
 * ## Why there is no `index` here
 *
 * Providers disagree on how they identify a tool call *while it is streaming*:
 * OpenAI Chat uses a numeric `choices[].delta.tool_calls[].index`, OpenAI
 * Responses uses a string `item_id`, Anthropic uses a numeric content-block
 * index. That key is a per-protocol implementation detail and it changes
 * meaning mid-stream, so it does not belong in a type the UI and the loop see.
 *
 * Adapters therefore accumulate against their own protocol-local key and emit
 * only resolved events here: [ToolCallStarted] once the call is known and
 * [ToolCallCompleted] once its arguments parse. A provider that streams a call
 * with no id synthesizes a stable one — the model never sees it, only the tool
 * implementation does, and only as a correlation handle.
 *
 * Adapters also parse the accumulated argument JSON themselves. They know their
 * own protocol's malformed-input quirks; making the loop re-implement that per
 * provider is exactly the duplication this layer exists to remove.
 */
sealed interface LlmStreamEvent {

    /** Visible assistant text. */
    data class TextDelta(val text: String) : LlmStreamEvent

    /** Hidden reasoning, for models that expose it. */
    data class ReasoningDelta(val text: String) : LlmStreamEvent

    /**
     * Reasoning finished, carrying the accumulated text.
     *
     * Emitted even when there were no [ReasoningDelta]s, so the UI can clear
     * the thinking indicator without tracking deltas itself.
     */
    data class ReasoningCompleted(val text: String) : LlmStreamEvent

    /** A tool call was recognised and is being streamed. */
    data class ToolCallStarted(val callId: String, val name: String) : LlmStreamEvent

    /** Arguments arrived and parsed. The call is ready to dispatch. */
    data class ToolCallCompleted(
        val callId: String,
        val name: String,
        val arguments: JsonObject,
    ) : LlmStreamEvent

    /** Stream closed normally. [usage] is whatever the provider reported. */
    data class Finished(
        val reason: FinishReason,
        val usage: TokenUsage? = null,
    ) : LlmStreamEvent

    /**
     * Stream failed.
     *
     * @param retryable whether the loop should back off and resend the same
     *                  request. Rate limits and 5xx are retryable; a 400 from a
     *                  malformed tool schema is not.
     */
    data class Failed(val message: String, val retryable: Boolean) : LlmStreamEvent
}

enum class FinishReason {
    /** Model finished its answer; the run is over. */
    STOP,

    /** Hit the output token ceiling. Truncated output is not retryable. */
    MAX_TOKENS,

    /** Ended because it emitted tool calls; the loop continues. */
    TOOL_CALLS,

    /** Provider-reported error inside an otherwise 200 response. */
    ERROR,

    /** Anything else; treated like [STOP] unless there are pending tool calls. */
    OTHER,
}

/**
 * Token counts exactly as the provider reported them.
 *
 * All fields are nullable because coverage differs: OpenAI and Gemini report
 * inclusive input/output totals, Anthropic reports a non-overlapping
 * breakdown, and cache fields exist only on some providers. Nothing is derived
 * and nothing is summed — a consumer that wants a total computes it from the
 * fields that are actually present, rather than trusting a number this type
 * invented.
 */
data class TokenUsage(
    val input: Int? = null,
    val output: Int? = null,
    val cacheRead: Int? = null,
    val cacheWrite: Int? = null,
    val reasoning: Int? = null,
) {
    /**
     * Sum of the non-overlapping buckets, or null when the provider reported
     * no counts at all. [reasoning] is deliberately excluded: it is a subset of
     * [output], not an additional bucket.
     */
    val total: Int?
        get() {
            val sum = (input ?: 0) + (output ?: 0) + (cacheRead ?: 0) + (cacheWrite ?: 0)
            return sum.takeIf { it > 0 }
        }

    /**
     * Running total across a run.
     *
     * A run spans many requests and each reports its own counts, so the UI
     * accumulates: showing the last turn alone would make a long run look
     * cheaper than a short one.
     *
     * Null is treated as zero rather than skipped, so a provider that stops
     * reporting a bucket does not make the total drift down.
     */
    operator fun plus(other: TokenUsage): TokenUsage = TokenUsage(
        input = (input ?: 0) + (other.input ?: 0),
        output = (output ?: 0) + (other.output ?: 0),
        cacheRead = (cacheRead ?: 0) + (other.cacheRead ?: 0),
        cacheWrite = (cacheWrite ?: 0) + (other.cacheWrite ?: 0),
        reasoning = (reasoning ?: 0) + (other.reasoning ?: 0),
    )

    /** True when nothing has been reported yet. */
    val isEmpty: Boolean
        get() = (input ?: 0) == 0 && (output ?: 0) == 0
}
