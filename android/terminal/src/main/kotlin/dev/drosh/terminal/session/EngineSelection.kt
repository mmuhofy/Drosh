package dev.drosh.terminal.session

/**
 * Which engine a terminal runs on.
 *
 * ## Why a flag and not a swap
 *
 * The Ghostty engine is new. It compiles, it links, and its pieces are tested —
 * but it has never drawn a frame on a device, and no amount of CI proves that a
 * GL context comes up on a particular phone. Deleting eleven thousand lines of
 * working code before the replacement has been seen once would be a bet, not an
 * engineering decision.
 *
 * So both engines live in the APK and the flag picks one. The old one is the
 * default until the new one has been used; flipping the default is a one-line
 * change, and rolling back is the same one line.
 *
 * ## What the flag does not change
 *
 * Everything above it. `EngineTerminal` and `GhosttyTerminal` present the same
 * host interface, so the manager, the panes and the Compose screens do not know
 * which one they are holding.
 */
object EngineSelection {

    /**
     * True to run on the Ghostty engine.
     *
     * Set from the settings screen, and from an intent extra for a build that
     * wants to test one without shipping the choice.
     */
    @Volatile
    var useGhostty: Boolean = false

    /**
     * The engine this process should use.
     *
     * A stored preference wins; a build without one uses the old engine, which
     * is the one that has been on a device.
     */
    fun current(): EngineKind = if (useGhostty) EngineKind.GHOSTTY else EngineKind.LEGACY

    /** The two engines. */
    enum class EngineKind {
        /** The Rust engine, `libdrosh_core.so`. The one that ships today. */
        LEGACY,

        /** The Ghostty engine, `libghostty-vt.so` + `libdrosh.so`. */
        GHOSTTY,
    }
}
