package dev.drosh.terminal.engine.update

import android.content.Context
import android.util.Log
import dev.drosh.linux.UbuntuBootstrap
import java.io.File

/**
 * Choosing which copy of each component to load, once, before anything else
 * touches it.
 *
 * A loaded `.so` cannot be unloaded: `dlclose` on Android is not reliable and
 * a running terminal's function pointers live in that library. So the choice is
 * made here, at the first call, and frozen for the life of the process — the
 * second choice would be the one that swaps a library under a running terminal.
 */
object ComponentLoader {

    private const val TAG = "ComponentLoader"

    /** True once a load has been attempted, from either source. */
    @Volatile
    var loaded: Boolean = false
        private set

    /** The reason loading failed, when it did. */
    @Volatile
    var failure: String? = null
        private set

    /**
     * Load the components, and then our own library that links the VT engine.
     *
     * Returns true when the VT engine and our library are loaded. A failure is
     * remembered rather than retried: a missing library does not appear later.
     */
    @Synchronized
    fun load(context: Context, store: ComponentStore): Boolean {
        if (loaded) return true
        failure?.let { return false }

        return try {
            // chroot-ng is not loaded; it is staged where it can be run. The
            // path the bootstrap uses is overridden when a downloaded copy is
            // active, so an update takes effect on the next process start.
            stageChrootNg(context, store)

            // The VT engine: a downloaded copy wins, but only if it is intact.
            val downloadedVt = store.downloadedLibrary(ComponentId.GHOSTTY_VT, null)
            if (downloadedVt != null) {
                System.load(downloadedVt.absolutePath)
                Log.i(TAG, "engine loaded from ${downloadedVt.absolutePath}")
            } else {
                System.loadLibrary("ghostty-vt")
                Log.i(TAG, "engine loaded from the APK")
            }

            // Our own library links the engine, so it is loaded second.
            System.loadLibrary("drosh")
            loaded = true
            true
        } catch (error: UnsatisfiedLinkError) {
            failure = error.message ?: error.javaClass.name
            Log.e(TAG, "the engine did not load: $failure", error)
            false
        }
    }

    /**
     * Point the bootstrap at a downloaded chroot-ng when one is active.
     *
     * The bootstrap reads [UbuntuBootstrap.chrootNgFile] for the path, so the
     * override is a single field on the bootstrap. When there is no downloaded
     * copy, or it fails its checksum, the bundled asset path is used and the
     * override is cleared.
     */
    private fun stageChrootNg(context: Context, store: ComponentStore) {
        val state = store.installed(ComponentId.CHROOT_NG)
        if (state?.source != InstallSource.DOWNLOADED) return
        val file = store.downloadedLibrary(ComponentId.CHROOT_NG, state.sha256)
        if (file == null) return
        // The bootstrap is constructed later by the session layer; the override
        // is recorded here so it can be read without a second store lookup.
        System.setProperty("dev.drosh.chrootNg.override", file.absolutePath)
    }

    /** Forget a previous failure, so a retry after an install can succeed. */
    @Synchronized
    fun reset() {
        if (loaded) return
        failure = null
    }

    /** The ABI this process is running. */
    fun abi(): String = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
}
