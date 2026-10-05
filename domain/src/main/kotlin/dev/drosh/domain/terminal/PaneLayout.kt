package dev.drosh.domain.terminal

/**
 * A rectangle expressed as fractions of its container, all in 0f..1f.
 *
 * Floating panes are persisted, and a phone rotates, so the geometry cannot be
 * in pixels: a pane saved at 300dp wide would be a different size — and a
 * different fraction of the screen — after the process restarts on a different
 * device or in the other orientation. Fractions are the only representation
 * that stays meaningful across both.
 *
 * `left`/`top` are the top-left corner, `width`/`height` the size, both as
 * fractions of the container's own width/height respectively.
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    companion object {
        /**
         * Default floating pane: 92% wide, 70% tall, centred, with a small
         * inset so its edges and the resize grip stay reachable rather than
         * flush against the screen.
         */
        val DEFAULT: NormalizedRect = NormalizedRect(
            left = 0.04f,
            top = 0.12f,
            width = 0.92f,
            height = 0.70f,
        )
    }
}

/** How the second pane sits relative to the first. */
enum class PanePresentation {
    /** Side by side, separated by a draggable divider. */
    DOCKED,

    /** A window floating over the first pane, movable and resizable. */
    FLOATING,
}

/**
 * The split layout: which session is in the second pane, how wide it is, and
 * whether it is docked beside the primary or floating over it.
 *
 * Pure Kotlin — no Android, no Compose. The layout is state, and state belongs
 * in `:domain` so the persistence layer and the UI cannot disagree about its
 * shape.
 *
 * Invariants, all enforced by the constructors below rather than by callers:
 *  - [splitFraction] is clamped to [MIN_SPLIT_FRACTION]..[MAX_SPLIT_FRACTION],
 *    so neither pane can be dragged to zero width and become unrecoverable.
 *  - [floatingBounds] never escapes its container by more than
 *    [OVERSCAN], so a pane dragged to the edge still shows a grip to grab.
 *  - There is never a second pane without a session: [withSecondary] refuses
 *    a blank id and [cleared] is the only way back to no second pane.
 */
data class PaneLayout(
    /** Session shown in the second pane, or null when there is no split. */
    val secondarySessionId: String?,
    /** Width of the primary pane as a fraction of the total, 0.15..0.85. */
    val splitFraction: Float = DEFAULT_SPLIT_FRACTION,
    val presentation: PanePresentation = PanePresentation.DOCKED,
    val floatingBounds: NormalizedRect = NormalizedRect.DEFAULT,
    /** True when the floating pane is expanded to fill the host. */
    val maximized: Boolean = false,
) {

    /** True when a second pane is open. */
    val isSplit: Boolean get() = secondarySessionId != null

    val isFloating: Boolean
        get() = isSplit && presentation == PanePresentation.FLOATING

    /**
     * Opens [sessionId] in the second pane.
     *
     * A blank id is refused rather than stored: it would render as an empty
     * pane with no way to tell it apart from a session that failed to spawn.
     * Re-opening a session that is already in the second pane is a no-op, so a
     * dropped session does not reset the divider the user had positioned.
     */
    fun withSecondary(sessionId: String): PaneLayout {
        if (sessionId.isBlank()) return this
        if (secondarySessionId == sessionId) return this
        return copy(secondarySessionId = sessionId)
    }

    /** Closes the second pane and returns to a single full-width terminal. */
    fun cleared(): PaneLayout = PaneLayout(
        secondarySessionId = null,
        splitFraction = DEFAULT_SPLIT_FRACTION,
    )

    /**
     * Moves the divider. The fraction is clamped rather than rejected, so a
     * drag that runs past the end of the screen stops at the limit instead of
     * snapping back.
     */
    fun withSplitFraction(fraction: Float): PaneLayout =
        copy(splitFraction = fraction.coerceIn(MIN_SPLIT_FRACTION, MAX_SPLIT_FRACTION))

    /** Docks the second pane beside the first, dropping any float state. */
    fun docked(): PaneLayout =
        copy(presentation = PanePresentation.DOCKED, maximized = false)

    /** Floats the second pane over the first at its remembered bounds. */
    fun floating(): PaneLayout =
        copy(presentation = PanePresentation.FLOATING, maximized = false)

    /** Toggles between docked and floating without losing the split. */
    fun togglePresentation(): PaneLayout = when (presentation) {
        PanePresentation.DOCKED -> floating()
        PanePresentation.FLOATING -> docked()
    }

    /** Expands or restores the floating pane. Ignored while docked. */
    fun toggleMaximized(): PaneLayout =
        if (presentation == PanePresentation.DOCKED) this
        else copy(maximized = !maximized)

    /**
     * Moves the floating pane, keeping as much of it on screen as possible.
     *
     * Clamped to [-OVERSCAN, 1] on the origin and to the same on the far edge,
     * so a pane can be pushed most of the way off but not entirely — a pane
     * fully outside the host has no visible edge to drag back in.
     */
    fun withFloatingBounds(bounds: NormalizedRect): PaneLayout {
        if (presentation != PanePresentation.FLOATING) return this
        val width = bounds.width.coerceIn(MIN_FLOAT_WIDTH, MAX_FLOAT_WIDTH)
        val height = bounds.height.coerceIn(MIN_FLOAT_HEIGHT, MAX_FLOAT_HEIGHT)
        val left = bounds.left.coerceIn(-OVERSCAN, 1f + OVERSCAN - width)
        val top = bounds.top.coerceIn(-OVERSCAN, 1f + OVERSCAN - height)
        return copy(floatingBounds = NormalizedRect(left, top, width, height))
    }

    /**
     * Drops the second pane if [liveSessionIds] no longer contains it.
     *
     * A pane outliving its session is the normal end of this feature rather
     * than an error: the user closed the session, or it exited on its own.
     * Reconciling here means the UI never has to render a pane bound to a
     * session that is gone, and the same check runs for the persisted id on
     * launch, where the session may not have been restored yet.
     */
    fun reconciledAgainst(liveSessionIds: Set<String>): PaneLayout {
        val secondary = secondarySessionId ?: return this
        if (secondary in liveSessionIds) return this
        return cleared()
    }

    companion object {
        const val DEFAULT_SPLIT_FRACTION = 0.5f

        /**
         * Neither pane may be narrower than this.
         *
         * Below roughly a sixth of a phone's width a terminal fits about ten
         * characters, which is narrower than most paths — the pane stops being
         * readable and the user has no way back, because the divider has
         * scrolled off with the pane it was attached to.
         */
        const val MIN_SPLIT_FRACTION = 0.15f
        const val MAX_SPLIT_FRACTION = 0.85f

        const val MIN_FLOAT_WIDTH = 0.4f
        const val MAX_FLOAT_WIDTH = 1.0f
        const val MIN_FLOAT_HEIGHT = 0.25f
        const val MAX_FLOAT_HEIGHT = 1.0f

        /** How far past the host edge a floating pane may be dragged. */
        const val OVERSCAN = 0.04f

        /** No second pane. */
        val EMPTY: PaneLayout = PaneLayout(secondarySessionId = null)
    }
}