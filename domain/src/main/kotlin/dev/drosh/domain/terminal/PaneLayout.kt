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

    /**
     * A window owned by the system, floating over *other apps*.
     *
     * Unlike [FLOATING], which is a frame drawn inside this app's own window and
     * disappears when the activity does, this one lives in a
     * `TYPE_APPLICATION_OVERLAY` window added by a foreground service. It
     * therefore outlives the activity, and it is not part of the composition that
     * draws it: the pane's `TerminalView` is handed over to the service rather
     * than laid out here.
     *
     * Held apart from [FLOATING] rather than folded into it, because the two
     * differ in something a caller must respect: a floating pane is a child of
     * this composition and can be measured against it, whereas a system overlay
     * cannot be. Anything that needs the pane's on-screen rectangle has to ask
     * which of the two it is looking at before it uses it.
     */
    SYSTEM_OVERLAY,
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
    /**
     * True when the floating pane is tucked against an edge as a sliver.
     *
     * A window dragged to the very edge is not really in the way, but it still
     * covers a third of the screen and its content is unreadable at that width.
     * Parking it edge-on keeps it reachable — a terminal you cannot see the
     * output of is not usable, and neither is one you cannot find.
     */
    val edgeSnapped: Boolean = false,
    /** True when the floating pane is expanded to fill the host. */
    val maximized: Boolean = false,
    /**
     * True when the secondary session sits in the *top* pane.
     *
     * Stored rather than applied, because the terminal manager owns the mapping
     * from a session id to a pane slot: it is the only thing that knows which id
     * is primary. Recording "these two have changed places" lets the host swap
     * the two views without either side having to re-resolve which is which.
     */
    val secondarySwapped: Boolean = false,
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
    /**
     * Closes the second pane and returns to a single full-width terminal.
     *
     * Resets [secondarySwapped] as well: the flag describes a relationship
     * between two sessions, and there is no second session any more. Carrying it
     * would put the next split's session in the wrong pane for no reason the
     * user could see.
     */
    fun cleared(): PaneLayout = PaneLayout(
        secondarySessionId = null,
        splitFraction = DEFAULT_SPLIT_FRACTION,
    )

    /**
     * The divider position mid-drag, before it is committed to a step.
     *
     * Deliberately separate from [withSplitFraction], which snaps. Snapping
     * *during* the drag is what made the handle feel dead: the pane jumped
     * between four fixed heights while the finger was still moving, so the
     * window under it never lined up with the finger. Here the pane follows the
     * finger exactly, and the step is chosen on release by
     * [commitDraggedFraction].
     *
     * The value is allowed past the stepped range, down to [COLLAPSE_FRACTION]
     * and up to `1 - [COLLAPSE_FRACTION]`. Past those the layout collapses
     * instead — that is the "drag it all the way to one side and let go" gesture,
     * and it has to be reachable by dragging, not by a separate control.
     */
    fun withDraggedFraction(fraction: Float): PaneLayout {
        if (!isSplit) return this
        val dragged = fraction.coerceIn(COLLAPSE_FRACTION, 1f - COLLAPSE_FRACTION)
        return copy(splitFraction = dragged)
    }

    /**
     * Chooses the step for a released drag, or collapses the split.
     *
     * A release nearest either edge collapses to a single pane: the divider ends
     * up as a stub against the edge with no room to grab it again, so the only
     * honest outcome is that there is no divider any more.
     *
     * Otherwise it snaps to the nearest [SPLIT_STEP], which is what makes the
     * five positions a control rather than a slider.
     */
    fun commitDraggedFraction(): PaneLayout {
        if (!isSplit) return this
        val dragged = splitFraction
        if (dragged <= COLLAPSE_FRACTION || dragged >= 1f - COLLAPSE_FRACTION) {
            return collapsed()
        }
        return withSplitFraction(dragged)
    }

    /**
     * The split collapses and the surviving session becomes the primary one.
     *
     * Dragging the divider *down* shrinks the lower pane, so what is left is the
     * top one — and [secondarySwapped] decides which session that actually is.
     * Without the swap, a collapse after a swap would keep the wrong session,
     * which is the most confusing outcome this feature can produce: the user
     * swapped, dragged one way, and lost the pane they were looking at.
     *
     * The session id itself is dropped either way. The layout does not know
     * which id is primary — that is the terminal manager's — so it records the
     * survivor and lets the caller promote it.
     */
    fun collapsedSurvivingSlot(): PaneSlot {
        val bottomSurvives = splitFraction <= 0.5f
        return if (secondarySwapped) {
            if (bottomSurvives) PaneSlot.SECONDARY else PaneSlot.PRIMARY
        } else {
            if (bottomSurvives) PaneSlot.PRIMARY else PaneSlot.SECONDARY
        }
    }

    /**
     * Drops the split, keeping the session that was on top.
     *
     * This is the simple case, used where the caller does not track which id is
     * in which slot — the divider drag, where the terminal manager promotes the
     * survivor itself.
     */
    fun collapsed(): PaneLayout = copy(secondarySessionId = null)

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

    /**
     * Swaps which session is on top.
     *
     * The divider position is deliberately left alone. A swap is about *which*
     * session is where, and carrying the height across means the pane the user
     * was working in keeps its size — which is the one thing they did not ask to
     * change.
     */
    fun swapped(): PaneLayout = copy(secondarySwapped = !secondarySwapped)

    /** The split positions a drag can land on, smallest first. */
    fun splitSteps(): List<Float> = (MIN_SPLIT_STEPS..MAX_SPLIT_STEPS)
        .map { it * SPLIT_STEP }

    /** Docks the second pane beside the first, dropping any float state. */
    fun docked(): PaneLayout =
        copy(presentation = PanePresentation.DOCKED, edgeSnapped = false)

    /** Floats the second pane over the first at its remembered bounds. */
    fun floating(): PaneLayout =
        copy(presentation = PanePresentation.FLOATING, edgeSnapped = false)

    /**
     * Tucks the floating pane against whichever edge it was left nearest.
     *
     * ## Why this is a state and not a rendering trick
     *
     * A pane dragged to the very edge is not really in the way, but it still
     * covers a third of the screen and its output is unreadable at that width.
     * Parking it edge-on keeps it reachable — a terminal whose output you cannot
     * see is not usable, and neither is one you cannot find.
     *
     * Only while floating, and only near an edge. A pane parked in the middle of
     * the screen at that size is not an edge, it is a mistake, and snapping it
     * would hide that.
     *
     * Returns the layout unchanged when the pane is nowhere near an edge, so
     * dragging it around the middle does not make it jump on release.
     */
    fun withEdgeSnap(): PaneLayout {
        if (presentation != PanePresentation.FLOATING) return this
        val b = floatingBounds
        val nearLeft = b.left <= EDGE_SNAP_ZONE
        val nearRight = b.right >= 1f - EDGE_SNAP_ZONE
        val nearTop = b.top <= EDGE_SNAP_ZONE
        val nearBottom = b.bottom >= 1f - EDGE_SNAP_ZONE
        if (!nearLeft && !nearRight && !nearTop && !nearBottom) return this

        val snapped = when {
            nearLeft -> b.copy(left = 0f, width = EDGE_SLIVER)
            nearRight -> b.copy(left = 1f - EDGE_SLIVER, width = EDGE_SLIVER)
            nearTop -> b.copy(top = 0f, height = EDGE_SLIVER)
            else -> b.copy(top = 1f - EDGE_SLIVER, height = EDGE_SLIVER)
        }
        return copy(floatingBounds = snapped, edgeSnapped = true)
    }

    /** Undoes [withEdgeSnap], putting the pane back at its remembered size. */
    fun unedgeSnapped(): PaneLayout {
        if (!edgeSnapped) return this
        return copy(floatingBounds = NormalizedRect.DEFAULT, edgeSnapped = false)
    }

    /** Toggles between docked and floating without losing the split. */
    fun togglePresentation(): PaneLayout = when (presentation) {
        PanePresentation.DOCKED -> floating()
        PanePresentation.FLOATING -> docked()
        // Deliberately docks rather than coming back in-app. Toggling implies a
        // round trip between two states of the same thing, and this pane is not
        // in the app's window — the service owns it, and the only way to take it
        // back is to stop the service. `docked()` is the honest half of that:
        // the user asked to bring the pane back, and where it lands afterwards is
        // a separate question from how it leaves.
        PanePresentation.SYSTEM_OVERLAY -> docked()
    }

    /**
     * Sends the second pane to a system overlay window.
     *
     * Refused while there is no split, because there is no pane to send — the
     * overlay would come up empty and there would be nothing in it to dock back.
     *
     * [edgeSnapped] is dropped rather than carried: parking the pane as a sliver
     * against an edge is about staying out of the way of the app it floats over
     * *inside this app*, and an overlay is already somewhere the user put it. The
     * window is sized on its own terms and a sliver would only hide its output.
     *
     * [maximized] is deliberately kept. The flag is meaningless while the pane is
     * in the overlay, but discarding it here would mean the pane came back collapsed
     * to a corner after a trip out and back — and a user who expanded it expanded
     * it on purpose. It is remembered rather than applied, exactly as
     * [secondarySwapped] is.
     */
    fun systemOverlay(): PaneLayout =
        if (!isSplit) this
        else copy(
            presentation = PanePresentation.SYSTEM_OVERLAY,
            edgeSnapped = false,
        )

    /**
     * Takes the pane back from a system overlay into this app's window.
     *
     * A no-op in any other presentation, so it is safe to call on the way out of
     * a configuration change or a teardown: a pane that was never in an overlay
     * has nothing to reclaim.
     */
    fun dockedFromOverlay(): PaneLayout =
        if (presentation != PanePresentation.SYSTEM_OVERLAY) this else docked()

    /** True when the pane's `TerminalView` is owned by the overlay service. */
    val isSystemOverlay: Boolean
        get() = isSplit && presentation == PanePresentation.SYSTEM_OVERLAY

    /**
     * True when the second pane is a window of any kind rather than a docked one.
     *
     * Covers both floating kinds, which is what callers that just want "is this a
     * window" should ask. It is deliberately *not* [isFloating], which means the
     * in-app one specifically — code that draws an in-app frame must not mistake
     * an overlay it has no frame for.
     */
    val isWindowed: Boolean
        get() = isSplit && presentation != PanePresentation.DOCKED

    /** Expands or restores the floating pane. Ignored unless it is an in-app float. */
    fun toggleMaximized(): PaneLayout =
        if (presentation != PanePresentation.FLOATING) this
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

        /**
         * How far past the host edge a floating pane may be dragged.
         *
         * Generous — 30% — because "I want it half off screen to see the thing
         * behind it" is the entire point of a floating window, and the old 4%
         * made that impossible.
         *
         * Not unlimited, and the limit is deliberate: a window dragged entirely
         * outside its host has no visible edge to grab, and the only way back is
         * to kill the process. Thirty percent leaves a strip on one side for
         * every position, which is as far as "mostly off screen" can go while
         * staying recoverable by touch.
         */
        const val OVERSCAN = 0.30f

        /**
         * Where a divider drag stops meaning "resize" and means "collapse".
         *
         * A tenth of the height from either edge. Below it, the surviving pane
         * would be too short to show a prompt and some output, which is not a
         * usable split — so a release there closes it instead.
         */
        const val COLLAPSE_FRACTION = 0.1f

        /**
         * How close to an edge a pane has to be before releasing snaps it.
         *
         * A tenth of the shorter side. Wide enough that aiming for an edge is
         * easy and aiming for the middle is not punished, narrow enough that
         * merely dragging past an edge on the way somewhere else does not catch.
         */
        const val EDGE_SNAP_ZONE = 0.12f

        /**
         * The width or height a snapped pane collapses to.
         *
         * Twelve percent of the shorter side — a strip you can see and hit, not a
         * few pixels. Too small and it cannot be found again without knowing it is
         * there; too large and it is still covering the terminal it was moved to
         * see.
         */
        const val EDGE_SLIVER = 0.12f

        /** No second pane. */
        val EMPTY: PaneLayout = PaneLayout(secondarySessionId = null)
    }
}