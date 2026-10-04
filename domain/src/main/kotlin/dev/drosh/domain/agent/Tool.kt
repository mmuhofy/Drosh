package dev.drosh.domain.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A capability the agent can invoke.
 *
 * Implementations live in `agent/tool/impl/` and may depend on Android or on
 * the terminal module; this interface is pure so the UI and the loop can both
 * reason about tools without pulling any of that in.
 */
interface Tool {

    /** Wire name. Must match the name in [parameters] semantics and be stable. */
    val name: String

    /** Sent to the model verbatim. Say what it does *and* when not to use it. */
    val description: String

    /** JSON Schema for the argument object, `type: "object"`. */
    val parameters: JsonObject

    /**
     * When true the loop parks on `ToolResult.AwaitingApproval` and waits for
     * the user before this tool runs. Use for anything that mutates state the
     * user did not explicitly ask for.
     */
    val requiresApproval: Boolean

    /**
     * One-line summary for the collapsed tool row, e.g. `npm run build` for a
     * shell call or `src/App.ktx` for a file write. The UI shows this instead
     * of the raw argument JSON, so keep it short and human-readable.
     */
    fun summarize(input: JsonObject): String

    /**
     * Run the tool.
     *
     * Cancellation is ordinary coroutine cancellation: [execute] is a suspend
     * function, so implementations call `coroutineContext.ensureActive()` or
     * simply let the exception propagate. There is deliberately no abort-signal
     * parameter — a `Job` is the cancellation primitive in Kotlin, and a
     * suspended call is already cancelled when its caller is.
     *
     * @param input parsed arguments, already validated against [parameters]
     *              by the adapter that produced the call
     * @param context per-call context: which chat, which step, and how to push
     *                incremental output to the UI while the tool is still running
     */
    suspend fun execute(input: JsonObject, context: ToolContext): ToolResult
}

/**
 * Per-invocation context handed to [Tool.execute].
 *
 * @param chatId the agent chat this call belongs to; a tool that needs to touch
 *               a terminal resolves its session through this
 * @param workingDirectory absolute path inside the guest filesystem the agent
 *                         is scoped to; tools must not escape it
 * @param step 1-based loop iteration, for diagnostics and loop detection
 * @param emit push an incremental update to the UI without ending the call.
 *               Suspend because streaming output *is* the asynchronous part —
 *               a tool reporting a line of shell output is doing I/O, and a
 *               plain `(ToolUpdate) -> Unit` would make that impossible to
 *               express honestly.
 * @param awaitApproval suspend until the user answers. A tool that mutates
 *               something the user did not explicitly ask for calls this with
 *               the detail worth showing — a diff, a question, a set of choices —
 *               and proceeds or returns `ToolResult.Cancelled` on the answer.
 */
data class ToolContext(
    val chatId: String,
    val workingDirectory: String,
    val step: Int,
    val emit: suspend (ToolUpdate) -> Unit,
    val awaitApproval: suspend (ApprovalRequest) -> ApprovalDecision,
)

/**
 * What a tool wants the user's consent for.
 *
 * Only [title] is required: an approval card that cannot be rendered into a
 * single readable line is not worth blocking a run over.
 */
data class ApprovalRequest(
    val title: String,
    val body: String? = null,
    /** Unified diff, when the decision is about a file change. */
    val diff: String? = null,
    /** Choices for [ApprovalDecision.Answer]; empty means free text. */
    val options: List<String> = emptyList(),
)

/** Incremental progress from a running tool. Never terminates the call. */
sealed interface ToolUpdate {

    /** One line of streamed output, e.g. a line of shell stdout. */
    data class Output(val line: String) : ToolUpdate

    /** Free-form status text, e.g. "compiling 3/9". */
    data class Progress(val text: String) : ToolUpdate
}

// ── Schema builder ───────────────────────────────────────────────────────
//
// Tools declare their arguments as JSON Schema. Writing that by hand nests four
// levels of buildJsonObject per property, so this collects properties and the
// required set separately and assembles the object once at [build].
//
// The previous implementation put `"required": "a,b"` — a JSON string, not an
// array, which is not valid JSON Schema and is silently dropped by strict
// providers. That is the reason `required` is a real JsonArray here.

class ToolSchemaBuilder {

    private val properties = LinkedHashMap<String, JsonObject>()
    private val required = mutableListOf<String>()

    fun string(
        name: String,
        description: String,
        enum: List<String>? = null,
        required: Boolean = true,
    ) = property(
        name,
        buildJsonObject {
            put("type", "string")
            put("description", description)
            if (enum != null) put("enum", enum.toJsonArray())
        },
        required,
    )

    fun integer(name: String, description: String, required: Boolean = true) = property(
        name,
        buildJsonObject {
            put("type", "integer")
            put("description", description)
        },
        required,
    )

    fun number(name: String, description: String, required: Boolean = true) = property(
        name,
        buildJsonObject {
            put("type", "number")
            put("description", description)
        },
        required,
    )

    fun bool(name: String, description: String, required: Boolean = true) = property(
        name,
        buildJsonObject {
            put("type", "boolean")
            put("description", description)
        },
        required,
    )

    /**
     * Escape hatch for a nested object or array schema.
     *
     * Takes a description because a raw schema has no way to carry one — without
     * it the model gets a property with no explanation of what it is.
     */
    fun raw(
        name: String,
        schema: JsonObject,
        description: String = "",
        required: Boolean = true,
    ) = property(
        name,
        if (description.isBlank()) {
            schema
        } else {
            buildJsonObject {
                schema.forEach { (key, value) -> put(key, value) }
                put("description", description)
            }
        },
        required,
    )

    fun build(): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { properties.forEach { (k, v) -> put(k, v) } })
        if (required.isNotEmpty()) put("required", required.toJsonArray())
    }

    private fun property(name: String, schema: JsonObject, isRequired: Boolean) {
        properties[name] = schema
        if (isRequired) required += name
    }
}

/** Build a tool's `parameters` schema. */
fun toolSchema(build: ToolSchemaBuilder.() -> Unit): JsonObject =
    ToolSchemaBuilder().apply(build).build()

private fun List<String>.toJsonArray(): JsonArray = buildJsonArray {
    this@toJsonArray.forEach { add(JsonPrimitive(it)) }
}

/** Convenience for a schema with no arguments. */
val NO_ARGUMENTS: JsonObject = toolSchema { }

/** Read a string argument, falling back when absent or of the wrong type. */
fun JsonObject.stringArg(key: String, fallback: String = ""): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fallback

/** Read a string argument, or null when absent. */
fun JsonObject.stringArgOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.intArg(key: String, fallback: Int): Int =
    ((this[key] as? JsonPrimitive)?.content)?.toIntOrNull() ?: fallback

fun JsonObject.boolArg(key: String, fallback: Boolean): Boolean =
    ((this[key] as? JsonPrimitive)?.content)?.toBooleanStrictOrNull() ?: fallback

/** True when the argument object is empty — the model sent no arguments. */
fun JsonObject.hasNoArgs(): Boolean = isEmpty()
