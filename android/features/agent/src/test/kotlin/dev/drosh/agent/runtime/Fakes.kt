package dev.drosh.agent.runtime

import dev.drosh.domain.agent.ApprovalDecision
import dev.drosh.domain.agent.ApprovalRequest
import dev.drosh.domain.agent.Tool
import dev.drosh.domain.agent.ToolContext
import dev.drosh.domain.agent.ToolResult
import dev.drosh.domain.agent.ToolUpdate
import dev.drosh.domain.agent.stringArg
import dev.drosh.domain.agent.toolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Test doubles for the loop.
 *
 * The interesting failures live in the loop — retries, repetition, approval
 * parking, two chats at once — and none of them need a network or a device. These
 * are scripted rather than random so a failing test names the behaviour that broke
 * instead of a seed.
 */

/** A tool that records what it was asked to do. */
internal class FakeTool(
    override val name: String,
    override val requiresApproval: Boolean = false,
    private val body: suspend (JsonObject, ToolContext) -> ToolResult = { _, _ -> ToolResult.Success("ok") },
) : Tool {

    override val description: String = "test tool $name"
    override val parameters: JsonObject = toolSchema { string("command", "what to do") }

    val calls: MutableList<JsonObject> = mutableListOf()

    override fun summarize(input: JsonObject): String = input.stringArg("command", name)

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        calls += input
        return body(input, context)
    }
}

/** A tool that parks on consent before doing anything. */
internal class ApprovingTool(
    override val name: String = "write_file",
    private val approval: ApprovalRequest = ApprovalRequest(
        title = "overwrite vite.config.js?",
        diff = "@@ -1,3 +1,4 @@",
        options = listOf("apply", "skip"),
    ),
    private val onApproved: suspend () -> ToolResult = { ToolResult.Success("written") },
) : Tool {

    override val description: String = "writes a file"
    override val requiresApproval: Boolean = true
    override val parameters: JsonObject = toolSchema { string("path", "file to write") }

    /** Times execute got past the approval; proves a rejection skipped the work. */
    var executions: Int = 0
        private set

    var decision: ApprovalDecision? = null
        private set

    override fun summarize(input: JsonObject): String = input.stringArg("path", name)

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val answer = context.awaitApproval(approval)
        decision = answer
        if (answer is ApprovalDecision.Approve) {
            executions++
            return onApproved()
        }
        val reason = (answer as? ApprovalDecision.Reject)?.reason ?: "declined"
        return ToolResult.Cancelled(reason)
    }
}

/** A tool that answers a question with free text or a choice. */
internal class AskingTool(
    override val name: String = "ask_user",
    private val options: List<String> = listOf("automatic", "manual"),
) : Tool {

    override val description: String = "asks the user a question"
    override val requiresApproval: Boolean = false
    override val parameters: JsonObject = toolSchema { string("question", "what to ask") }

    var answer: ApprovalDecision? = null
        private set

    override fun summarize(input: JsonObject): String = input.stringArg("question", name)

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val reply = context.awaitApproval(ApprovalRequest(title = input.stringArg("question"), options = options))
        answer = reply
        return ToolResult.Success(
            (reply as? ApprovalDecision.Answer)?.text
                ?: (reply as? ApprovalDecision.Reject)?.reason
                ?: "no answer",
        )
    }
}

/** A tool that streams output lines before finishing. */
internal class StreamingTool(
    override val name: String = "shell",
    private val lines: List<String> = listOf("vite v5.4.8 building...", "1423 modules transformed"),
) : Tool {

    override val description: String = "runs a command"
    override val requiresApproval: Boolean = false
    override val parameters: JsonObject = toolSchema { string("command", "command to run") }

    override fun summarize(input: JsonObject): String = input.stringArg("command", name)

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        lines.forEach { context.emit(ToolUpdate.Output(it)) }
        return ToolResult.Success(lines.joinToString("\n"))
    }
}

/** A tool that always throws, to prove one broken tool cannot end a run. */
internal class ExplodingTool(
    override val name: String = "boom",
) : Tool {

    override val description: String = "always fails"
    override val requiresApproval: Boolean = false
    override val parameters: JsonObject = toolSchema { string("command", "ignored") }

    override fun summarize(input: JsonObject): String = name

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult =
        throw IllegalStateException("tool exploded")
}

