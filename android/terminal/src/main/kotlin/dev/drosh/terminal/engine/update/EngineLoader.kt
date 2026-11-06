package dev.drosh.terminal.engine.update

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Choosing which engine to load, once, before anything else touches it.
 *
 * ## Why this is a separate object
 *
 * `System.loadLibrary` resolves against the APK's `jniLibs`. A downloaded copy
 * has an absolute path and has to be loaded by path. The choice between them
 * is made here and nowhere else, at the first call, and the result is frozen
 * for the life of the process — because the second choice would be the one
 * that swaps a library under a running terminal.
 *
 * ## The order
 *
 * ```
 * 1. a downloaded copy that passes its checksum   → loaded by path
 * 2. the APK's copy                                → loaded by name
 * 3. neither                                       → failure, reported
 * ```
 *
 * Step 1 is deliberately paranoid: the checksum is recomputed from the bytes
 * on disk against the one the release published. A library is code execution,
 * and a file that is not the one that was published is not a file to run.
 */
object EngineLoader {

    private const val TAG = "EngineLoader"

    /** The engine library's name, without the platform prefix or suffix. */
    private const val LIBRARY = "ghostty-vt"

    /** True once an engine has been loaded, from either source. */
    @Volatile
    var loaded: Boolean = false
        private set

    /** Which source the loaded engine came from. */
    @Volatile
    var source: EngineSource = EngineSource.Bundled
        private set

    /** The reason loading failed, when it did. */
    @Volatile
    var failure: String? = null
        private set

    /**
     * Load the engine, and then our own library that links it.
     *
     * Returns true when both are loaded. A failure is remembered rather than
     * retried: a missing library does not appear later.
     */
    @Synchronized
    fun load(context: Context?, expectedSha256: String? = null): Boolean {
        if (loaded) return true
        failure?.let { return false }

        val downloaded = context?.let { EngineInstall.downloadedLibrary(it, expectedSha256) }

        return try {
            if (downloaded != null) {
                // Loaded by absolute path: the file is ours, in the app's
                // files directory, and the loader has no name for it.
                System.load(downloaded.absolutePath)
                source = EngineSource.Downloaded(
                    build = EngineBuild(
                        tag = downloaded.parentFile?.name ?: "downloaded",
                        channel = EngineChannel.DEFAULT,
                        ghosttyCommit = "",
                        abi = EngineInstall.defaultAbi(),
                        sizeBytes = downloaded.length(),
                        sha256 = expectedSha256 ?: "",
                        downloadUrl = "",
                        publishedAt = "",
                    ),
                    path = downloaded.absolutePath,
                )
                Log.i(TAG, "engine loaded from ${downloaded.absolutePath}")
            } else {
                // The APK's copy. Loaded by name so the platform's own
                // resolution and its `jniLibs` extraction apply.
                System.loadLibrary(LIBRARY)
                source = EngineSource.Bundled
                Log.i(TAG, "engine loaded from the APK")
            }

            // Our own library links the engine, so it is loaded second.
            System.loadLibrary("drosh")
            loaded = true
            true
        } catch (error: UnsatisfiedLinkError) {
            // The message is the only diagnosis a device gives: it names the
            // library and the missing symbol or dependency.
            failure = error.message ?: error.javaClass.name
            Log.e(TAG, "the engine did not load: $failure", error)
            false
        }
    }

    /** Forget a previous failure, so a retry after an install can succeed. */
    @Synchronized
    fun reset() {
        if (loaded) return
        failure = null
    }

    /** The ABI the loaded engine was built for. */
    fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
}
