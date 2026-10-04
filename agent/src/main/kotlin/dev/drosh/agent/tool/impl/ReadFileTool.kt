package dev.drosh.agent.tool.impl

import dev.drosh.agent.tool.GuestPaths
import dev.drosh.domain.agent.AgentLimits
import dev.drosh.domain.agent.Tool
import dev.drosh.domain.agent.ToolContext
import dev.drosh.domain.agent.ToolResult
import dev.drosh.domain.agent.intArg
import dev.drosh.domain.agent.stringArg
import dev.drosh.domain.agent.toolSchema
import kotlinx.serialization.json.JsonObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a file from the guest filesystem into the conversation.
 *
 * ## Why there is a range
 *
 * The whole point of reading a file is usually to see one part of it — a
 * function, a config block, the lines around an error. Without a range the only
 * options are "return the entire file" (which can be megabytes and blows the
 * context several turns later) or "shell out to sed", which the model then has to
 * discover. A range is cheaper than both.
 */
@Singleton
class ReadFileTool @Inject constructor(
    private val paths: GuestPaths,
) : Tool {

    override val name: String = NAME
    override val description: String =
        "Read a file from the working directory. Returns its contents with line " +
            "numbers. Pass start_line / end_line to read part of a large file instead " +
            "of the whole thing. Read a file before you change it."
    override val requiresApproval: Boolean = false
    override val parameters: JsonObject = toolSchema {
        string("path", "file path, relative to the working directory or absolute")
        integer("start_line", "first line to return, 1-based", required = false)
        integer("end_line", "last line to return, inclusive", required = false)
    }

    override fun summarize(input: JsonObject): String = input.stringArg("path", "(no path)")

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val path = input.stringArg("path").trim()
        if (path.isEmpty()) return ToolResult.Error("path must not be empty")

        val resolved = paths.resolve(path)
        val relative = (resolved as? ToolResult.Success)?.output
            ?: return resolved

        val file = paths.resolveOrNull(path) ?: return ToolResult.Error("cannot resolve $path")
        if (file.isDirectory) {
            return listingFor(file, relative)
        }

        val startLine = input.intArg("start_line", 1).coerceAtLeast(1)
        val endLine = input.intArg("end_line", -1)

        val read = GuestPaths.readText(file, AgentLimits.READ_FILE_MAX_CHARS)
        val content = (read as? ToolResult.Success)?.output ?: return read

        val text = if (endLine > 0 || startLine > 1) {
            sliceLines(content, startLine, endLine)
        } else {
            content
        }

        return ToolResult.Success(numberLines(text, relative, startLine))
    }

    /** A directory is a listing, not an error — the model asked a reasonable thing. */
    private fun listingFor(dir: File, relative: String): ToolResult {
        val entries = dir.listFiles()
            ?: return ToolResult.Error("cannot list $relative: not readable")

        if (entries.isEmpty()) return ToolResult.Success("$relative is empty")

        val rendered = entries
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            .joinToString("\n") { entry ->
                val name = if (entry.isDirectory) "${entry.name}/" else entry.name
                val size = if (entry.isDirectory) "" else "  ${entry.length()} bytes"
                "$name$size"
            }
        return ToolResult.Success("$relative/\n$rendered")
    }

    private fun sliceLines(content: String, startLine: Int, endLine: Int): String {
        val lines = content.split("\n")
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        val from = (startLine - 1).coerceIn(0, lines.size)
        val to = if (endLine > 0) endLine.coerceAtMost(lines.size) else lines.size
        if (from >= to) {
            return "Requested lines $startLine-${endLine} but the file has ${lines.size} lines."
        }
        return lines.subList(from, to).joinToString("\n")
    }

    /**
     * Prefix line numbers.
     *
     * They cost a few characters each and they let the model address a range in a
     * follow-up instead of counting from the top again.
     */
    private fun numberLines(text: String, label: String, firstLine: Int): String {
        val lines = text.split("\n")
        val width = (firstLine + lines.size).toString().length
        val body = lines.mapIndexed { index, line ->
            val number = (firstLine + index).toString().padStart(width, ' ')
            "$number\t$line"
        }.joinToString("\n")
        return "$label (${lines.size} lines)\n$body"
    }

    companion object {
        const val NAME = "read_file"
    }
}