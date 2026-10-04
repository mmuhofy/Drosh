package dev.drosh.agent.tool.impl

import dev.drosh.agent.tool.GuestPaths
import dev.drosh.agent.tool.diff.TextDiff
import dev.drosh.domain.agent.ApprovalDecision
import dev.drosh.domain.agent.ApprovalRequest
import dev.drosh.domain.agent.Tool
import dev.drosh.domain.agent.ToolContext
import dev.drosh.domain.agent.ToolResult
import dev.drosh.domain.agent.stringArg
import dev.drosh.domain.agent.toolSchema
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes a file, after showing the user exactly what would change.
 *
 * ## The diff is computed here, not in the guest
 *
 * Producing the preview with `git diff --no-index` or a `diff` binary would mean
 * depending on what happens to be installed in a minimal rootfs, and would let
 * the agent show an approval card that it then cannot apply. Computing it on the
 * host means the preview and the write come from the same code path.
 *
 * ## The file is never touched before the answer
 *
 * The old content is read, the diff is computed, the user is asked, and only then
 * is anything written. A rejected change leaves the filesystem exactly as it was,
 * and the model is told why so it can adapt rather than retry the same write.
 *
 * ## Applies whole files, not patches
 *
 * The model sends the complete new content. A patch format would be more compact
 * on the wire, but it would need applying to a file that may have changed since
 * the model read it — and a failed hunk on a source file is worse than an
 * oversized request.
 */
@Singleton
class WriteFileTool @Inject constructor(
    private val paths: GuestPaths,
) : Tool {

    override val name: String = NAME
    override val description: String =
        "Replace a file's contents. Always read the file first with read_file, " +
            "then send the complete new contents. The user sees a diff and must " +
            "approve before anything is written."
    override val requiresApproval: Boolean = true
    override val parameters: JsonObject = toolSchema {
        string("path", "file path, relative to the working directory or absolute")
        string("content", "the complete new contents of the file")
    }

    override fun summarize(input: JsonObject): String = input.stringArg("path", "(no path)")

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val path = input.stringArg("path").trim()
        if (path.isEmpty()) return ToolResult.Error("path must not be empty")
        if (!input.containsKey("content")) {
            return ToolResult.Error("content is required — send the complete new contents")
        }
        val newContent = input.stringArg("content")

        val resolved = paths.resolve(path, mustExist = false)
        val relative = (resolved as? ToolResult.Success)?.output ?: return resolved

        val file = paths.resolveOrNull(path)
            ?: return ToolResult.Error("cannot resolve $path")

        val existed = file.exists()
        if (existed && file.isDirectory) {
            return ToolResult.Error("$relative is a directory")
        }
        val oldContent = if (existed) {
            (GuestPaths.readText(file, MAX_EDITABLE_BYTES) as? ToolResult.Success)?.output
                ?: return ToolResult.Error(
                    "$relative is too large to edit (${file.length()} bytes, limit $MAX_EDITABLE_BYTES)",
                )
        } else {
            ""
        }

        if (oldContent == newContent) {
            return ToolResult.Success("$relative already has exactly this content; nothing to write")
        }

        val result = TextDiff.diff(oldContent, newContent, relative, relative)
        val verb = if (existed) "Modify" else "Create"

        when (
            val decision = context.awaitApproval(
                ApprovalRequest(
                    title = "$verb $relative?",
                    body = if (existed) {
                        null
                    } else {
                        "This file does not exist yet."
                    },
                    diff = TextDiff.unified(
                        old = oldContent,
                        new = newContent,
                        oldLabel = relative,
                        newLabel = relative,
                    ),
                ),
            )
        ) {
            ApprovalDecision.Approve -> Unit

            is ApprovalDecision.Reject ->
                return ToolResult.Cancelled(
                    "user rejected the change to $relative: ${decision.reason}",
                )

            is ApprovalDecision.Answer ->
                return ToolResult.Error(
                    "write_file needs an approve or reject decision, not free text: " +
                        "\"${decision.text}\"",
                )
        }

        val write = GuestPaths.writeText(file, newContent)
        if (write is ToolResult.Error) return write

        return ToolResult.Success(
            "${(write as ToolResult.Success).output} (${TextDiff.summary(result)})",
        )
    }

    companion object {
        const val NAME = "write_file"

        /** Refuse to load something enormous into memory just to diff it. */
        private const val MAX_EDITABLE_BYTES = 1_000_000
    }
}