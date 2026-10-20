package dev.drosh.agent.stream

import dev.drosh.domain.agent.LlmToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Reassembles streamed tool-call fragments into whole calls.
 *
 * ## Why this is not in `:domain`
 *
 * The stream key is a protocol detail. OpenAI Chat keys a streaming tool call by
 * a numeric `index` in `choices[].delta.tool_calls[]`; OpenAI Responses uses a
 * string `item_id`; Anthropic uses a content-block index. That key exists only
 * while a call is being assembled and it is not the call's identity, so it stays
 * inside this class. What leaves is a list of [LlmToolCall] carrying the
 * provider's real call id — the only thing the loop and a tool correlate on.
 *
 * ## Shape of an OpenAI Chat tool-call stream
 *
 * A call arrives as several fragments sharing one `index`:
 *
 * ```json
 * {"index":0,"id":"call_a","function":{"name":"shell","arguments":""}}
 * {"index":0,"function":{"arguments":"{\"comm"}}
 * {"index":0,"function":{"arguments":"and\":\"ls\"}"}}
 * ```
 *
 * `id` and `name` appear only on the first fragment for an index. Arguments are
 * raw JSON split at arbitrary byte boundaries — often mid-token, sometimes
 * mid-escape — so they are concatenated as text and parsed once, at the end.
 */
internal class ToolCallBuffer(private val json: Json) {

    /** Outcome of [append]; null when the fragment was unusable. */
    data class Appended(
        val id: String?,
        val name: String?,
        /** True the first time this index is seen, i.e. a genuinely new call. */
        val isNew: Boolean,
    )

    private data class Pending(
        val id: String?,
        val name: String?,
        val args: StringBuilder,
    )

    private val pending = LinkedHashMap<Int, Pending>()
    private var syntheticCounter = 0

    val pendingCount: Int get() = pending.size

    /**
     * Append one fragment.
     *
     * @return what was resolved for this index, or null when the fragment
     *         carried neither an id nor a name — such a fragment cannot be
     *         attributed to a call and is dropped rather than guessed at
     */
    fun append(index: Int, id: String?, name: String?, argsDelta: String?): Appended? {
        val existing = pending[index]
        val resolvedId = id ?: existing?.id
        val resolvedName = name ?: existing?.name
        if (resolvedId == null && resolvedName == null) return null

        val isNew = existing == null
        pending[index] = Pending(
            id = resolvedId,
            name = resolvedName,
            args = (existing?.args ?: StringBuilder()).also { builder ->
                if (!argsDelta.isNullOrEmpty()) builder.append(argsDelta)
            },
        )
        return Appended(resolvedId, resolvedName, isNew)
    }

    /**
     * Finish every pending call.
     *
     * OpenAI Chat emits no per-call stop event — all accumulated calls complete
     * when the choice receives a terminal `finish_reason` — so this is called
     * once at the end of the stream.
     */
    fun finishAll(): List<LlmToolCall> {
        val calls = pending.entries.map { (index, p) -> toCall(index, p) }
        pending.clear()
        return calls
    }

    /**
     * Finish one call, by its protocol-local key.
     *
     * Anthropic closes each content block with its own `content_block_stop`,
     * so a call is complete the moment its arguments stop arriving rather than
     * when the message ends. Emitting it early is what lets a long write_file
     * argument stream show up in the transcript while the model is still
     * finishing the turn.
     *
     * @return the resolved call, or null when the key was already finished or
     *         never seen
     */
    fun finish(index: Int): LlmToolCall? {
        val p = pending.remove(index) ?: return null
        return toCall(index, p)
    }

    private fun toCall(index: Int, p: Pending): LlmToolCall = LlmToolCall(
        // A call that never got an id still needs a stable handle so its result
        // can be correlated back. Only visible to this run.
        id = p.id ?: "call_synthetic_${syntheticCounter++}_$index",
        name = p.name.orEmpty(),
        arguments = parseArguments(p.args.toString()),
    )

    /**
     * Parse accumulated argument text.
     *
     * Malformed arguments must not kill the run. Returning an empty object lets
     * the tool receive its call, find the arguments missing, and return a
     * `ToolResult.Error` naming what it wanted — which the model can act on.
     * Throwing here would abort the loop on a provider's typo.
     */
    private fun parseArguments(raw: String): JsonObject {
        val text = raw.trim()
        if (text.isEmpty()) return JsonObject(emptyMap())
        return runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }
    }
}

/** Read a string field, or null when absent or not a string. */
internal fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Read an integer field, or null when absent or not numeric. */
internal fun JsonObject.intFieldOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

/**
 * Hidden-reasoning text, across the spellings providers actually use.
 *
 * OpenAI Chat sends `reasoning_content`; DeepSeek, Qwen and several OpenAI-
 * compatible gateways send `reasoning`; some send neither. Each spelling costs
 * a key in this list rather than three near-identical blocks at the call site.
 */
internal fun JsonObject.reasoningText(): String? = sequenceOf("reasoning_content", "reasoning")
    .mapNotNull { key -> stringField(key) }
    .firstOrNull { it.isNotEmpty() }
