package dev.drosh.terminal.engine.update

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Where the engine library lives on this device, and which one to load.
 *
 * ## The rule
 *
 * A downloaded copy wins over the APK's copy — but only if it is intact. The
 * check is the same SHA-256 the release published, recomputed from the bytes on
 * disk. A file that fails it is deleted rather than trusted: a corrupted
 * library that still loads is worse than one that does not, because the crash
 * happens inside somebody else's code with our stack.
 *
 * ## Why the decision is made here and not in the loader
 *
 * [NativeLibraries] loads by name, which means it loads from the APK. A
 * downloaded copy has an absolute path, so it has to be loaded by path, and
 * the choice between the two has to be made before the first load. This class
 * makes that choice once, at startup, and never again — because the second
 * choice would be the one that swaps a library under a live process.
 */
object EngineInstall {

    private const val TAG = "EngineInstall"

    /** The directory downloaded engines live in, under `filesDir`. */
    private const val DIRECTORY = "engine"

    /** The file name the loader expects inside that directory. */
    private const val LIBRARY = "libghostty-vt.so"

    /**
     * The path of a downloaded engine that passed its checksum, or null when
     * the APK's copy should be used.
     *
     * Deletes a copy that fails: a file that is not what was published is not
     * a file worth keeping.
     */
    fun downloadedLibrary(context: Context, expectedSha256: String?): File? {
        val file = File(File(context.filesDir, DIRECTORY), LIBRARY)
        if (!file.isFile) return null

        if (expectedSha256 != null) {
            val actual = sha256(file)
            if (actual != expectedSha256) {
                Log.w(TAG, "the downloaded engine failed its checksum; removing it")
                file.delete()
                return null
            }
        }

        return file
    }

    /**
     * The SHA-256 the APK's own copy has.
     *
     * Read once and cached: the APK does not change while the process lives.
     * A missing or unreadable entry is null, which the caller treats as "no
     * opinion" rather than as a failure.
     */
    fun bundledSha256(context: Context, abi: String = defaultAbi()): String? {
        bundled?.let { return it }

        val computed = runCatching {
            val apk = context.applicationInfo.sourceDir
            ZipFile(apk).use { zip ->
                val entry = zip.getEntry("lib/$abi/$LIBRARY") ?: return@use null
                zip.getInputStream(entry).use { stream ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
            }
        }.getOrNull()

        bundled = computed
        return computed
    }

    /** The ABI this process is running. */
    fun defaultAbi(): String =
        android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    /** True when a downloaded engine is present and intact. */
    fun hasDownloaded(context: Context, expectedSha256: String?): Boolean =
        downloadedLibrary(context, expectedSha256) != null

    /**
     * Install a downloaded engine.
     *
     * Written to a temporary name first and renamed into place, so a process
     * killed mid-write leaves the previous copy — or nothing — rather than a
     * half-written library that would fail its checksum at the next start.
     */
    fun install(context: Context, bytes: ByteArray, expectedSha256: String): Boolean {
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (actual != expectedSha256) {
            Log.w(TAG, "refusing to install: checksum mismatch")
            return false
        }

        val directory = File(context.filesDir, DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) {
            Log.w(TAG, "could not create $directory")
            return false
        }

        val target = File(directory, LIBRARY)
        val temporary = File(directory, "$LIBRARY.tmp")
        return runCatching {
            temporary.writeBytes(bytes)
            if (target.exists()) target.delete()
            temporary.renameTo(target)
        }.getOrElse {
            Log.w(TAG, "the engine could not be installed", it)
            temporary.delete()
            false
        }
    }

    /** Remove a downloaded engine, going back to the APK's copy. */
    fun removeDownloaded(context: Context) {
        File(File(context.filesDir, DIRECTORY), LIBRARY).delete()
    }

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    @Volatile
    private var bundled: String? = null
}
