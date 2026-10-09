package dev.drosh.agent.provider

import dev.drosh.agent.di.AgentNetworkModule.AgentHttpClient
import dev.drosh.agent.stream.SseFrameReader
import dev.drosh.agent.stream.intFieldOrNull
import dev.drosh.agent.stream.stringField
import dev.drosh.domain.agent.ChatAdapter
import dev.drosh.domain.agent.FinishReason
import dev.drosh.domain.agent.LlmCredential
import dev.drosh.domain.agent.LlmMessage
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.LlmRequest
import dev.drosh.domain.agent.LlmStreamEvent
import dev.drosh.domain.agent.LlmToolCall
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
 * Google's `generateContent` API, streamed.
 *
 * Inspired by: github.com/anomalyco/opencode — packages/llm/src/protocols/gemini.ts
 * Adapted for Drosh — dev.drosh
 *
 * ## The model id is part of the path
 *
 * The endpoint is `models/{id}:streamGenerateContent?alt=sse` — not a fixed
 * path with the model in the body. That is also why this adapter cannot share
 * `OpenAiCompatAdapter`'s URL derivation: there is no `/chat/completions` to
 * append.
 *
 * ## Reasoning arrives as ordinary text with a flag
 *
 * Gemini has no separate reasoning channel. A part with `thought: true` is
 * reasoning and a part without it is the answer, in the same `parts` array.
 * Drosh's [ToolCallBuffer] is not involved: `functionCall` parts arrive whole,
 * with complete arguments, unlike Chat Completions' fragmented stream.
 *
 * ## Thought signatures
 *
 * `generateContent` has no dedicated thought blocks, so Gemini attaches a
 * signature to parts — most importantly to `functionCall`. The signature must
 * be echoed back on the following request or Gemini 3 refuses it, which is why
 * [LlmToolCall.thoughtSignature] exists. It is carried in memory for the
 * duration of a run; it does not survive a restart, because
 * `assembleModelHistory` does not restore tool calls at all (see
 * `TranscriptStore.kt`), which is a pre-existing gap affecting every protocol.
 */
@Singleton
class GeminiAdapter @Inject constructor(
    @AgentHttpClient private val httpClient: OkHttpClient,
    private val json: Json,
    private val efforts: EffortMapper,
) : ChatAdapter {

    override val kind: ProviderKind = ProviderKind.GEMINI

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
        val reasoning = StringBuilder()

        var finishReason: FinishReason? = null
        var usage: TokenUsage? = null
        var providerError: String? = null

        // Gemini sends no call id, so one is minted here. It only has to be
        // stable within this run — the model never sees it, and a tool result
        // correlates back through the `functionResponse.name`.
        var nextCallId = 0

        suspend fun handleFrame(payload: String) {
            when (val frame = parseFrame(payload)) {
                is Frame.Text -> emit(LlmStreamEvent.TextDelta(frame.delta))

                is Frame.Reasoning -> {
                    reasoning.append(frame.delta)
                    emit(LlmStreamEvent.ReasoningDelta(frame.delta))
                }

                is Frame.ToolCall -> {
                    val toolCall = LlmToolCall(
                        id = "call_${nextCallId++}",
                        name = frame.name,
                        arguments = frame.arguments,
                        thoughtSignature = frame.thoughtSignature,
                    )
                    // Gemini delivers arguments whole, so a call is announced and
                    // completed in the same step rather than across fragments.
                    emit(LlmStreamEvent.ToolCallStarted(toolCall.id, toolCall.name))
                    emit(
                        LlmStreamEvent.ToolCallCompleted(
                            callId = toolCall.id,
                            name = toolCall.name,
                            arguments = toolCall.arguments,
                        ),
                    )
                }

                // Cumulative on every chunk, so the last value wins rather than
                // being summed — Anthropic's per-event deltas are the opposite.
                is Frame.Usage -> usage = frame.usage

                // The terminal chunk carries both the finish reason and the final
                // usage counts, so they arrive together rather than needing to be
                // reconciled across two frames.
                is Frame.Finish -> {
                    finishReason = frame.reason
                    frame.usage?.let { usage = it }
                }

                is Frame.Failure -> providerError = frame.message
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

        if (finishReason == null) {
            emit(
                LlmStreamEvent.Failed(
                    message = "Stream ended without a finish reason",
                    retryable = true,
                ),
            )
            return
        }

        emit(LlmStreamEvent.Finished(finishReason ?: FinishReason.OTHER, usage))
    }

    // ── frame parsing ─────────────────────────────────────────────────────

    internal sealed interface Frame {
        data object Ignore : Frame
        data class Text(val delta: String) : Frame
        data class Reasoning(val delta: String) : Frame
        data class ToolCall(
            val name: String,
            val arguments: JsonObject,
            val thoughtSignature: String?,
        ) : Frame

        data class Usage(val usage: TokenUsage) : Frame

        /** Terminal chunk: the finish reason, with the final counts if present. */
        data class Finish(val reason: FinishReason, val usage: TokenUsage? = null) : Frame

        data class Failure(val message: String) : Frame
    }

    internal fun parseFrame(payload: String): Frame {
        val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
            ?: return Frame.Ignore

        (root["error"] as? JsonObject)?.let { error ->
            return Frame.Failure(error.stringField("message") ?: "Provider reported an error mid-stream")
        }

        // Read first but not returned: the same chunk usually carries content
        // too, and the terminal chunk carries this alongside finishReason.
        val usage = (root["usageMetadata"] as? JsonObject)?.let { parseUsage(it) }

        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject

        candidate?.get("content")?.let { raw ->
            val parts = (raw as? JsonObject)?.get("parts") as? JsonArray
            if (parts != null) {
                for (part in parts) {
                    val obj = part as? JsonObject ?: continue
                    val isThought = obj["thought"]?.let { it as? JsonPrimitive }?.content == "true"

                    (obj["functionCall"] as? JsonObject)?.let { fn ->
                        return Frame.ToolCall(
                            name = fn.stringField("name").orEmpty(),
                            arguments = fn["args"] as? JsonObject ?: JsonObject(emptyMap()),
                            thoughtSignature = obj.stringField("thoughtSignature"),
                        )
                    }

                    obj.stringField("text")?.takeIf { it.isNotEmpty() }?.let { text ->
                        return if (isThought) Frame.Reasoning(text) else Frame.Text(text)
                    }
                }
            }
        }

        // The finish reason is the signal the stream is over, so it wins over a
        // usage-only chunk — and it carries the counts with it when present.
        candidate?.stringField("finishReason")?.let { raw ->
            return Frame.Finish(mapFinishReason(raw), usage)
        }

        return usage?.let { Frame.Usage(it) } ?: Frame.Ignore
    }

    /**
     * `candidatesTokenCount` is visible-only and excludes
     * `thoughtsTokenCount`, so the two are summed for the output figure. Summing
     * them when the visible component is missing would fabricate an inclusive
     * number from half a breakdown.
     */
    private fun parseUsage(raw: JsonObject): TokenUsage {
        val candidates = raw.intFieldOrNull("candidatesTokenCount")
        return TokenUsage(
            input = raw.intFieldOrNull("promptTokenCount"),
            output = candidates?.let { it + (raw.intFieldOrNull("thoughtsTokenCount") ?: 0) },
            cacheRead = raw.intFieldOrNull("cachedContentTokenCount"),
            reasoning = raw.intFieldOrNull("thoughtsTokenCount"),
        )
    }

    private fun mapFinishReason(raw: String): FinishReason = when (raw) {
        "STOP" -> FinishReason.STOP
        "MAX_TOKENS" -> FinishReason.MAX_TOKENS
        "MALFORMED_FUNCTION_CALL" -> FinishReason.ERROR
        // Safety, recitation and blocklist blocks all mean the turn ended
        // without a usable answer, which is closer to an error than a stop.
        "IMAGE_SAFETY", "RECITATION", "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> FinishReason.ERROR
        else -> FinishReason.OTHER
    }

    // ── request building ──────────────────────────────────────────────────

    internal fun buildBody(provider: LlmProvider, request: LlmRequest): JsonObject = buildJsonObject {
        put("contents", encodeMessages(request.messages))

        request.systemPrompt?.takeIf { it.isNotBlank() }?.let { system ->
            // `systemInstruction`, not a `system` role in `contents`.
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", system) })
                })
            })
        }

        if (request.tools.isNotEmpty()) {
            put(
                "tools",
                buildJsonArray {
                    add(buildJsonObject {
                        put(
                            "functionDeclarations",
                            buildJsonArray { request.tools.forEach { add(encodeTool(it)) } },
                        )
                    })
                },
            )
        }

        val generationConfig = buildJsonObject {
            request.maxOutputTokens?.let { put("maxOutputTokens", it) }
            request.temperature?.let { put("temperature", it) }

            request.reasoningEffort?.takeIf { it.isNotBlank() }?.let { effort ->
                // Nested inside generationConfig rather than at the top level,
                // which is why EffortMapper exposes a separate accessor for this
                // protocol.
                efforts.geminiThinkingConfig(request.model, effort)?.let { put("thinkingConfig", it) }
            }
        }
        if (generationConfig.isNotEmpty()) put("generationConfig", generationConfig)
    }

    private fun encodeMessages(messages: List<LlmMessage>): JsonArray {
        val encoded = mutableListOf<JsonObject>()

        for (message in messages) {
            when (message) {
                is LlmMessage.User -> encoded += buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray { add(textPart(message.text)) })
                }

                is LlmMessage.Assistant -> {
                    val parts = buildJsonArray {
                        message.text.takeIf { it.isNotEmpty() }?.let { add(textPart(it)) }
                        message.toolCalls.forEach { toolCall ->
                            add(buildJsonObject {
                                put(
                                    "functionCall",
                                    buildJsonObject {
                                        put("name", toolCall.name)
                                        put("args", toolCall.arguments)
                                    },
                                )
                                // Required back on Gemini 3, and ignored elsewhere.
                                toolCall.thoughtSignature?.let { put("thoughtSignature", it) }
                            })
                        }
                    }
                    if (parts.isNotEmpty()) {
                        encoded += buildJsonObject {
                            put("role", "model")
                            put("parts", parts)
                        }
                    }
                }

                is LlmMessage.ToolResultMessage -> {
                    // `functionResponse` names the tool and carries the result
                    // nested under `response`, which is where Gemini looks for it.
                    val part = buildJsonObject {
                        put(
                            "functionResponse",
                            buildJsonObject {
                                put("name", message.name)
                                put(
                                    "response",
                                    buildJsonObject {
                                        put("name", message.name)
                                        put("content", message.content)
                                    },
                                )
                            },
                        )
                    }
                    val previous = encoded.lastOrNull()
                    val previousParts = previous?.get("parts") as? JsonArray
                    if (previous != null && previous.stringField("role") == "user" && previousParts != null) {
                        encoded[encoded.lastIndex] = buildJsonObject {
                            put("role", "user")
                            put("parts", buildJsonArray {
                                previousParts.forEach { add(it) }
                                add(part)
                            })
                        }
                    } else {
                        encoded += buildJsonObject {
                            put("role", "user")
                            put("parts", buildJsonArray { add(part) })
                        }
                    }
                }
            }
        }

        return buildJsonArray { encoded.forEach { add(it) } }
    }

    private fun textPart(text: String): JsonObject = buildJsonObject { put("text", text) }

    private fun encodeTool(tool: ToolDefinition): JsonObject = buildJsonObject {
        put("name", tool.name)
        put("description", tool.description)
        // `parameters`, and it is the schema object rather than a JSON string.
        put("parameters", tool.parameters)
    }

    /**
     * `models/{id}:streamGenerateContent?alt=sse`.
     *
     * `alt=sse` is what selects server-sent events; without it the endpoint
     * returns one buffered JSON document and the stream parser sees a single
     * frame with no `[DONE]`.
     */
    internal fun buildHttpRequest(
        provider: LlmProvider,
        request: LlmRequest,
        credential: LlmCredential,
    ): Request {
        val base = provider.baseUrl.trimEnd('/').toHttpUrl()

        // `encodedPath` rather than `addPathSegment`: the segment contains a
        // colon (`id:streamGenerateContent`), which is valid in a path but which
        // the segment encoder would percent-escape into something Google rejects.
        val url = base.newBuilder()
            .encodedPath("${base.encodedPath}/models/${request.model}:streamGenerateContent")
            .setQueryParameter("alt", "sse")
            .apply { provider.extraQuery.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()

        return Request.Builder()
            .url(url)
            .post(buildBody(provider, request).toString().toRequestBody(JSON_MEDIA_TYPE))
            // `x-goog-api-key`; a Bearer token is not accepted here.
            .header("x-goog-api-key", credential.apiKey)
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
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
