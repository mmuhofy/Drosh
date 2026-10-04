package dev.drosh.agent.tool.impl

import dev.drosh.domain.agent.ApprovalDecision
import dev.drosh.domain.agent.ApprovalRequest
import dev.drosh.domain.agent.Tool
import dev.drosh.domain.agent.ToolContext
import dev.drosh.domain.agent.ToolResult
import dev.drosh.domain.agent.stringArg
import dev.drosh.domain.agent.toolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks the user something only they can answer.
 *
 * ## Why this is the escape hatch it is
 *
 * An agent that cannot ask a question will guess. Guessing between two
 * irreversible readings of a requirement costs more than one extra turn, and the
 * user has no way to see that a fork was taken silently. This tool is where the
 * agent stops and says so.
 *
 * ## Approval or question?
 *
 * The same primitive serves both, because from the loop's point of view they are
 * the same act: suspend, present something, take an answer. With [options] the
 * answer is a choice; without, it is free text.
 */
@Singleton
class AskUserTool @Inject constructor() : Tool {

    override val name: String = NAME
    override val description: String =
        "Ask the user a question and wait for their answer. Use this when a decision " +
            "is genuinely theirs to make — which of two valid approaches to take, " +
            "whether to sacrifice existing work, what a value should be. Do not use " +
            "it for anything you can look up, read, or decide yourself."
    override val requiresApproval: Boolean = false
    override val parameters: JsonObject = toolSchema {
        string("question", "the question, in one or two sentences")
        string(
            name = "body",
            description = "optional extra context explaining why you are asking",
            required = false,
        )
        raw(
            name = "options",
            description = "optional list of choices to offer instead of free text",
            schema = OPTIONS_SCHEMA,
            required = false,
        )
    }

    override fun summarize(input: JsonObject): String = input.stringArg("question", "(no question)")

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val question = input.stringArg("question").trim()
        if (question.isEmpty()) return ToolResult.Error("question must not be empty")

        val options = input.stringList("options")

        val decision = context.awaitApproval(
            ApprovalRequest(
                title = question,
                body = input.stringArg("body").trim().ifEmpty { null },
                options = options,
            ),
        )

        return when (decision) {
            ApprovalDecision.Approve ->
                ToolResult.Success("the user acknowledged the question")

            is ApprovalDecision.Answer -> ToolResult.Success(decision.text)

            is ApprovalDecision.Reject ->
                ToolResult.Cancelled("the user declined to answer: ${decision.reason}")
        }
    }

    private fun JsonObject.stringList(key: String): List<String> {
        val array = this[key] as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { v -> v.isNotBlank() } }
    }

    companion object {
        const val NAME = "ask_user"

        /** Declared by hand because the DSL covers scalars, not arrays. */
        private val OPTIONS_SCHEMA = buildJsonObject {
            put("type", "array")
            put("description", "choices to offer the user")
            put("items", buildJsonObject { put("type", "string") })
        }
    }
}
