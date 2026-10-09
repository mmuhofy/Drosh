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
 * OpenAI's Responses API.
 *
 * Inspired by: github.com/anomalyco/opencode — packages/llm/src/protocols/openai-responses.ts
 * Adapted for Drosh — dev.drosh
 *
 * ## Not Chat Completions
 *
 * The two share a vendor and a bearer token and almost nothing else. Three
 * differences justify a separate adapter rather than a flag on
 * [OpenAiCompatAdapter]:
 *
 * 1. **The conversation is an item list, not a role list.** There is no
 *    `messages` key: `input` is a flat array where a tool call is its own
 *    `function_call` item and its result is a separate
 *    `function_call_output` item. The "roles must alternate" rule that
 *    constrains Anthropic and Gemini does not exist here.
 * 2. **Reasoning is a first-class stream.** Reasoning arrives as
 *    `response.reasoning_summary_text.delta`, not as a delta field on the
 *    message, and its token count lives in
 *    `usage.output_tokens_details.reasoning_tokens`.
 * 3. **Terminal events are typed, not a finish_reason string.** A stream ends
 *    on `response.completed`, `response.incomplete` or `response.failed`, and a
 *    truncation is `incomplete_details.reason === "max_output_tokens"`.
 *
 * Six catalog providers are routed here rather than to Chat Completions:
 * `openai`, `meta`, `perplexity-agent`, `infer`, `neosmith` and `vivgrid`.
 *
 * ## Reasoning is not replayed
 *
 * `store: false` is sent so nothing is retained server-side. The consequence is
 * that a reasoning item can only be continued by echoing its
 * `encrypted_content`, which would need a place to live between requests.
 * [LlmToolCall] carries Gemini's `thoughtSignature` for exactly this reason and
 * has no equivalent here, so reasoning items are dropped on the way back rather
 * than sent incomplete — which is also what OpenCode does when the encrypted
 * payload is absent.
 */
@Singleton
class OpenAiResponsesAdapter @Inject constructor(
    @AgentHttpClient private val httpClient: OkHttpClient,
    private val json: Json,
    private val efforts: EffortMapper,
) : ChatAdapter {

    override val kind: ProviderKind = ProviderKind.OPENAI_RESPONSES

    override fun stream(
        provider: LlmProvider,
        request: LlmRequest,
        credential: LlmCredential,
    ): Flow<LlmStreamEvent> = flow {
        val call = httpClient.newCall(buildHttpRequest(provider, request, credential))
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
        var sawComplete = false

        /**
         * `item_id` → the integer key [ToolCallBuffer] is addressed by.
         *
         * Responses keys a streaming tool call by a string `item_id` while the
         * buffer is keyed by the content-block integer Chat Completions uses.
         * Mapping one onto the other here keeps the argument reassembly — the
         * part that actually has quirks — in one place.
         */
        val itemIndices = LinkedHashMap<String, Int>()

        suspend fun handleFrame(payload: String) {
            parseFrame(payload).forEach { frame ->
                when (frame) {
                    is Frame.Text -> emit(LlmStreamEvent.TextDelta(frame.delta))

                    is Frame.Reasoning -> {
                        reasoning.append(frame.delta)
                        emit(LlmStreamEvent.ReasoningDelta(frame.delta))
                    }

                    is Frame.ToolStart -> {
                        // Registered before the append so a fragment arriving for an
                        // item that was never opened still lands somewhere stable.
                        val index = itemIndices.getOrPut(frame.itemId) { itemIndices.size }
                        val appended = toolCalls.append(
                            index = index,
                            id = frame.callId,
                            name = frame.name,
                            argsDelta = null,
                        )
                        if (appended != null && appended.id != null && appended.name != null) {
                            val id = appended.id
                            val name = appended.name
                            if (id !in announcedCalls) {
                                announcedCalls += id
                                emit(LlmStreamEvent.ToolCallStarted(id, name))
                            }
                        }
                    }

                    is Frame.ToolArgs -> {
                        val index = itemIndices.getOrPut(frame.itemId) { itemIndices.size }
                        toolCalls.append(
                            index = index,
                            id = null,
                            name = null,
                            argsDelta = frame.delta,
                        )
                    }

                    is Frame.ToolDone -> {
                        val index = itemIndices[frame.itemId] ?: return
                        toolCalls.finish(index)?.let { resolved ->
                            if (resolved.id !in announcedCalls) {
                                emit(LlmStreamEvent.ToolCallStarted(resolved.id, resolved.name))
                            }
                            emit(
                                LlmStreamEvent.ToolCallCompleted(
                                    callId = resolved.id,
                                    name = resolved.name,
                                    arguments = resolved.arguments,
                                ),
                            )
                        }
                    }

                    is Frame.Finish -> {
                        finishReason = frame.reason
                        sawComplete = true
                        frame.usage?.let { usage = it }
                    }

                    is Frame.Usage -> usage = frame.usage
                    is Frame.Failure -> providerError = frame.message
                    Frame.Ignore -> Unit
                }
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

        toolCalls.finishAll().forEach { resolved ->
            if (resolved.id !in announcedCalls) {
                emit(LlmStreamEvent.ToolCallStarted(resolved.id, resolved.name))
            }
            emit(LlmStreamEvent.ToolCallCompleted(resolved.id, resolved.name, resolved.arguments))
        }

        if (!sawComplete) {
            emit(
                LlmStreamEvent.Failed(
                    message = "Stream ended without a terminal response event",
                    retryable = true,
                ),
            )
            return
        }

        emit(LlmStreamEvent.Finished(finishReason, usage))
    }

    // ── frame parsing ─────────────────────────────────────────────────────

    internal sealed interface Frame {
        data object Ignore : Frame
        data class Text(val delta: String) : Frame
        data class Reasoning(val delta: String) : Frame
        data class ToolStart(val itemId: String, val callId: String?, val name: String?) : Frame
        data class ToolArgs(val itemId: String, val delta: String) : Frame
        data class ToolDone(val itemId: String) : Frame
        data class Finish(val reason: FinishReason, val usage: TokenUsage? = null) : Frame
        data class Usage(val usage: TokenUsage) : Frame
        data class Failure(val message: String) : Frame
    }

    /**
     * Every frame an event carries.
     *
     * A list for the same reason as the other two adapters' parsers: an event can
     * carry a delta and a terminal signal together, and dropping one of them is
     * how token counts go missing. Uniform with them so the pump code reads the
     * same in all three.
     */
    internal fun parseFrame(payload: String): List<Frame> {
        val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
            ?: return listOf(Frame.Ignore)

        val type = root.stringField("type") ?: return listOf(Frame.Ignore)

        // A `response.failed` and an `error` event both carry the same nested
        // shape, and both mean the turn is over with nothing usable.
        if (type == "response.failed" || type == "error") {
            val nested = (root["response"] as? JsonObject)?.get("error") as? JsonObject
            return listOf(
                Frame.Failure(
                    root.stringField("message")
                        ?: nested?.stringField("message")
                        ?: "Provider reported an error mid-stream",
                ),
            )
        }

        return when (type) {
            "response.output_text.delta" ->
                root.stringField("delta")?.let { listOf(Frame.Text(it)) } ?: listOf(Frame.Ignore)

            "response.reasoning_text.delta",
            "response.reasoning_summary.delta",
            "response.reasoning_summary_text.delta",
            -> root.stringField("delta")?.let { listOf(Frame.Reasoning(it)) } ?: listOf(Frame.Ignore)

            "response.output_item.added" -> {
                val item = root["item"] as? JsonObject ?: return listOf(Frame.Ignore)
                // `call_id` is the model-visible id; `item_id` is the stream key
                // that the argument deltas are addressed by. They are different
                // and both are needed.
                if (item.stringField("type") != "function_call") {
                    listOf(Frame.Ignore)
                } else {
                    listOf(
                        Frame.ToolStart(
                            itemId = item.stringField("id").orEmpty(),
                            callId = item.stringField("call_id") ?: item.stringField("id"),
                            name = item.stringField("name"),
                        ),
                    )
                }
            }

            "response.function_call_arguments.delta" -> {
                val itemId = root.stringField("item_id") ?: return listOf(Frame.Ignore)
                root.stringField("delta")?.let { listOf(Frame.ToolArgs(itemId, it)) } ?: listOf(Frame.Ignore)
            }

            "response.output_item.done" -> {
                val item = root["item"] as? JsonObject ?: return listOf(Frame.Ignore)
                val itemId = item.stringField("id")
                if (item.stringField("type") == "function_call" && itemId != null) {
                    // Arguments can arrive whole on `output_item.done` rather than
                    // as deltas, so the buffer is topped up here rather than
                    // assuming the deltas carried everything.
                    val delta = item.stringField("arguments")
                    if (!delta.isNullOrEmpty()) {
                        listOf(Frame.ToolArgs(itemId, delta))
                    } else {
                        listOf(Frame.ToolDone(itemId))
                    }
                } else {
                    listOf(Frame.Ignore)
                }
            }

            // Usage rides along with the terminal event rather than arriving as
            // an event of its own, so the finish frame carries both.
            "response.completed", "response.incomplete" -> {
                val response = root["response"] as? JsonObject
                listOf(
                    Frame.Finish(
                        reason = mapFinishReason(response),
                        usage = (response?.get("usage") as? JsonObject)?.let { parseUsage(it) },
                    ),
                )
            }

            else -> listOf(Frame.Ignore)
        }
    }

    /**
     * `incomplete_details.reason` distinguishes a truncation from a clean stop;
     * `finish_reason` does not exist in this protocol.
     */
    private fun mapFinishReason(response: JsonObject?): FinishReason {
        val reason = (response?.get("incomplete_details") as? JsonObject)?.stringField("reason")
        return when (reason) {
            null -> FinishReason.STOP
            "max_output_tokens" -> FinishReason.MAX_TOKENS
            "content_filter" -> FinishReason.ERROR
            else -> FinishReason.OTHER
        }
    }

    /**
     * `input_tokens` is inclusive with a `cached_tokens` subset, and
     * `output_tokens` with a `reasoning_tokens` subset. Both are passed through
     * as reported rather than derived.
     */
    private fun parseUsage(raw: JsonObject): TokenUsage = TokenUsage(
        input = raw.intFieldOrNull("input_tokens"),
        output = raw.intFieldOrNull("output_tokens"),
        cacheRead = (raw["input_tokens_details"] as? JsonObject)?.intFieldOrNull("cached_tokens"),
        reasoning = (raw["output_tokens_details"] as? JsonObject)?.intFieldOrNull("reasoning_tokens"),
    )

    // ── request building ──────────────────────────────────────────────────

    internal fun buildBody(provider: LlmProvider, request: LlmRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", true)

        // Nothing is kept server-side, which is also why reasoning items are
        // dropped on the way back rather than replayed half-formed.
        put("store", false)

        request.maxOutputTokens?.let { put("max_output_tokens", it) }
        request.temperature?.let { put("temperature", it) }

        // A top-level field here, not a system-role input item.
        request.systemPrompt?.takeIf { it.isNotBlank() }?.let { system ->
            put("instructions", system)
        }

        put("input", buildJsonArray { encodeItems(request).forEach { add(it) } })

        if (request.tools.isNotEmpty()) {
            put(
                "tools",
                buildJsonArray {
                    request.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters)
                            put("strict", false)
                        })
                    }
                },
            )
            put("tool_choice", "auto")
        }

        request.reasoningEffort?.takeIf { it.isNotBlank() }?.let { effort ->
            efforts.bodyFields(
                provider = provider,
                modelId = request.model,
                effort = effort,
                outputTokenLimit = request.maxOutputTokens,
            ).forEach { (key, value) -> put(key, value) }
        }
    }

    /**
     * The conversation as a flat item list.
     *
     * No merging of consecutive items is needed: unlike `messages` in Chat
     * Completions, `input` is a sequence of typed items and a `function_call`
     * followed by a `function_call_output` is the documented shape.
     */
    private fun encodeItems(request: LlmRequest): List<JsonObject> {
        val items = mutableListOf<JsonObject>()

        for (message in request.messages) {
            when (message) {
                is LlmMessage.User -> items += buildJsonObject {
                    put("role", "user")
                    put(
                        "content",
                        buildJsonArray {
                            add(buildJsonObject {
                                put("type", "input_text")
                                put("text", message.text)
                            })
                        },
                    )
                }

                is LlmMessage.Assistant -> {
                    message.text.takeIf { it.isNotEmpty() }?.let { text ->
                        items += buildJsonObject {
                            put("role", "assistant")
                            put(
                                "content",
                                buildJsonArray {
                                    add(buildJsonObject {
                                        put("type", "output_text")
                                        put("text", text)
                                    })
                                },
                            )
                        }
                    }
                    message.toolCalls.forEach { toolCall ->
                        items += buildJsonObject {
                            put("type", "function_call")
                            put("call_id", toolCall.id)
                            put("name", toolCall.name)
                            // A JSON string, not the object: Chat Completions and
                            // Anthropic both take it the other way.
                            put("arguments", toolCall.arguments.toString())
                        }
                    }
                }

                is LlmMessage.ToolResultMessage -> items += buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", message.callId)
                    put("output", message.content)
                }
            }
        }

        return items
    }

    internal fun buildHttpRequest(
        provider: LlmProvider,
        request: LlmRequest,
        credential: LlmCredential,
    ): Request {
        val url = "${provider.baseUrl.trimEnd('/')}$RESPONSES_PATH"

        return Request.Builder()
            .url(url)
            .post(buildBody(provider, request).toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer ${credential.apiKey}")
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
        const val RESPONSES_PATH = "/responses"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
