package dev.drosh.domain.terminal

import kotlin.math.roundToInt

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
 *  - [splitFraction] is clamped to [MIN_SPLIT_FRACTION]..[MAX_SPLIT_FRACTION]
 *    and snapped to a multiple of [SPLIT_STEP], so neither pane can be dragged
 *    to zero height and become unrecoverable.
 *  - [floatingBounds] never escapes its container by more than
 *    [OVERSCAN], so a pane dragged to the edge still shows a grip to grab.
 *  - There is never a second pane without a session: [withSecondary] refuses
 *    a blank id and [cleared] is the only way back to no second pane.
 */
data class PaneLayout(
    /** Session shown in the second pane, or null when there is no split. */
    val secondarySessionId: String?,
    /**
     * Height of the top pane as a fraction of the total.
     *
     * A height, not a width: the split runs top-to-bottom. Side by side was the
     * first cut and it was wrong on a phone — two 180dp columns of terminal are
     * about ten characters wide, which is narrower than most paths, and the
     * wrapped output is unreadable in a way that a short-but-full-width pane is
     * not.
     *
     * Always a multiple of [SPLIT_STEP], so a stored value and a dragged one
     * describe the same set of positions.
     */
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
     * Moves the divider, snapping it to the nearest [SPLIT_STEP].
     *
     * Snapping rather than free positioning: on a phone a divider that stops
     * anywhere produces panes at heights nobody would have chosen, and the user
     * has to hold it there with a finger. Five positions is enough to cover any
     * useful arrangement and each one is reachable with one drag, which is what
     * makes the divider feel like a control rather than a slider.
     *
     * Clamped first, then snapped. Snapping first could round a value that is
     * past the limit up to a legal step, so a hard drag to the bottom would
     * land somewhere other than the bottom.
     */
    fun withSplitFraction(fraction: Float): PaneLayout {
        val clamped = fraction.coerceIn(MIN_SPLIT_FRACTION, MAX_SPLIT_FRACTION)
        val steps = (clamped / SPLIT_STEP).roundToInt()
        val snapped = (steps * SPLIT_STEP).coerceIn(MIN_SPLIT_FRACTION, MAX_SPLIT_FRACTION)
        return copy(splitFraction = snapped)
    }

    /** The split positions a drag can land on, smallest first. */
    fun splitSteps(): List<Float> = (MIN_SPLIT_STEPS..MAX_SPLIT_STEPS)
        .map { it * SPLIT_STEP }

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
         * One notch of the divider. Twenty percent, five times across.
         *
         * A fifth of the screen is the smallest step that still leaves a pane
         * tall enough to show a prompt and a few lines of output — which is the
         * whole use of the lower pane when you split a terminal, watching
         * something run while you work.
         */
        const val SPLIT_STEP = 0.2f

        const val MIN_SPLIT_STEPS = 1
        const val MAX_SPLIT_STEPS = 4

        const val MIN_SPLIT_FRACTION = 0.2f
        const val MAX_SPLIT_FRACTION = 0.8f

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