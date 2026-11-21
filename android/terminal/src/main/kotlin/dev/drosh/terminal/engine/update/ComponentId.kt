package dev.drosh.terminal.engine.update

/**
 * The things the update system knows how to fetch.
 *
 * Rust and Kotlin ship together in the APK — a mismatched pair is a crash — so
 * there is no separate `DROSH` component. The three below are the only pieces
 * that can move on their own.
 */
enum class ComponentId(val key: String) {
    APK("apk"),
    GHOSTTY_VT("ghostty_vt"),
    CHROOT_NG("chroot_ng");

    companion object {
        fun fromKey(key: String): ComponentId =
            entries.firstOrNull { it.key == key }
                ?: throw IllegalArgumentException("unknown component: $key")
    }
}

/**
 * Which build of a component the user wants to follow.
 *
 * A single choice governs APK and VT together (see [UpdateChannel.fromKey]):
 * mixing a nightly APK with a stable engine is the kind of combination that
 * produces a crash nobody can attribute.
 */
enum class UpdateChannel(val key: String, val label: String, val unstable: Boolean) {
    RELEASE("release", "Kararlı", false),
    NIGHTLY("nightly", "Gecelik", true),
}

/**
 * Where an installed copy came from.
 *
 * A bundled copy is the floor: it cannot be removed, and a device that never
 * reaches the network still works. A downloaded copy can be removed, which is
 * what makes "go back to the bundled engine" a real operation.
 */
enum class InstallSource { BUNDLED, DOWNLOADED }
