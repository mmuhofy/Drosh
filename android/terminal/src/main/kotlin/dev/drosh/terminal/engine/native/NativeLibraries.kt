package dev.drosh.terminal.engine.native

/**
 * Loading the two native libraries, in the order the linker needs them.
 *
 * ## The order is not optional
 *
 * `libdrosh.so` links against `libghostty-vt.so`. Android's linker resolves a
 * library's `DT_NEEDED` entries at `dlopen` time, so the dependency has to be
 * loadable by name before its dependent is opened. Both are in the APK's
 * `jniLibs/<abi>/`, which is on the loader path, so loading the dependency
 * first by name is enough.
 *
 * ## Why an object and not a `companion object`
 *
 * The load happens once per process. A `companion object` of a class that is
 * never touched would not run; a top-level object that the entry points
 * reference runs exactly when it is first needed, which is the first JNI call.
 */
object NativeLibraries {

    /** The application context, for the component store. Set at startup. */
    @Volatile
    var appContext: android.content.Context? = null

    /** The component store, created from the context on first use. */
    @Volatile
    var store: dev.drosh.terminal.engine.update.ComponentStore? = null

    /** True once both libraries are loaded and the entry points are bound. */
    @Volatile
    var loaded: Boolean = false
        private set

    /** The reason loading failed, when it did. */
    @Volatile
    var failure: String? = null
        private set

    /**
     * Load both libraries. Safe to call more than once; a failure is remembered
     * rather than retried, because a missing library does not appear later.
     */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        failure?.let { return false }

        val context = appContext
        return if (context != null) {
            val componentStore = store ?: dev.drosh.terminal.engine.update.ComponentStore(context)
                .also { store = it }
            dev.drosh.terminal.engine.update.ComponentLoader.load(context, componentStore)
        } else {
            // No context yet — fall back to the bundled copy so the terminal
            // still opens. The component store is not consulted.
            try {
                System.loadLibrary("ghostty-vt")
                System.loadLibrary("drosh")
                loaded = true
                true
            } catch (error: UnsatisfiedLinkError) {
                failure = error.message ?: error.javaClass.name
                false
            }
        }
    }
}
