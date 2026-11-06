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

        return try {
            // The dependency first: libdrosh.so has it in DT_NEEDED.
            System.loadLibrary("ghostty-vt")
            System.loadLibrary("drosh")
            loaded = true
            true
        } catch (error: UnsatisfiedLinkError) {
            // The message is the only diagnosis a device will give: it names
            // the library and the symbol or the missing dependency.
            failure = error.message ?: error.javaClass.name
            false
        }
    }
}
