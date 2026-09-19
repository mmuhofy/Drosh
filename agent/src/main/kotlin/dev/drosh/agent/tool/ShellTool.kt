package dev.drosh.agent.tool

import dev.drosh.domain.agent.PropertySchema
import dev.drosh.domain.agent.Tool
import dev.drosh.domain.agent.ToolDef
import dev.drosh.domain.agent.ToolResult
import dev.drosh.domain.agent.buildObjectSchema
import dev.drosh.terminal.TerminalManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

class ShellTool @Inject constructor(
    private val terminalManager: TerminalManager
) : Tool {

    override val name = "shell"
    override val description =
        "Execute a shell command in the project directory. " +
        "Output is streamed to chat. Use for grep, git, ls, tests, builds. " +
        "Avoid interactive commands or long-running processes."

    override val requiresApproval = false

    private var onOutputLine: ((eventId: String, line: String) -> Unit)? = null

    fun setOutputListener(listener: (eventId: String, line: String) -> Unit) {
        onOutputLine = listener
    }

    override fun toLlmToolDef(): ToolDef = ToolDef(
        name = name,
        description = description,
        parameters = buildObjectSchema(
            properties = mapOf(
                "command" to PropertySchema("string", "Shell command to execute"),
                "timeout_seconds" to PropertySchema(
                    type = "integer",
                    description = "Max seconds to wait (default 30, max 120)"
                )
            ),
            required = listOf("command")
        )
    )

    override suspend fun execute(args: Map<String, Any>): ToolResult = withContext(Dispatchers.IO) {
        val command = args["command"] as? String
            ?: return@withContext ToolResult.Error("Missing required argument: command")

        val timeoutSec = ((args["timeout_seconds"] as? Number)?.toLong() ?: 30L)
            .coerceIn(1L, 120L)

        val eventId = UUID.randomUUID().toString()

        val result = terminalManager.executeCommand(
            command = command,
            timeoutSec = timeoutSec,
            onOutput = { line ->
                onOutputLine?.invoke(eventId, line)
            }
        )

        result
    }
}