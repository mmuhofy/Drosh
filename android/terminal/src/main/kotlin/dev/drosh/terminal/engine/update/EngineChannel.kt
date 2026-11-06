package dev.drosh.terminal.engine.update

/**
 * Which build of the engine the app is running, and which one it could run.
 *
 * ## Why the engine is a separate artifact
 *
 * `libghostty-vt.so` is somebody else's build — a Zig library, built for our
 * ABIs, published with a checksum. Shipping it inside the APK means the engine
 * only ever changes when the whole app does, which for a two-megabyte library
 * that upstream improves monthly is the wrong trade.
 *
 * So: the APK carries a known-good copy, and a newer one can be downloaded and
 * used instead. The APK's copy is the floor — a device that never reaches the
 * network still has a working terminal.
 *
 * ## The one constraint
 *
 * A loaded `.so` cannot be unloaded. `dlclose` on Android is not reliable, and
 * a running terminal's function pointers live in that library, so swapping it
 * under a live process is a crash. An update therefore takes effect at the
 * **next process start**, and the UI has to say so rather than pretending
 * otherwise.
 *
 * The second constraint is `targetSdk`. Android 10 (API 29) and above forbid
 * loading code from a writable directory — the W^X rule. Drosh targets 28, so
 * the downloaded copy loads. Raising `targetSdk` past 28 breaks this feature;
 * see `docs/MEMORY_BANK.md`.
 */
sealed interface EngineChannel {

    /** A tagged release. Tested before it is published. */
    data class Release(val version: String) : EngineChannel

    /**
     * A build from the day's upstream commit.
     *
     * Useful and unstable: the whole point is to see an upstream change before
     * a release carries it, which means it can be broken.
     */
    data class Nightly(val date: String, val commit: String) : EngineChannel

    /** The channel's name, for a preference and for the UI. */
    val key: String
        get() = when (this) {
            is Release -> "release"
            is Nightly -> "nightly"
        }

    /** What the UI calls it. */
    val label: String
        get() = when (this) {
            is Release -> "Kararlı"
            is Nightly -> "Gecelik"
        }

    /** True when the build is not a tested release. */
    val isUnstable: Boolean
        get() = this is Nightly

    companion object {
        /** The default: a device that never chooses gets the tested build. */
        val DEFAULT: EngineChannel = Release("bundled")

        /** Parse a stored preference. */
        fun fromKey(key: String): EngineChannel =
            if (key == "nightly") Nightly(date = "", commit = "") else DEFAULT
    }
}

/**
 * One published build of the engine.
 *
 * The checksum is not optional: an artifact downloaded over the network and
 * loaded into the process is code execution, and a build that skips the check
 * is a build that trusts whatever answered the request.
 */
data class EngineBuild(
    /** The release tag, e.g. `vt-v1.4.0` or `nightly-20261106`. */
    val tag: String,
    val channel: EngineChannel,
    /** Which Ghostty commit the library was built from. */
    val ghosttyCommit: String,
    /** The Android ABI the artifact is for. */
    val abi: String,
    /** The library's size in bytes, for the download UI. */
    val sizeBytes: Long,
    /** SHA-256 of the library, as published. */
    val sha256: String,
    /** Where to get it. */
    val downloadUrl: String,
    /** When it was published, as the API reports it. */
    val publishedAt: String,
) {
    /** True when the artifact is worth offering for this ABI. */
    fun usableOn(abi: String): Boolean = this.abi == abi
}

/**
 * Where an installed engine came from.
 *
 * The two sources are not interchangeable: the APK's copy cannot be removed
 * (it is the floor), and a downloaded copy can be. Keeping them apart is what
 * makes "go back to the bundled engine" a real operation rather than a wish.
 */
sealed interface EngineSource {

    /** The copy inside the APK's `jniLibs`. */
    data object Bundled : EngineSource

    /** A copy downloaded into the app's files directory. */
    data class Downloaded(val build: EngineBuild, val path: String) : EngineSource
}
