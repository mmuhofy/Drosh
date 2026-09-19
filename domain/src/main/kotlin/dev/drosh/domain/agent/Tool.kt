package dev.drosh.domain.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Base interface for all agent tools.
 *
 * Mirrors IrisCode's IrisTool pattern (domain/agent/IrisTool.kt:18) —
 * pure interface, no implementation dependencies.
 */
interface Tool {
    val name: String
    val description: String
    val requiresApproval: Boolean

    fun toLlmToolDef(): ToolDef

    suspend fun execute(args: Map<String, Any>): ToolResult
}

/**
 * Tool descriptor sent to the LLM as a function/tool declaration.
 * JSON-serializable map for OpenAI-compatible API format.
 */
data class ToolDef(
    val name: String,
    val description: String,
    val parameters: JsonObject
)

/**
 * Builder DSL for ToolDef — simplifies defining structured parameters.
 */
fun toolDef(
    name: String,
    description: String,
    parameters: JsonObject = buildJsonObject { put("type", JsonPrimitive("object")) }
) = ToolDef(name, description, parameters)

fun buildObjectSchema(
    properties: Map<String, PropertySchema>,
    required: List<String> = emptyList()
): JsonObject = buildJsonObject {
    put("type", JsonPrimitive("object"))
    put("properties", buildJsonObject {
        properties.forEach { (propName, schema) ->
            put(propName, schema.toJson())
        }
    })
    put("required", JsonPrimitive(required.joinToString(",")))
}

data class PropertySchema(
    val type: String,
    val description: String,
    val enum: List<String>? = null
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(type))
        put("description", JsonPrimitive(description))
        if (enum != null) {
            put("enum", buildJsonArray {
                enum.forEach { add(JsonPrimitive(it)) }
            })
        }
    }
}