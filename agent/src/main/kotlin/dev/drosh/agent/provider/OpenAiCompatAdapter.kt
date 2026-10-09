package dev.drosh.agent.provider

import dev.drosh.agent.stream.SseFrameReader
import dev.drosh.agent.stream.ToolCallBuffer
import dev.drosh.agent.stream.intFieldOrNull
import dev.drosh.agent.stream.reasoningText
import dev.drosh.agent.stream.stringField
import dev.drosh.agent.di.AgentNetworkModule.AgentHttpClient
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI Chat Completions, as streamed by OpenRouter, OpenAI and other
 * OpenAI-compatible gateways.
 *
 * One class serves every vendor speaking this protocol. Adding OpenRouter or
 * Groq is a row in the provider catalog, not another adapter — which is why
 * [ChatAdapter] takes the [LlmProvider] per call instead of holding one.
 *
 * ## Reading the body rather than using `EventSource`
 *
 * The stream is read straight off the response body line by line instead of
 * through OkHttp's `EventSource`. `EventSource` is a callback API, so using it
 * from a `Flow` means bridging callbacks into a coroutine and hides the framing
 * behind types that are awkward to build in a test. The framing actually needed
 * is twenty lines, and [SseFrameReader] plus [parseFrame] are driven from
 * literal wire text in unit tests.
 *
 * ## Blocking reads
 *
 * `Call.execute()` and the body reads block, so the entire upstream moves to
 * [Dispatchers.IO] via `flowOn`. Cancellation is checked once per line and the
 * call is cancelled on the way out, which closes the socket instead of leaving
 * it to the connection pool.
 *
 * ## The client this is given
 *
 * Do not pass it the app-wide `NetworkModule` client. That one is configured for
 * decorative background lookups — 5s read timeout, 10s call timeout — which
 * would sever a stream every time the model pauses to think. It also sets
 * `retryOnConnectionFailure(true)`, and OkHttp resending a POST means paying
 * twice for the same completion. See `AgentNetworkModule`.
 */
@Singleton
class OpenAiCompatAdapter @Inject constructor(
    @AgentHttpClient private val httpClient: OkHttpClient,
    private val json: Json,
    private val efforts: EffortMapper,
) : ChatAdapter {

    override val kind: ProviderKind = ProviderKind.OPENAI_COMPAT

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
            // Cancellation is control flow, not a provider failure. Rethrowing
            // stops the collector; turning it into a Failed event would make
            // every caller special-case a stop.
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
     * so that every piece of per-stream mutable state is plainly scoped to one
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
        var sawDone = false

        // ── per-frame handling ──
        suspend fun handleFrame(payload: String) {
            when (val frame = parseFrame(payload)) {
                is Frame.Text -> emit(LlmStreamEvent.TextDelta(frame.delta))

                is Frame.Reasoning -> {
                    reasoning.append(frame.delta)
                    emit(LlmStreamEvent.ReasoningDelta(frame.delta))
                }

                is Frame.ToolFragment -> {
                    val appended = toolCalls.append(
                        index = frame.index,
                        id = frame.id,
                        name = frame.name,
                        argsDelta = frame.argsDelta,
                    ) ?: return

                    // Announce as soon as the call is identifiable rather than
                    // after its arguments finish streaming — a write_file call
                    // can carry kilobytes of file content.
                    val id = appended.id
                    val name = appended.name
                    if (id != null && name != null && appended.isNew && id !in announcedCalls) {
                        announcedCalls += id
                        emit(LlmStreamEvent.ToolCallStarted(id, name))
                    }
                }

                is Frame.Finish -> finishReason = frame.reason
                is Frame.Usage -> usage = frame.usage
                is Frame.Failure -> providerError = frame.message
                Frame.Ignore -> Unit
            }
        }

        try {
            body.source().use { source ->
                while (true) {
                    // Per line, so a stop takes effect within one line of the socket
                    // instead of whenever the stream happens to end.
                    currentCoroutineContext().ensureActive()

                    val line = source.readUtf8Line()
                    if (line == null) {
                        // A proxy can close mid-frame; without this the last tool
                        // call is silently lost and it looks like the model forgot
                        // what it was doing.
                        frames.flush()?.let { payload ->
                            if (payload == DONE_MARKER) sawDone = true else handleFrame(payload)
                        }
                        break
                    }

                    val payload = frames.accept(line) ?: continue
                    if (payload == DONE_MARKER) {
                        sawDone = true
                        break
                    }
                    handleFrame(payload)
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

        val resolvedCalls = toolCalls.finishAll()
        resolvedCalls.forEach { resolved ->
            // Announce late only if the call never became identifiable mid-stream.
            if (resolved.id !in announcedCalls) {
                emit(LlmStreamEvent.ToolCallStarted(resolved.id, resolved.name))
            }
            emit(LlmStreamEvent.ToolCallCompleted(resolved.id, resolved.name, resolved.arguments))
        }

        if (!sawDone && finishReason == FinishReason.OTHER) {
            emit(
                LlmStreamEvent.Failed(
                    message = "Stream ended without a finish reason or a [DONE] marker",
                    retryable = true,
                ),
            )
            return
        }

        val reason = if (resolvedCalls.isNotEmpty()) FinishReason.TOOL_CALLS else finishReason
        emit(LlmStreamEvent.Finished(reason, usage))

    }

    // ── frame parsing ─────────────────────────────────────────────────────

    /** One parsed SSE payload. Every branch carries what the caller needs. */
    internal sealed interface Frame {
        data object Ignore : Frame
        data class Text(val delta: String) : Frame
        data class Reasoning(val delta: String) : Frame
        data class ToolFragment(
            val index: Int,
            val id: String?,
            val name: String?,
            val argsDelta: String?,
        ) : Frame

        data class Finish(val reason: FinishReason) : Frame
        data class Usage(val usage: TokenUsage) : Frame
        data class Failure(val message: String) : Frame
    }

    internal fun parseFrame(payload: String): Frame {
        val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
            ?: return Frame.Ignore

        // A 200 response can still carry an error object part-way through.
        (root["error"] as? JsonObject)?.let { error ->
            return Frame.Failure(
                error.stringField("message") ?: "Provider reported an error mid-stream",
            )
        }

        val choices = root["choices"] as? JsonArray
        if (choices == null) return Frame.Ignore

        // Usage arrives in a trailing chunk after the last choice, as
        // `"choices": []`. It is opt-in via stream_options.include_usage.
        if (choices.isEmpty()) {
            val usage = root["usage"] as? JsonObject ?: return Frame.Ignore
            return Frame.Usage(
                TokenUsage(
                    input = usage.intFieldOrNull("prompt_tokens"),
                    output = usage.intFieldOrNull("completion_tokens"),
                    cacheRead = usage.intFieldOrNull("cache_read_input_tokens"),
                    reasoning = usage.intFieldOrNull("reasoning_tokens"),
                ),
            )
        }

        val choice = choices.firstOrNull() as? JsonObject
        val delta = choice?.get("delta") as? JsonObject

        if (delta != null) {
            val fragments = delta["tool_calls"] as? JsonArray
            if (fragments != null && fragments.isNotEmpty()) {
                val fragment = fragments.first() as? JsonObject ?: return Frame.Ignore
                val function = fragment["function"] as? JsonObject
                return Frame.ToolFragment(
                    // OpenAI Chat always sends an index; default defensively so a
                    // gateway that omits it still assembles into one call.
                    index = fragment.intFieldOrNull("index") ?: 0,
                    id = fragment.stringField("id"),
                    name = function?.stringField("name"),
                    argsDelta = function?.stringField("arguments"),
                )
            }

            delta.reasoningText()?.let { return Frame.Reasoning(it) }
            delta.stringField("content")?.let { return Frame.Text(it) }
        }

        choice?.get("finish_reason")?.let { raw ->
            val reason = (raw as? JsonPrimitive)?.content
            if (!reason.isNullOrBlank()) return Frame.Finish(mapFinishReason(reason))
        }

        return Frame.Ignore
    }

    private fun mapFinishReason(raw: String): FinishReason = when (raw) {
        "stop" -> FinishReason.STOP
        "length" -> FinishReason.MAX_TOKENS
        "tool_calls", "function_call" -> FinishReason.TOOL_CALLS
        "error" -> FinishReason.ERROR
        else -> FinishReason.OTHER
    }

    // ── request building ──────────────────────────────────────────────────

    /**
     * Build the request body.
     *
     * Internal rather than private so the wire format is asserted directly in
     * tests, which is cheaper and less brittle than standing up a server.
     *
     * Takes [provider] as well as the request because two of the fields it emits
     * are provider policy rather than conversation state: the effort spelling,
     * which differs between OpenRouter and every other gateway, and [LlmProvider.extraBody],
     * which exists for endpoints that reject something this sends unconditionally.
     */
    internal fun buildBody(provider: LlmProvider, request: LlmRequest): JsonObject {
        val built = buildJsonObject {
            put("model", request.model)
            put("stream", true)
            // Without this, streaming responses carry no token counts at all and the
            // UI has nothing to show after a long run.
            put("stream_options", buildJsonObject { put("include_usage", true) })

            request.temperature?.let { put("temperature", it) }
            request.maxOutputTokens?.let { put("max_tokens", it) }

            put("messages", buildJsonArray {
                request.systemPrompt?.takeIf { it.isNotBlank() }?.let { system ->
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", system)
                    })
                }
                request.messages.forEach { message -> add(encodeMessage(message)) }
            })

            if (request.tools.isNotEmpty()) {
                put("tools", buildJsonArray { request.tools.forEach { add(encodeTool(it)) } })
            }

            // Reasoning effort, spelled per protocol. Set before extraBody so a
            // provider that wants it elsewhere can override rather than duplicate.
            request.reasoningEffort?.takeIf { it.isNotBlank() }?.let { effort ->
                efforts.bodyFields(
                    provider = provider,
                    modelId = request.model,
                    effort = effort,
                    outputTokenLimit = request.maxOutputTokens,
                ).forEach { (key, value) -> put(key, value) }
            }

            // Last, and the only thing allowed to override the adapter: a field
            // set to JSON null deletes the key rather than sending null, which is
            // the only way to express "do not send stream_options here".
            applyOverrides(provider.extraBody)
        }
        return built
    }

    /** Merge provider overrides, where an explicit null removes the key. */
    private fun MutableMap<String, JsonElement>.applyOverrides(overrides: JsonObject) {
        if (overrides.isEmpty()) return
        val merged = LinkedHashMap(this)
        overrides.forEach { (key, value) ->
            if (value is JsonNull) merged.remove(key) else merged[key] = value
        }
        clear()
        putAll(merged)
    }

    private fun encodeMessage(message: LlmMessage): JsonObject = when (message) {
        is LlmMessage.User -> buildJsonObject {
            put("role", "user")
            put("content", message.text)
        }

        is LlmMessage.Assistant -> buildJsonObject {
            put("role", "assistant")
            // An assistant turn that only called tools carries no text; the key
            // must still be present, as null.
            put("content", message.text.takeIf { it.isNotEmpty() })
            if (message.toolCalls.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    message.toolCalls.forEach { toolCall ->
                        add(buildJsonObject {
                            put("id", toolCall.id)
                            put("type", "function")
                            put(
                                "function",
                                buildJsonObject {
                                    put("name", toolCall.name)
                                    put("arguments", toolCall.arguments.toString())
                                },
                            )
                        })
                    }
                })
            }
        }

        is LlmMessage.ToolResultMessage -> buildJsonObject {
            put("role", "tool")
            put("tool_call_id", message.callId)
            put("content", message.content)
        }
    }

    private fun encodeTool(tool: ToolDefinition): JsonObject = buildJsonObject {
        put("type", "function")
        put(
            "function",
            buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.parameters)
            },
        )
    }

    internal fun buildHttpRequest(
        provider: LlmProvider,
        request: LlmRequest,
        credential: LlmCredential,
    ): Request {
        val url = provider.chatCompletionsUrl.toHttpUrl().newBuilder()
            // Appended rather than baked into baseUrl because they are a property
            // of the request, not of the endpoint — Azure's `api-version` being
            // the one the catalog cannot express.
            .apply { provider.extraQuery.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()

        return Request.Builder()
            .url(url)
            .post(buildBody(provider, request).toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer ${credential.apiKey}")
            .header("Accept", "text/event-stream")
            // OpenRouter attributes traffic by referer and app name. Both are
            // recommended rather than required, so they arrive as provider config
            // instead of being hardcoded here.
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
        const val DONE_MARKER = "[DONE]"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
