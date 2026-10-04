package dev.drosh.agent.tool

import dev.drosh.domain.agent.ToolResult
import java.io.File
import java.io.IOException

/**
 * Translates guest paths to host paths and refuses to leave the working tree.
 *
 * ## The mapping
 *
 * The PRoot guest root lives at `<filesDir>/ubuntu/rootfs`, so `/home/zsh/App.kt`
 * is `<filesDir>/ubuntu/rootfs/home/zsh/App.kt` on the device — the same thing
 * `TerminalManager` does when it reads `File(rootfsDir, "home")`.
 *
 * A handful of guest prefixes are *not* under the rootfs. `ProotRunner` bind-mounts
 * `/data`, `/storage` and `/sdcard` from the real filesystem, so those resolve to
 * the real absolute path. Getting this wrong in either direction is a security
 * problem: a false negative writes outside the sandbox, a false positive makes
 * `/sdcard/Download` unreadable.
 *
 * ## Why containment is enforced here and not per tool
 *
 * Every file tool needs the same three checks — normalise, resolve, confirm the
 * result is inside the working directory. Centralised, they cannot be forgotten in
 * one of the tools, and a tool added later gets containment for free.
 */
class GuestPaths(
    private val rootfsDir: File,
    private val workingDirectory: String,
) {

    /** Guest prefixes that ProotRunner binds from the real device. */
    private val bindMounts = listOf("/data", "/storage", "/sdcard")

    /** The guest working directory this resolver is scoped to. */
    val workDir: String = normaliseGuest(workingDirectory)

    private val workDirHost: File = resolveOrNull(workDir)
        ?: throw IllegalArgumentException("working directory does not exist: $workingDirectory")

    /**
     * Resolve a guest path to a host file, or fail.
     *
     * @param mustExist reject a path that is not already there — for reads
     * @param mustBeDirectory reject a path that is not a directory
     */
    fun resolve(
        guestPath: String,
        mustExist: Boolean = true,
        mustBeDirectory: Boolean = false,
    ): ToolResult {
        val normalized = try {
            normaliseGuest(guestPath)
        } catch (e: IllegalArgumentException) {
            return ToolResult.Error(e.message ?: "invalid path")
        }

        if (normalized == "/") {
            return ToolResult.Error("path must not be the filesystem root")
        }

        val host = resolveOrNull(normalized)
            ?: return ToolResult.Error("cannot resolve '$normalized' to a path on this device")

        if (mustExist && !host.exists()) {
            return ToolResult.Error("no such file: $normalized")
        }
        if (mustBeDirectory && host.exists() && !host.isDirectory) {
            return ToolResult.Error("not a directory: $normalized")
        }

        val relative = relativise(host)
            ?: return ToolResult.Error(
                "'$normalized' is outside the working directory ($workDir). " +
                    "The agent may only touch files inside it.",
            )

        return ToolResult.Success(relative)
    }

    /** Resolve and unwrap, for the common case where the path is valid. */
    fun resolveOrNull(guestPath: String): File? {
        val normalized = try {
            normaliseGuest(guestPath)
        } catch (e: IllegalArgumentException) {
            return null
        }

        bindMounts
            .firstOrNull { normalized == it || normalized.startsWith("$it/") }
            ?.let { return File(normalized) }

        return File(rootfsDir, normalized.removePrefix("/"))
    }

    /**
     * The path as the agent should be told about it.
     *
     * Absolute and guest-shaped, because that is what the model will use in its
     * next command.
     */
    fun toGuest(hostFile: File): String {
        val absolute = hostFile.absolutePath
        return if (absolute.startsWith(rootfsDir.absolutePath)) {
            absolute.removePrefix(rootfsDir.absolutePath).ifEmpty { "/" }
        } else {
            absolute
        }
    }

    /**
     * Collapse `.` and `..` without touching the filesystem.
     *
     * Done lexically on purpose: `File.canonicalPath` would resolve symlinks, and
     * a symlink planted inside the working directory pointing at `/data/data/...`
     * would then pass the check while still escaping the sandbox.
     */
    private fun normaliseGuest(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("path must not be empty")
        if (trimmed.any { it == '\u0000' }) throw IllegalArgumentException("path contains a null byte")

        val absolute = if (trimmed.startsWith("/")) trimmed else "$workDir/$trimmed"
        val out = ArrayDeque<String>()
        absolute.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(segment)
            }
        }
        return "/" + out.joinToString("/")
    }

    /**
     * The path relative to the working directory, or null when it escapes.
     *
     * Compared on the normalised lexical form for the same reason as
     * [normaliseGuest].
     */
    private fun relativise(host: File): String? {
        val work = workDirHost.absolutePath.trimEnd('/')
        val target = host.absolutePath.trimEnd('/')
        if (target == work) return "."
        if (!target.startsWith("$work/")) return null
        return target.removePrefix("$work/")
    }

    companion object {
        /** Read a file as UTF-8, or return a result naming the failure. */
        fun readText(file: File, maxChars: Int): ToolResult = try {
            if (file.length() > maxChars) {
                ToolResult.Error(
                    "file is ${file.length()} bytes, over the ${maxChars} limit. " +
                        "Read a range instead (start_line / end_line).",
                )
            } else {
                ToolResult.Success(file.readText())
            }
        } catch (e: IOException) {
            ToolResult.Error("cannot read ${file.name}: ${e.message ?: "I/O error"}")
        } catch (e: SecurityException) {
            ToolResult.Error("cannot read ${file.name}: permission denied")
        }

        /** Write UTF-8 atomically, or return a result naming the failure. */
        fun writeText(file: File, content: String): ToolResult = try {
            file.parentFile?.mkdirs()
            // Write to a sibling then rename, so an interrupted write cannot leave a
            // half-written source file behind.
            val temp = File(file.parentFile, ".${file.name}.drosh-tmp")
            temp.writeText(content)
            if (!temp.renameTo(file)) {
                temp.delete()
                ToolResult.Error("cannot replace ${file.name}: rename failed")
            } else {
                ToolResult.Success("wrote ${file.length()} bytes to ${file.name}")
            }
        } catch (e: IOException) {
            ToolResult.Error("cannot write ${file.name}: ${e.message ?: "I/O error"}")
        } catch (e: SecurityException) {
            ToolResult.Error("cannot write ${file.name}: permission denied")
        }
    }
}