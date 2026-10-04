/*
 * Copyright (C) 2026 Drosh contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package dev.drosh.domain.file

/**
 * Limits the editor enforces before touching the disk.
 *
 * Named constants rather than literals at the call site: these are policy, and
 * a literal in the middle of a read path is how a 40 MB file ends up in a heap
 * that has 40 MB left.
 */
object GuestFileLimits {
    /**
     * Largest file the editor will open.
     *
     * 2 MiB. Well past any config file, script or source file a person edits
     * on a phone, and low enough that the decoded text plus sora-editor's
     * per-line layout structures stay comfortably inside a normal heap even on
     * a low-end device with a 256 MB cap.
     */
    const val MAX_EDIT_BYTES: Long = 2L * 1024 * 1024
}

/**
 * A file the guest shell asked Drosh to open.
 *
 * [guestPath] is the path as the shell sees it — already absolute, because the
 * `editor` shell function resolves a relative argument against `$PWD` before
 * the request leaves the guest. Nothing downstream has to guess what the
 * current working directory was.
 *
 * [exists] is false for a path the editor is about to create. The editor shows
 * an empty buffer rather than an error, because `editor newfile.txt` is a
 * reasonable thing to type.
 */
data class GuestFile(
    val guestPath: String,
    val text: String,
    val exists: Boolean,
) {
    val fileName: String get() = guestPath.substringAfterLast('/')

    /**
     * Lowercased extension without the dot, or an empty string when there is
     * none. Drives syntax-highlight selection.
     */
    val extension: String
        get() = guestPath.substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() && it != guestPath.substringAfterLast('/') }
            ?.lowercase()
            .orEmpty()
}

/** Why a read or a write could not be done. */
sealed class FileFailure {
    /** The path resolved to nothing — a directory, or a broken symlink. */
    data class NotFound(val guestPath: String) : FileFailure()

    /** A directory was passed where a file was expected. */
    data class IsDirectory(val guestPath: String) : FileFailure()

    /**
     * The path resolved outside the rootfs. Reported rather than clamped: a
     * request to reach `/sdcard` or `/data` should be visibly refused, not
     * quietly redirected to somewhere harmless.
     */
    data class OutsideRootfs(val guestPath: String) : FileFailure()

    /** The OS refused, or the disk failed. [message] is for logs, not the UI. */
    data class Io(val guestPath: String, val message: String) : FileFailure()

    /**
     * The file is larger than the editor will hold in memory. [sizeBytes] is
     * reported so the UI can say how big it actually is.
     */
    data class TooLarge(val guestPath: String, val sizeBytes: Long) : FileFailure()
}

/**
 * Carries a [FileFailure] across a `Result.failure`.
 *
 * [FileFailure] is a sealed value hierarchy, not an exception, because the UI
 * switches over it to choose a message rather than catching it. Wrapping it
 * keeps the original recoverable: callers do
 * `(result.exceptionOrNull() as? GuestFileException)?.reason` and switch on
 * that, with no string matching and no lost type.
 *
 * Lives here rather than beside the implementation so a consumer can unwrap it
 * without depending on `:data`.
 */
class GuestFileException(
    val reason: FileFailure,
) : Exception("guest file operation failed: $reason")

/** Result of a write. */
sealed class FileWriteResult {
    data class Success(val guestPath: String, val bytesWritten: Int) : FileWriteResult()
    data class Failure(val reason: FileFailure) : FileWriteResult()
}

/**
 * Read and write files inside the Ubuntu rootfs.
 *
 * Scope is deliberately the rootfs only. PRoot bind-mounts `/sdcard`,
 * `/storage`, `/data`, `/proc`, `/sys` and the `/system*` partitions into the
 * guest, and none of those has a host path the app can compute — the mount
 * table lives in the PRoot process, not in Kotlin. A naive
 * `rootfsDir + guestPath` concatenation silently produces a wrong path for all
 * of them, so this interface refuses them outright via [FileFailure.OutsideRootfs]
 * rather than pretending.
 *
 * When bind mounts are supported later, that is a new implementation of this
 * same interface, not a change to it.
 *
 * Pure Kotlin by design: the editor's ViewModel and Compose screen depend on
 * this and must not know that `java.io.File` is involved.
 */
interface GuestFileRepository {

    /**
     * Reads the file at [guestPath] as UTF-8.
     *
     * A path that does not exist is [FileFailure.NotFound]; it is not an empty
     * file. Use [openForEditing] to get create-on-save behaviour.
     */
    suspend fun read(guestPath: String): Result<String>

    /**
     * Reads the file for editing, treating "does not exist" as an empty
     * document rather than an error.
     *
     * Every other failure still fails: a directory, a path outside the rootfs
     * or an oversized file are problems the user needs to see.
     */
    suspend fun openForEditing(guestPath: String): Result<GuestFile>

    /**
     * Writes [text] to [guestPath] as UTF-8, creating the file and any missing
     * parent directories.
     *
     * Not atomic — a plain write. Atomic replace would need a temporary file
     * and a rename, and the failure mode being guarded against (the app being
     * killed mid-save) is one where a half-written file the user can see and
     * retry beats a silent one they cannot.
     */
    suspend fun write(guestPath: String, text: String): FileWriteResult
}