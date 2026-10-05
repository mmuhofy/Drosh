package dev.drosh.agent.tool.impl

import dev.drosh.agent.tool.GuestPaths
import dev.drosh.domain.agent.Tool
import dev.drosh.domain.agent.ToolContext
import dev.drosh.domain.agent.ToolResult
import dev.drosh.domain.agent.boolArg
import dev.drosh.domain.agent.intArg
import dev.drosh.domain.agent.stringArg
import dev.drosh.domain.agent.toolSchema
import kotlinx.serialization.json.JsonObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Search file contents by pattern.
 *
 * ## Why this is not a shell command
 *
 * The model could `shell("grep -rn pattern .")`, and for a one-off that is fine.
 * It is a bad habit for three reasons: grep is not in every minimal rootfs, its
 * output for a broad pattern is thousands of lines that land in the context, and
 * a model that reaches for a shell command cannot tell the difference between
 * "no matches" and "grep is not installed".
 *
 * Results are capped and grouped by file. The cap is the point — a
 * thousand-line grep result is the same context blow-up as a large `cat`, only
 * harder to notice because it looks like search results.
 */
@Singleton
class GrepTool @Inject constructor(
    private val paths: GuestPaths,
) : Tool {

    override val name: String = NAME
    override val description: String =
        "Search inside files for text or a regular expression, and get back matching " +
            "lines with their file and line number. Use this to find where something is " +
            "used or defined — prefer it over guessing a path, and over grepping through " +
            "shell. Set glob to limit the search to a file type, e.g. \"*.kt\"."
    override val requiresApproval: Boolean = false
    override val parameters: JsonObject = toolSchema {
        string("pattern", "text or regular expression to search for")
        string("path", "file or directory to search, relative to the working directory")
        string("glob", "only search files matching this, e.g. \"*.kt\"", required = false)
        bool(
            name = "ignore_case",
            description = "match regardless of case",
            required = false,
        )
        integer(
            name = "max_results",
            description = "stop after this many matches",
            required = false,
        )
    }

    override fun summarize(input: JsonObject): String {
        val pattern = input.stringArg("pattern").ifBlank { "(empty)" }
        val path = input.stringArg("path")
        return if (path.isBlank()) "in . · $pattern" else "$pattern in $path"
    }

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val pattern = input.stringArg("pattern").trim()
        if (pattern.isEmpty()) return ToolResult.Error("pattern must not be empty")

        val root = input.stringArg("path").ifBlank { paths.workDir }
        val resolved = paths.resolve(root, mustExist = true, mustBeDirectory = false)
        val relative = (resolved as? ToolResult.Success)?.output ?: return resolved

        val searchRoot = paths.resolveOrNull(root)
            ?: return ToolResult.Error("cannot resolve $root")
        val glob = input.stringArg("glob").trim()
        val ignoreCase = input.boolArg("ignore_case", false)
        val maxResults = input.intArg("max_results", DEFAULT_MAX_RESULTS)
            .coerceIn(1, HARD_MAX_RESULTS)

        val regex = compile(pattern, ignoreCase)
            ?: return ToolResult.Error(
                "\"$pattern\" is not a valid regular expression. Pass plain text instead.",
            )

        val matches = mutableListOf<String>()
        val filesSearched = mutableListOf<String>()
        var truncated = false
        var unreadable = 0

        fun visit(file: File) {
            if (matches.size >= maxResults) {
                truncated = true
                return
            }
            when {
                file.isDirectory -> {
                    val children = file.listFiles() ?: run {
                        unreadable++
                        return
                    }
                    children.sortedBy { it.name }.forEach { visit(it) }
                }

                !file.isFile -> Unit
                file.length() > MAX_FILE_BYTES -> Unit // a binary or a huge blob
                !matchesGlob(file.name, glob) -> Unit
                else -> {
                    filesSearched += file.name
                    val lines = runCatching { file.readLines() }.getOrElse {
                        unreadable++
                        return
                    }
                    lines.forEachIndexed { index, line ->
                        if (matches.size < maxResults && regex.containsMatchIn(line)) {
                            val path = paths.toGuest(file)
                            matches += "${path}:${index + 1}: ${line.trim().take(MAX_LINE_CHARS)}"
                        }
                    }
                }
            }
        }

        visit(searchRoot)

        if (matches.isEmpty()) {
            return ToolResult.Success(
                "No matches for \"$pattern\" in $relative" +
                    if (filesSearched.isEmpty()) "" else
                        " (${filesSearched.size} file(s) searched). " +
                            "The pattern may not be present, or the path may not hold it.",
            )
        }

        val header = buildString {
            append(matches.size).append(" match(es) for \"").append(pattern)
            append("\" in ").append(relative)
            append(" · ").append(filesSearched.size).append(" file(s) searched")
            if (truncated) append(" · stopped at the result limit")
            if (unreadable > 0) append(" · ").append(unreadable).append(" unreadable")
        }

        return ToolResult.Success("$header\n${matches.joinToString("\n")}")
    }

    private fun compile(pattern: String, ignoreCase: Boolean) = runCatching {
        Regex(pattern, setOf(RegexOption.IGNORE_CASE.takeIf { ignoreCase } ?: RegexOption.NONE))
    }.getOrNull()

    private fun matchesGlob(name: String, glob: String): Boolean {
        if (glob.isEmpty()) return true
        // A single trailing `*` is the overwhelmingly common case and does not
        // need a real glob; anything else falls back to a full pattern.
        val star = glob.lastIndexOf('*')
        if (star >= 0 && glob.indexOf('*') == star && !glob.dropLast(1).contains('?')) {
            val prefix = glob.take(star)
            return name.startsWith(prefix, ignoreCase = true) &&
                name.length >= prefix.length + 1
        }
        val regex = glob.replace(".", "\\.").replace("*", ".*").replace("?", ".")
        return runCatching { Regex(regex, RegexOption.IGNORE_CASE).matches(name) }.getOrDefault(false)
    }

    companion object {
        const val NAME = "grep"

        /**
         * Ten, not fifty.
         *
         * The model reads the first few matches and asks again with a narrower
         * pattern if it needs more. Fifty matches is the same context cost as
         * reading a file, and usually tells the model nothing the first ten did not.
         */
        const val DEFAULT_MAX_RESULTS = 10

        /** Above this the caller is asking for something a shell command does better. */
        const val HARD_MAX_RESULTS = 50

        /** Skip binaries and large bundles rather than reading them into memory. */
        const val MAX_FILE_BYTES = 1_000_000L

        const val MAX_LINE_CHARS = 200
    }
}
