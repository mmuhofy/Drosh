package dev.drosh.agent.tool.impl

import dev.drosh.agent.tool.GuestPaths
import dev.drosh.domain.agent.ApprovalDecision
import dev.drosh.domain.agent.ApprovalRequest
import dev.drosh.domain.agent.Tool
import dev.drosh.domain.agent.ToolContext
import dev.drosh.domain.agent.ToolResult
import dev.drosh.domain.agent.boolArg
import dev.drosh.domain.agent.stringArg
import dev.drosh.domain.agent.toolSchema
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Move or rename a file.
 *
 * ## Why it asks even though the model named both paths
 *
 * The model has to be the one naming the destination, which is what makes a move
 * destructive from the user's point of view: a file that was `notes.md` is now
 * `archive/notes.md`, and nothing about that is recoverable without knowing where
 * it went. The approval card names both paths so the change is legible at a glance.
 *
 * ## Why `overwrite` defaults to off
 *
 * Overwriting a destination silently destroys it. It stays available as an
 * explicit argument, and even then the card says which file is being replaced.
 */
@Singleton
class MoveFileTool @Inject constructor(
    private val paths: GuestPaths,
) : Tool {

    override val name: String = NAME
    override val description: String =
        "Move or rename a file or directory. Use it instead of a shell mv so the " +
            "user sees and approves the change. Both paths are relative to the " +
            "working directory."
    override val requiresApproval: Boolean = true
    override val parameters: JsonObject = toolSchema {
        string("from", "existing path, relative to the working directory")
        string("to", "new path, relative to the working directory")
        bool(
            name = "overwrite",
            description = "replace the destination if something is already there",
            required = false,
        )
    }

    override fun summarize(input: JsonObject): String =
        "${input.stringArg("from", "?")} → ${input.stringArg("to", "?")}"

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val from = input.stringArg("from").trim()
        val to = input.stringArg("to").trim()
        if (from.isEmpty() || to.isEmpty()) {
            return ToolResult.Error("both from and to are required")
        }
        if (from == to) return ToolResult.Success("$from is already the destination")

        val source = paths.resolve(from, mustExist = true)
        val sourcePath = (source as? ToolResult.Success)?.output ?: return source
        val destination = paths.resolve(to, mustExist = false)
        val destinationPath = (destination as? ToolResult.Success)?.output ?: return destination

        val sourceFile = paths.resolveOrNull(from)
            ?: return ToolResult.Error("cannot resolve $from")
        val destinationFile = paths.resolveOrNull(to)
            ?: return ToolResult.Error("cannot resolve $to")

        if (destinationFile.exists()) {
            if (destinationFile.isDirectory) {
                return ToolResult.Error("$destinationPath is a directory. Give a file name, not a folder.")
            }
            if (!input.boolArg("overwrite", false)) {
                return ToolResult.Error(
                    "$destinationPath already exists. Pass overwrite=true to replace it.",
                )
            }
        }

        when (
            val decision = context.awaitApproval(
                ApprovalRequest(
                    title = if (destinationFile.exists()) {
                        "$sourcePath → $destinationPath (replaces the existing file)"
                    } else {
                        "$sourcePath → $destinationPath"
                    },
                    body = if (destinationFile.exists()) {
                        "The file at $destinationPath will be lost."
                    } else {
                        null
                    },
                ),
            )
        ) {
            ApprovalDecision.Approve -> Unit
            is ApprovalDecision.Reject ->
                return ToolResult.Cancelled("user declined the move: ${decision.reason}")
            is ApprovalDecision.Answer ->
                return ToolResult.Error("move_file needs an approve or reject decision")
        }

        destinationFile.parentFile?.mkdirs()

        val moved = if (destinationFile.exists()) {
            // A plain rename over an existing file fails on some filesystems and
            // silently succeeds on others; removing first makes the behaviour the
            // same everywhere, and the user has just approved replacing it.
            if (destinationFile.delete()) {
                sourceFile.renameTo(destinationFile)
            } else {
                false
            }
        } else {
            sourceFile.renameTo(destinationFile)
        }

        if (!moved) {
            // renameTo does not copy across filesystems, which a bind-mounted
            // /sdcard and the rootfs are.
            val copied = sourceFile.copyRecursively(destinationFile, overwrite = true)
            if (!copied) {
                return ToolResult.Error("could not move $sourcePath to $destinationPath")
            }
            sourceFile.deleteRecursively()
        }

        val kind = if (sourceFile.isDirectory) "directory" else "file"
        return ToolResult.Success("moved $sourcePath to $destinationPath ($kind)")
    }

    companion object {
        const val NAME = "move_file"
    }
}
