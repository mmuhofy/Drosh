package dev.drosh.agent.provider

import dev.drosh.agent.di.AgentNetworkModule.AgentHttpClient
import dev.drosh.agent.stream.SseFrameReader
import dev.drosh.agent.stream.ToolCallBuffer
import dev.drosh.agent.stream.intFieldOrNull
import dev.drosh.agent.stream.stringField
import dev.drosh.domain.agent.ChatAdapter
import dev.drosh.domain.agent.FinishReason
import dev.drosh.domain.agent.LlmCredential
import dev.drosh.domain.agent.LlmMessage
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.LlmRequest
import dev.drosh.domain.agent.LlmStreamEvent
import dev.drosh.domain.agent.ProviderKind
import dev.drosh.domain.agent.TokenUsage
import dev.drosh.domain.agent.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Anthropic's Messages API.
 *
 * Inspired by: github.com/anomalyco/opencode — packages/llm/src/protocols/anthropic-messages.ts
 * Adapted for Drosh — dev.drosh
 *
 * ## Not OpenAI Chat Completions
 *
 * Three differences drive most of this file:
 *
 * 1. **`max_tokens` is required.** Chat Completions defaults it; Anthropic
 *    rejects a request without it with a 400 whose body does not say which
 *    field was missing, so a caller that omits it sees "malformed request".
 *    The loop supplies the catalog's declared ceiling (see
 *    [LlmRequest.maxOutputTokens]); when there is none this adapter fails
 *    loudly rather than substituting a number, because every candidate default
 *    would be a guess.
 * 2. **Authentication is `x-api-key`, not `Authorization: Bearer`.** Sending
 *    the latter yields a 401 that reads as a bad key.
 * 3. **The system prompt is a top-level block array**, not a `system` role in
 *    `messages` — a system *message* is accepted only as the beta per-message
 *    effort mechanism.
 *
 * ## Tool calls close per block
 *
 * Unlike Chat Completions, where all pending calls complete at once on a
 * terminal `finish_reason`, Anthropic closes each content block with its own
 * `content_block_stop`. A call is therefore emitted as soon as its arguments
 * stop arriving, which is why [ToolCallBuffer.finish] exists.
 *
 * ## Consecutive tool results must share one user message
 *
 * Anthropic requires roles to alternate. Two `tool_result` blocks sent as two
 * `user` messages — which is what a naive 1:1 mapping of
 * [LlmMessage.ToolResultMessage] produces — returns a 400. They are merged
 * into a single user message carrying both blocks instead.
 */
@Singleton
class AnthropicAdapter @Inject constructor(
    @AgentHttpClient private val httpClient: OkHttpClient,
    private val json: Json,
    private val efforts: EffortMapper,
) : ChatAdapter {

    override val kind: ProviderKind = ProviderKind.ANTHROPIC

    override fun stream(
        provider: LlmProvider,
        request: LlmRequest,
        credential: LlmCredential,
    ): Flow<LlmStreamEvent> = flow {
        // Built outside the try that covers the network call: a request that
        // cannot be assembled is not a transport failure, and the message the
        // user needs is about the body, not the socket.
        val call = try {
            httpClient.newCall(buildHttpRequest(provider, request, credential))
        } catch (error: MissingMaxTokens) {
            emit(LlmStreamEvent.Failed(error.message ?: "max_tokens is required", retryable = false))
            return@flow
        }
        try {
            val response = call.execute()
            response.use {
                if (!it.isSuccessful) {
                    val body = it.body.string()
                    emit(
                        LlmStreamEvent.Failed(
                            message = describeHttpFailure(it.code, body),
                            retryable = isRetryable(it.code),
                        ),
                    )
                    return@flow
                }
                pump(body = it.body, call = call)
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            emit(
                LlmStreamEvent.Failed(
                    message = error.message ?: error::class.java.simpleName,
                    retryable = error.isRetryable(),
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Consume the SSE body.
     *
     * Local suspend function rather than a private method taking the collector,
     * so every piece of per-stream mutable state is plainly scoped to one
     * stream and cannot leak between two runs.
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<LlmStreamEvent>.pump(
        body: okhttp3.ResponseBody,
        call: okhttp3.Call,
    ) {
        val frames = SseFrameReader()
        val toolCalls = ToolCallBuffer(json)
        val reasoning = StringBuilder()
        val announcedCalls = mutableSetOf<String>()

        var finishReason = FinishReason.OTHER
        var usage: TokenUsage? = null
        var providerError: String? = null
        var sawMessageStop = false

        suspend fun handleFrame(payload: String) {
            when (val frame = parseFrame(payload)) {
                is Frame.Text -> emit(LlmStreamEvent.TextDelta(frame.delta))

                is Frame.Reasoning -> {
                    reasoning.append(frame.delta)
                    emit(LlmStreamEvent.ReasoningDelta(frame.delta))
                }

                is Frame.ToolStart -> {
                    val appended = toolCalls.append(
                        index = frame.blockIndex,
                        id = frame.id,
                        name = frame.name,
                        argsDelta = null,
                    ) ?: return
                    val id = appended.id
                    val name = appended.name
                    if (id != null && name != null && id !in announcedCalls) {
                        announcedCalls += id
                        emit(LlmStreamEvent.ToolCallStarted(id, name))
                    }
                }

                is Frame.ToolArgs -> {
                    toolCalls.append(
                        index = frame.blockIndex,
                        id = null,
                        name = null,
                        argsDelta = frame.delta,
                    )
                }

                is Frame.ToolDone -> {
                    toolCalls.finish(frame.blockIndex)?.let { resolved ->
                        if (resolved.id !in announcedCalls) {
                            emit(LlmStreamEvent.ToolCallStarted(resolved.id, resolved.name))
                        }
                        emit(LlmStreamEvent.ToolCallCompleted(resolved.id, resolved.name, resolved.arguments))
                    }
                }

                is Frame.Stop -> finishReason = frame.reason
                is Frame.Usage -> usage = mergeUsage(usage, frame.usage)
                is Frame.Failure -> providerError = frame.message
                is Frame.Done -> sawMessageStop = true
                Frame.Ignore -> Unit
            }
        }

        try {
            body.source().use { source ->
                while (true) {
                    currentCoroutineContext().ensureActive()

                    val line = source.readUtf8Line()
                    if (line == null) {
                        frames.flush()?.let { payload -> handleFrame(payload) }
                        break
                    }

                    frames.accept(line)?.let { payload -> handleFrame(payload) }
                }
            }
        } finally {
            call.cancel()
        }

        providerError?.let { error ->
            emit(LlmStreamEvent.Failed(error, retryable = false))
            return
        }

        if (reasoning.isNotEmpty()) {
            emit(LlmStreamEvent.ReasoningCompleted(reasoning.toString()))
        }

        // A block that never received its own stop — a proxy that closed early —
        // is still a call the model asked for.
        toolCalls.finishAll().forEach { resolved ->
            if (resolved.id !in announcedCalls) {
                emit(LlmStreamEvent.ToolCallStarted(resolved.id, resolved.name))
            }
            emit(LlmStreamEvent.ToolCallCompleted(resolved.id, resolved.name, resolved.arguments))
        }

        if (!sawMessageStop && finishReason == FinishReason.OTHER) {
            emit(
                LlmStreamEvent.Failed(
                    message = "Stream ended without a stop reason or a message_stop",
                    retryable = true,
                ),
            )
            return
        }

        emit(LlmStreamEvent.Finished(finishReason, usage))
    }

    // ── frame parsing ─────────────────────────────────────────────────────

    /** One parsed Anthropic SSE event. */
    internal sealed interface Frame {
        data object Ignore : Frame
        data class Text(val delta: String) : Frame
        data class Reasoning(val delta: String) : Frame

        /** A tool_use block opened. Carries no arguments yet. */
        data class ToolStart(val blockIndex: Int, val id: String?, val name: String?) : Frame

        /** A fragment of `input_json_delta`, keyed by content-block index. */
        data class ToolArgs(val blockIndex: Int, val delta: String) : Frame

        /** A content block closed. */
        data class ToolDone(val blockIndex: Int) : Frame

        data class Stop(val reason: FinishReason) : Frame
        data class Usage(val usage: TokenUsage) : Frame
        data class Failure(val message: String) : Frame
        data object Done : Frame
    }

    internal fun parseFrame(payload: String): Frame {
        val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
            ?: return Frame.Ignore

        (root["error"] as? JsonObject)?.let { error ->
            return Frame.Failure(error.stringField("message") ?: "Provider reported an error mid-stream")
        }

        return when (val type = root.stringField("type")) {
            "message_start" -> {
                val usage = (root["message"] as? JsonObject)?.get("usage") as? JsonObject
                usage?.let { Frame.Usage(parseUsage(it)) } ?: Frame.Ignore
            }

            "content_block_start" -> {
                val block = root["content_block"] as? JsonObject ?: return Frame.Ignore
                val index = root.intFieldOrNull("index") ?: 0
                when (block.stringField("type")) {
                    "tool_use", "server_tool_use" -> Frame.ToolStart(
                        blockIndex = index,
                        id = block.stringField("id"),
                        name = block.stringField("name"),
                    )

                    "text" -> block.stringField("text")?.takeIf { it.isNotEmpty() }
                        ?.let { Frame.Text(it) } ?: Frame.Ignore

                    "thinking" -> block.stringField("thinking")?.takeIf { it.isNotEmpty() }
                        ?.let { Frame.Reasoning(it) } ?: Frame.Ignore

                    else -> Frame.Ignore
                }
            }

            "content_block_delta" -> {
                val delta = root["delta"] as? JsonObject ?: return Frame.Ignore
                val index = root.intFieldOrNull("index") ?: 0
                when (delta.stringField("type")) {
                    "text_delta" -> delta.stringField("text")?.let { Frame.Text(it) } ?: Frame.Ignore
                    "thinking_delta" -> delta.stringField("thinking")?.let { Frame.Reasoning(it) }
                        ?: Frame.Ignore

                    // Raw JSON, split at arbitrary boundaries. Assembled as text
                    // and parsed once the block closes.
                    "input_json_delta" -> delta.stringField("partial_json")
                        ?.let { Frame.ToolArgs(index, it) } ?: Frame.Ignore

                    // Signature deltas carry no visible text; the signature is
                    // only needed to replay the block, which this loop does not.
                    else -> Frame.Ignore
                }
            }

            "content_block_stop" -> Frame.ToolDone(root.intFieldOrNull("index") ?: 0)

            "message_delta" -> {
                val usage = root["usage"] as? JsonObject
                val delta = root["delta"] as? JsonObject
                val reason = delta?.stringField("stop_reason")
                when {
                    reason != null -> Frame.Stop(mapFinishReason(reason))
                    usage != null -> Frame.Usage(parseUsage(usage))
                    else -> Frame.Ignore
                }
            }

            "message_stop" -> Frame.Done
            "ping" -> Frame.Ignore
            else -> Frame.Ignore
        }
    }

    private fun mapFinishReason(raw: String): FinishReason = when (raw) {
        "end_turn", "stop_sequence" -> FinishReason.STOP
        "max_tokens" -> FinishReason.MAX_TOKENS
        "tool_use" -> FinishReason.TOOL_CALLS
        "refusal" -> FinishReason.ERROR
        else -> FinishReason.OTHER
    }

    /**
     * Anthropic reports a non-overlapping breakdown, so nothing here is summed.
     *
     * `cache_creation_input_tokens` is a write and is kept as one: folding it
     * into `input` would double-count it against the next request's read.
     */
    private fun parseUsage(raw: JsonObject): TokenUsage = TokenUsage(
        input = raw.intFieldOrNull("input_tokens"),
        output = raw.intFieldOrNull("output_tokens"),
        cacheRead = raw.intFieldOrNull("cache_read_input_tokens"),
        cacheWrite = raw.intFieldOrNull("cache_creation_input_tokens"),
        reasoning = (raw["output_tokens_details"] as? JsonObject)?.intFieldOrNull("thinking_tokens"),
    )

    /** Later events refine earlier ones; the later value wins per field. */
    private fun mergeUsage(existing: TokenUsage?, incoming: TokenUsage): TokenUsage =
        if (existing == null) {
            incoming
        } else {
            TokenUsage(
                input = incoming.input ?: existing.input,
                output = incoming.output ?: existing.output,
                cacheRead = incoming.cacheRead ?: existing.cacheRead,
                cacheWrite = incoming.cacheWrite ?: existing.cacheWrite,
                reasoning = incoming.reasoning ?: existing.reasoning,
            )
        }

    // ── request building ──────────────────────────────────────────────────

    internal fun buildBody(provider: LlmProvider, request: LlmRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", true)

        // Required, and there is no default to fall back on. Every candidate
        // value would be invented, so the loop supplies the catalog's declared
        // ceiling and this refuses to run without one.
        val maxTokens = request.maxOutputTokens
            ?: throw MissingMaxTokens(
                "Anthropic's Messages API requires max_tokens, and the catalog " +
                    "declares no output ceiling for '${request.model}'.",
            )

        put("max_tokens", maxTokens)

        request.temperature?.let { put("temperature", it) }

        // A system prompt is blocks, not a message.
        request.systemPrompt?.takeIf { it.isNotBlank() }?.let { system ->
            put("system", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", system)
                })
            })
        }

        put("messages", encodeMessages(request.messages))

        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray { request.tools.forEach { add(encodeTool(it)) } })
            // Anthropic's default is `auto`; saying so explicitly costs nothing
            // and makes the intent legible in the request.
            put("tool_choice", buildJsonObject { put("type", "auto") })
        }

        request.reasoningEffort?.takeIf { it.isNotBlank() }?.let { effort ->
            efforts.bodyFields(
                provider = provider,
                modelId = request.model,
                effort = effort,
                outputTokenLimit = maxTokens,
            ).forEach { (key, value) -> put(key, value) }
        }
    }

    /**
     * Encode the conversation, merging consecutive tool results.
     *
     * A run that calls two tools produces two [LlmMessage.ToolResultMessage]s
     * back to back. Sent as two `user` messages Anthropic answers with a 400
     * about alternating roles; sent as one `user` message carrying both
     * `tool_result` blocks it is the documented shape.
     */
    private fun encodeMessages(messages: List<LlmMessage>): JsonArray {
        val encoded = mutableListOf<JsonObject>()

        for (message in messages) {
            when (message) {
                is LlmMessage.User -> encoded += buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", message.text)
                        })
                    })
                }

                is LlmMessage.Assistant -> {
                    val blocks = buildJsonArray {
                        // An assistant turn that only called tools must not
                        // carry an empty text block.
                        message.text.takeIf { it.isNotEmpty() }?.let { text ->
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", text)
                            })
                        }
                        message.toolCalls.forEach { toolCall ->
                            add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", toolCall.id)
                                put("name", toolCall.name)
                                // Nested, not a JSON string: Anthropic takes the
                                // object where Chat Completions takes text.
                                put("input", toolCall.arguments)
                            })
                        }
                    }
                    if (blocks.isNotEmpty()) {
                        encoded += buildJsonObject {
                            put("role", "assistant")
                            put("content", blocks)
                        }
                    }
                }

                is LlmMessage.ToolResultMessage -> {
                    val block = buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", message.callId)
                        put("content", message.content)
                    }
                    val previous = encoded.lastOrNull()
                    val previousBlocks = previous?.get("content") as? JsonArray
                    if (previous != null && previous.stringField("role") == "user" && previousBlocks != null) {
                        encoded[encoded.lastIndex] = buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                previousBlocks.forEach { add(it) }
                                add(block)
                            })
                        }
                    } else {
                        encoded += buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray { add(block) })
                        }
                    }
                }
            }
        }

        return buildJsonArray { encoded.forEach { add(it) } }
    }

    private fun encodeTool(tool: ToolDefinition): JsonObject = buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        // `input_schema`, and it is the object rather than a JSON string.
        put("input_schema", tool.parameters)
    }

    internal fun buildHttpRequest(
        provider: LlmProvider,
        request: LlmRequest,
        credential: LlmCredential,
    ): Request {
        val url = "${provider.baseUrl.trimEnd('/')}$MESSAGES_PATH"

        return Request.Builder()
            .url(url)
            .post(buildBody(provider, request).toString().toRequestBody(JSON_MEDIA_TYPE))
            // Not `Authorization: Bearer` — Anthropic answers that with a 401
            // that reads exactly like a wrong key.
            .header("x-api-key", credential.apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Accept", "text/event-stream")
            .apply { provider.extraHeaders.forEach { (name, value) -> header(name, value) } }
            .build()
    }

    private fun describeHttpFailure(code: Int, body: String): String {
        val message = runCatching {
            val root = json.parseToJsonElement(body) as? JsonObject
            (root?.get("error") as? JsonObject)?.stringField("message")
        }.getOrNull()
        return message?.takeIf { it.isNotBlank() } ?: "Provider returned HTTP $code"
    }

    private fun isRetryable(code: Int): Boolean =
        ProviderErrorClassifier.classify(code) == ProviderErrorClassifier.Decision.RETRY

    private fun Throwable.isRetryable(): Boolean =
        ProviderErrorClassifier.classify(this) == ProviderErrorClassifier.Decision.RETRY

    private companion object {
        const val MESSAGES_PATH = "/messages"

        // UNTESTED — verify before use. OpenCode pins this date, but
        // `output_config.effort` and adaptive thinking are newer features and
        // may need a later version string. If Anthropic answers a request
        // carrying both with an unrecognised-field 400, this is the value to
        // raise, and the error will name the field it did not expect.
        const val ANTHROPIC_VERSION = "2023-06-01"

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/** The catalog declared no output ceiling for a model that needs one. */
internal class MissingMaxTokens(message: String) : IllegalStateException(message)
