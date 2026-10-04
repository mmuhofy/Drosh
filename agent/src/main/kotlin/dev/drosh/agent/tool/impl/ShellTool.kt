package dev.drosh.agent.tool.impl

import dev.drosh.domain.agent.AgentLimits
import dev.drosh.domain.agent.Tool
import dev.drosh.domain.agent.ToolContext
import dev.drosh.domain.agent.ToolResult
import dev.drosh.domain.agent.ToolUpdate
import dev.drosh.domain.agent.intArg
import dev.drosh.domain.agent.stringArg
import dev.drosh.domain.agent.toolSchema
import dev.drosh.terminal.TerminalManager
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a shell command in the guest and streams its output back live.
 *
 * ## Why the output callback is the whole point
 *
 * `TerminalManager.executeCommand` has always accepted an `onOutput` callback.
 * The previous implementation exposed a `setOutputListener` that nothing ever
 * called, so a command that took thirty seconds produced no output for thirty
 * seconds and looked like a hang. Here every line goes straight to
 * [ToolContext.emit], so the tool row fills while the command runs.
 *
 * ## Why there is no separate output parameter
 *
 * The model should not have to learn two ways of asking for the same thing.
 * Long output is handled by the caller clipping it, not by a knob on the tool.
 */
@Singleton
class ShellTool @Inject constructor(
    private val terminalManager: TerminalManager,
) : Tool {

    override val name: String = NAME
    override val description: String =
        "Run a shell command in the Linux environment. Returns its combined stdout " +
            "and stderr plus the exit code. Use this to inspect the filesystem, build, " +
            "test, install packages, or run anything else. The command runs in the " +
            "current working directory; paths you pass are relative to it."
    override val requiresApproval: Boolean = false
    override val parameters: JsonObject = toolSchema {
        string("command", "the shell command to run, e.g. \"ls -la\" or \"npm run build\"")
        integer(
            name = "timeout",
            description = "seconds to wait before killing the command. Raise it for " +
                "installs and builds; leave it out otherwise.",
            required = false,
        )
    }

    override fun summarize(input: JsonObject): String {
        val command = input.stringArg("command").ifBlank { "(empty)" }
        return if (command.length <= SUMMARY_LIMIT) command else command.take(SUMMARY_LIMIT) + "…"
    }

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val command = input.stringArg("command").trim()
        if (command.isEmpty()) {
            return ToolResult.Error("command must not be empty")
        }

        val timeoutSec = input.intArg("timeout", AgentLimits.SHELL_TIMEOUT_SEC.toInt()).toLong()
            .coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)

        var lineCount = 0
        val result = terminalManager.executeCommand(
            command = command,
            timeoutSec = timeoutSec,
            onOutput = { line ->
                lineCount++
                if (lineCount <= MAX_STREAMED_LINES) {
                    context.emit(ToolUpdate.Output(line))
                }
            },
        )

        val exitNote = when (result) {
            is ToolResult.Success -> {
                val body = result.output
                if (body.isBlank()) {
                    "exit 0, no output"
                } else {
                    "exit 0\n$body"
                }
            }

            is ToolResult.Error -> "ERROR: ${result.message}"
            is ToolResult.Cancelled -> "CANCELLED: ${result.reason}"
            is ToolResult.AwaitingApproval -> return result
        }

        if (lineCount > MAX_STREAMED_LINES) {
            return ToolResult.Success("$exitNote\n[${lineCount - MAX_STREAMED_LINES} earlier lines omitted from the live view]")
        }
        return ToolResult.Success(exitNote)
    }

    companion object {
        const val NAME = "shell"

        /** Tool rows collapse at around this width; keep the summary to one line. */
        private const val SUMMARY_LIMIT = 80

        /** Streaming more than this is noise in the UI and useless in context. */
        private const val MAX_STREAMED_LINES = 400

        private const val MIN_TIMEOUT_SEC = 1L
        private const val MAX_TIMEOUT_SEC = 600L
    }
}
