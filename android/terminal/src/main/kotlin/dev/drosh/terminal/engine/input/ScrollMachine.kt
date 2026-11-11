package dev.drosh.terminal.engine.input

/**
 * Where a scroll gesture lands, with no Android in it.
 *
 * ## The model
 *
 * Two numbers, exactly as Termux keeps them:
 *
 *  - **the row** the viewport shows, in the engine's row space, where row 0 is
 *    the oldest row of the scrollback and the largest row is the live edge;
 *  - **the shift**, in pixels, of the picture within the viewport. A positive
 *    shift moves the picture *down*, which opens the row above the viewport —
 *    the older one.
 *
 * The two are kept apart on purpose. A finger moves in pixels and the engine
 * moves in rows, and a machine that rounds the position to a row on every
 * event throws away the fraction in between: the picture jumps to the row
 * boundary and back, which is what a scroll that feels like a spring is.
 *
 * So the finger's travel goes into the shift, and only a *whole* row moves
 * the viewport — with the shift keeping the picture where the finger is. The
 * picture therefore never jumps: the shift stays below one row and the
 * viewport moves under it in whole steps.
 *
 * ## The live edge
 *
 * The engine's row space has its live edge at the largest row, and the engine
 * keeps the viewport on the active area when it is there — so new output
 * carries the view down with it. When the view is up in the scrollback the
 * engine holds the viewport on that row instead, and the view stays where the
 * user put it.
 */
class ScrollMachine {

    /**
     * Where a drag lands.
     *
     * @property row the row the viewport should show.
     * @property remainderPx the picture's shift within the viewport, in pixels.
     *   Positive moves the picture down, opening the row above.
     * @property position the row the picture's top is at, for the caller that
     *   wants to know where the view really is.
     * @property toBottom true when the view is on the live edge.
     */
    data class Target(
        val row: Int,
        val remainderPx: Float,
        val position: Float,
        val toBottom: Boolean,
    )

    /** The viewport's row, as this machine believes it to be. */
    private var row: Int = 0

    /** The picture's shift within the viewport, in pixels. */
    private var shiftPx: Float = 0f

    /** The row the engine was last asked for, to notice a move of its own. */
    private var askedRow: Int = -1

    /**
     * Move the viewport by a finger's travel.
     *
     * @param engineOffset where the engine's viewport is now, in its row space.
     * @param historyRows the live edge's row: the largest row there is.
     * @param distancePx finger travel. **Positive means the finger moved down**,
     *   which drags the picture down and opens the older row above it.
     * @param spacingPx the height of a row.
     */
    fun drag(
        engineOffset: Int,
        historyRows: Int,
        distancePx: Float,
        spacingPx: Float,
    ): Target {
        val bottom = historyRows.coerceAtLeast(0)
        val spacing = spacingPx.coerceAtLeast(1f)

        // The engine moved on its own: new output at the live edge carried the
        // view with it, or the terminal was resized. Its position is the truth,
        // and the shift belonged to the row we were on.
        if (askedRow >= 0 && engineOffset != askedRow) {
            row = engineOffset.coerceIn(0, bottom)
            shiftPx = 0f
        }

        var current = row.coerceIn(0, bottom)
        var shift = shiftPx

        // The finger's travel goes into the shift. A whole row moves the
        // viewport and the rest stays as the shift, so the picture does not
        // jump when the row does.
        shift += distancePx
        while (shift >= spacing) {
            if (current > 0) {
                current -= 1
                shift -= spacing
            } else {
                // There is nothing older than the first row.
                shift = 0f
                break
            }
        }
        while (shift <= -spacing) {
            if (current < bottom) {
                current += 1
                shift += spacing
            } else {
                // There is nothing newer than the live edge.
                shift = 0f
                break
            }
        }

        // The shift has to be legal for the row it belongs to: at the oldest
        // row it may not open a row above, and at the live edge it may not open
        // a row below.
        if (current <= 0) shift = minOf(shift, 0f)
        if (current >= bottom) shift = maxOf(shift, 0f)

        row = current
        shiftPx = shift
        askedRow = current

        return Target(
            row = current,
            remainderPx = shift,
            position = current - shift / spacing,
            toBottom = current >= bottom,
        )
    }

    /**
     * Forget the shift.
     *
     * The viewport was moved by something other than a drag — a new session, a
     * resize — so the picture starts again from the row the engine is at.
     */
    fun reset() {
        row = 0
        shiftPx = 0f
        askedRow = -1
    }
}
