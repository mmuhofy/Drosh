package dev.drosh.terminal.engine.input

/**
 * Where a scroll gesture lands, with no Android in it.
 *
 * ## The model
 *
 * The picture is shifted within the viewport by a fraction of a row, and the
 * engine is moved by whole rows. The two are kept apart on purpose:
 *
 *  - **the shift**, in pixels, is how far the picture sits below the row it
 *    belongs to. A positive shift moves the picture *down*, which opens the row
 *    above the viewport — the older one;
 *  - **the delta**, in rows, is how many whole rows the engine moves. A
 *    negative delta goes into the history, a positive one toward the live edge.
 *
 * ## Why the engine is asked for a *delta*
 *
 * Asking for an absolute row means asking the engine to jump to a place, and
 * the engine answers with the place it could reach: at the ends of the
 * history it clamps, and a picture that was drawn for the row we asked for is
 * left showing the row we got. That disagreement is what a scroll that pulls
 * itself back looks like.
 *
 * A delta cannot disagree: the engine moves by that many rows or not at all,
 * and when it does not move, the viewport is against an edge and the shift is
 * dropped — the edge is the edge.
 *
 * ## The edges
 *
 * At the oldest row there is nothing above the viewport, so the shift is zero
 * there: the picture cannot be pulled down over a row that does not exist.
 * The same goes for a move the engine refused.
 */
class ScrollMachine {

    /**
     * Where a drag lands.
     *
     * @property delta how many whole rows the engine moves: negative into the
     *   history, positive toward the live edge.
     * @property offsetPx the picture's shift within the viewport, in pixels.
     *   Positive moves the picture down.
     * @property pinned whether the viewport is against an edge and cannot move
     *   further in the direction it was going.
     */
    data class Target(
        val delta: Int,
        val offsetPx: Float,
        val pinned: Boolean,
    )

    /**
     * The picture's shift within the viewport.
     *
     * Always between zero and one row: a whole row's worth of travel moves
     * the engine instead, and the shift keeps the picture where the finger
     * left it.
     */
    private var offsetPx: Float = 0f

    /**
     * Move the viewport by a finger's travel.
     *
     * @param distancePx finger travel. **Positive means the finger moved down**,
     *   which drags the picture down and opens the older row above it.
     * @param spacingPx the height of a row.
     */
    fun drag(distancePx: Float, spacingPx: Float): Target {
        // A row of no height is a row that cannot be crossed: the machine has
        // nothing to measure the travel against, so it does not move at all.
        if (spacingPx <= 0f) {
            return Target(delta = 0, offsetPx = offsetPx, pinned = false)
        }
        val spacing = spacingPx

        var offset = offsetPx + distancePx
        var delta = 0

        // A whole row's worth of travel moves the engine, and the rest stays
        // as the shift, so the picture does not jump when the row does.
        while (offset >= spacing) {
            offset -= spacing
            delta -= 1
        }
        while (offset < 0f) {
            offset += spacing
            delta += 1
        }

        offsetPx = offset
        return Target(delta = delta, offsetPx = offset, pinned = false)
    }

    /** The picture's shift within the viewport, in pixels. */
    fun shift(): Float = offsetPx

    /**
     * The engine refused the move.
     *
     * The viewport is against an edge: there is no row to move to, and the
     * shift that was left over cannot be shown.
     */
    fun pinned() {
        offsetPx = 0f
    }

    /**
     * The viewport is at the oldest row.
     *
     * There is nothing above it, so the picture cannot sit below the row it
     * belongs to.
     */
    fun atOldestRow() {
        offsetPx = 0f
    }

    /** Forget the shift: the picture starts again from a row boundary. */
    fun reset() {
        offsetPx = 0f
    }
}
