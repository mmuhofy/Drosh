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

package dev.drosh.data.file

import dev.drosh.domain.file.FileFailure
import dev.drosh.domain.file.FileWriteResult
import dev.drosh.domain.file.GuestFile
import dev.drosh.domain.file.GuestFileException
import dev.drosh.domain.file.GuestFileLimits
import dev.drosh.domain.file.GuestFileRepository
import dev.drosh.terminal.UbuntuBootstrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes files inside the Ubuntu rootfs.
 *
 * The rootfs is a plain directory on the host — `<filesDir>/ubuntu/rootfs` — so
 * a guest path maps to a host path by concatenation and nothing more. That is
 * only true *under* the rootfs; see [resolve] for the bind mounts that break
 * the rule and how they are refused.
 *
 * Every call hops to [Dispatchers.IO]. These are blocking file calls and the
 * callers are coroutines on the main dispatcher, so running them inline would
 * drop frames in the editor exactly when a large file is being read.
 *
 * The dispatcher is referenced directly rather than injected: Hilt cannot
 * satisfy a `CoroutineDispatcher` parameter that has a default value, and a
 * qualifier for the one dispatcher this class uses is more indirection than it
 * saves.
 */
@Singleton
class RootfsGuestFileRepository @Inject constructor(
    private val bootstrap: UbuntuBootstrap,
) : GuestFileRepository {

    override suspend fun read(guestPath: String): Result<String> = withContext(Dispatchers.IO) {
        when (val resolved = resolve(guestPath, mustExist = true)) {
            is Resolution.Failed -> failed(resolved.reason)
            is Resolution.Ok -> readText(resolved.file, guestPath)
        }
    }

    override suspend fun openForEditing(guestPath: String): Result<GuestFile> =
        withContext(Dispatchers.IO) {
            when (val resolved = resolve(guestPath, mustExist = false)) {
                is Resolution.Failed -> failed(resolved.reason)
                is Resolution.Ok -> {
                    val file = resolved.file
                    // Not being there yet is the normal case for
                    // `editor notes.md`, not an error: hand back an empty
                    // document and let the first save create it.
                    if (!file.exists()) {
                        Result.success(GuestFile(guestPath, text = "", exists = false))
                    } else if (file.isDirectory) {
                        failed(FileFailure.IsDirectory(guestPath))
                    } else {
                        // Explicit rather than fold(): `fold` would have to infer
                        // its result type across a lambda that both builds a
                        // GuestFile and re-wraps a failure, and it does not
                        // manage it. getOrElse keeps the success value untouched
                        // and re-wraps only the failure.
                        readText(file, guestPath).fold(
                            onSuccess = { text ->
                                Result.success(GuestFile(guestPath, text = text, exists = true))
                            },
                            onFailure = { error ->
                                failed((error as? GuestFileException)?.reason ?: reasonOf(error))
                            },
                        )
                    }
                }
            }
        }

    override suspend fun write(guestPath: String, text: String): FileWriteResult =
        withContext(Dispatchers.IO) {
            when (val resolved = resolve(guestPath, mustExist = false)) {
                is Resolution.Failed -> FileWriteResult.Failure(resolved.reason)
                is Resolution.Ok -> {
                    val file = resolved.file
                    // Overwriting a directory would otherwise fail deep inside
                    // the JDK with a message about the filesystem provider, so
                    // refuse it where the caller can say something useful.
                    if (file.isDirectory) {
                        FileWriteResult.Failure(FileFailure.IsDirectory(guestPath))
                    } else {
                        try {
                            file.parentFile?.mkdirs()
                            file.writeText(text)
                            FileWriteResult.Success(
                                guestPath,
                                bytesWritten = text.toByteArray(Charsets.UTF_8).size,
                            )
                        } catch (e: IOException) {
                            Timber.e(e, "write failed for %s", guestPath)
                            FileWriteResult.Failure(
                                FileFailure.Io(guestPath, e.message ?: "write failed"),
                            )
                        }
                    }
                }
            }
        }

    private fun readText(file: File, guestPath: String): Result<String> {
        val size = file.length()
        if (size > GuestFileLimits.MAX_EDIT_BYTES) {
            // Refused with the real size rather than a flat "too big", because
            // the user needs to know whether they are 3 MB or 3 GB over.
            return failed(FileFailure.TooLarge(guestPath, size))
        }
        return try {
            Result.success(file.readText())
        } catch (e: IOException) {
            Timber.e(e, "read failed for %s", guestPath)
            failed(FileFailure.Io(guestPath, e.message ?: "read failed"))
        }
    }

    /** Outcome of turning a guest path into a host [File]. */
    private sealed interface Resolution {
        data class Ok(val file: File) : Resolution
        data class Failed(val reason: FileFailure) : Resolution
    }

    /**
     * Maps a guest path onto a host file inside the rootfs, or explains why not.
     *
     * The check that matters is the second half. PRoot bind-mounts `/sdcard`,
     * `/storage`, `/data`, `/proc`, `/sys` and the `/system*` partitions into
     * the guest, and `rootfsDir + "/sdcard/..."` names a real directory that has
     * nothing to do with the phone's shared storage — so a path that looked
     * harmless would quietly read the wrong file. Comparing canonical paths is
     * what refuses those, and it catches `..` and symlinks for free because
     * canonicalisation resolves them.
     *
     * Both sides are canonicalised, so a symlink *inside* the rootfs pointing out
     * of it is rejected too.
     */
    private fun resolve(guestPath: String, mustExist: Boolean): Resolution {
        // A NUL in the name would truncate the path inside native calls; the
        // `editor` shell function strips control bytes, so this is belt and
        // braces for any other caller.
        if (guestPath.isBlank() || guestPath.any { it.code == 0 }) {
            return Resolution.Failed(FileFailure.NotFound(guestPath))
        }
        // The shell function makes the path absolute before it reaches us. If one
        // ever arrives without that, treat it as not found rather than guessing
        // a directory to resolve it against.
        if (!guestPath.startsWith("/")) {
            return Resolution.Failed(FileFailure.NotFound(guestPath))
        }

        val rootfs = bootstrap.rootfsDir
        val target = File(rootfs, guestPath.trimStart('/'))

        return try {
            val rootCanonical = rootfs.canonicalFile
            val targetCanonical = target.canonicalFile
            val inside = targetCanonical.path == rootCanonical.path ||
                targetCanonical.path.startsWith(rootCanonical.path + File.separator)
            if (!inside) {
                Timber.w("refused path outside rootfs: %s", guestPath)
                Resolution.Failed(FileFailure.OutsideRootfs(guestPath))
            } else if (mustExist && !target.exists()) {
                Resolution.Failed(FileFailure.NotFound(guestPath))
            } else {
                Resolution.Ok(target)
            }
        } catch (e: IOException) {
            // canonicalFile throws when a path component cannot be resolved.
            Timber.e(e, "resolve failed for %s", guestPath)
            Resolution.Failed(FileFailure.Io(guestPath, e.message ?: "resolve failed"))
        }
    }

    /**
     * A failed [Result] carrying [reason].
     *
     * Named rather than an extension on `FileFailure` so the result type is
     * fixed at the declaration. As a generic `fun <T> FileFailure.asFailure()`
     * it left the compiler to infer `T` from the surrounding `when`/`fold`,
     * which it could not do and reported as a cascade of type mismatches.
     */
    private fun <T> failed(reason: FileFailure): Result<T> =
        Result.failure(GuestFileException(reason))

    /**
     * Recovers a [FileFailure] from an error that should already be one.
     *
     * Only reached if something throws something other than
     * [GuestFileException] out of [readText], which it does not. Kept total so
     * the editor screen always has a reason to show rather than crashing on a
     * missing one.
     */
    private fun reasonOf(error: Throwable): FileFailure =
        FileFailure.Io("(unknown)", error.message ?: "unknown failure")
}