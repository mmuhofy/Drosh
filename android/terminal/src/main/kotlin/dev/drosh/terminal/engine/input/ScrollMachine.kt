package dev.drosh.terminal.engine.input

/**
 * Where a scroll gesture lands, with no Android in it.
 *
 * ## The model
 *
 * A terminal's scrollback is rows, but a finger moves in pixels. The engine
 * owns the rows — it says where the viewport is, and it is the only thing that
 * knows — and the renderer owns the sub-line remainder: the grid is drawn
 * shifted by it, which is what makes a drag follow the finger instead of
 * stepping a line at a time.
 *
 * ## What is remembered, and what is not
 *
 * The engine's offset is asked for on every event and is never second-guessed:
 * it is the single source of truth for where the viewport is. What is
 * remembered is the *fraction* — where inside its row the content's top sits,
 * because the finger is rarely on a row boundary.
 *
 * An earlier version kept a row counter of its own and sent the engine an
 * absolute row every time. That is two answers to "where is the viewport", and
 * they drift: the engine clamps at the ends of the history, and a machine that
 * does not hear about the clamp keeps counting against a grid that stopped
 * moving. The drift shows as a scroll that springs back — the finger pulls the
 * grid up, the engine refuses, and the next event asks for the row the machine
 * believes in rather than the row the engine is at.
 *
 * Here the fraction is re-synced with the engine on every event: if the engine
 * moved on its own, the fraction is dropped and the picture starts from where
 * the engine actually is.
 *
 * ## The row space
 *
 * The engine's row space runs from the oldest row of the scrollback (0) to the
 * live edge (`historyRows`). The live edge is the largest number in it: the
 * content is *below* the live edge, so it is at the end of the space.
 *
 * A finger moving **down** drags the content down and reveals what is above
 * it, which is older output — so the first visible row gets *smaller*. A finger
 * moving up does the opposite.
 *
 * ## The remainder
 *
 * The row the engine shows is a whole row, but the finger is somewhere inside
 * one. The remainder is what is left over: the grid is shifted by it, so the
 * content sits where the finger is rather than where the row boundary is.
 *
 * Its sign follows the picture: a **positive** remainder moves the content
 * **down**, a negative one moves it up. The renderer subtracts it from the
 * clip's y, and clip y grows upward.
 */
class ScrollMachine {

    /**
     * Where a drag lands.
     *
     * @property row the row the engine should show first.
     * @property remainderPx how far the grid is shifted from that row, in
     *   pixels. Positive moves the content down.
     */
    data class Target(
        val row: Int,
        val remainderPx: Float,
    )

    /**
     * Where the content's top sits inside the engine's row, in rows.
     *
     * Negative when the content's top is above the row the engine shows.
     */
    private var fraction: Float = 0f

    /** The row the engine was last asked for, so a move of its own is noticed. */
    private var askedRow: Int = 0

    /**
     * Move the viewport by a finger's travel.
     *
     * @param engineOffset where the engine's viewport is now, in its row space.
     * @param historyRows the rows of history above the live edge. The live edge
     *   is at this row.
     * @param distancePx finger travel. **Positive means the finger moved down**,
     *   which drags the content down and reveals older output.
     * @param spacingPx the height of a row.
     */
    fun drag(
        engineOffset: Int,
        historyRows: Int,
        distancePx: Float,
        spacingPx: Float,
    ): Target {
        val history = historyRows.coerceAtLeast(0)
        val offset = engineOffset.coerceIn(0, history)

        // A row of no height is a surface that has not been measured. Nothing
        // can be moved by a distance measured against it.
        if (spacingPx <= 0f || !spacingPx.isFinite()) {
            askedRow = offset
            return Target(row = offset, remainderPx = 0f)
        }
        val spacing = spacingPx

        // The engine moved on its own — a program scrolled, or something else
        // asked for a row. The fraction belonged to the old row and is dropped:
        // the picture starts from where the engine actually is.
        if (offset != askedRow) {
            fraction = 0f
        }

        if (!distancePx.isFinite() || distancePx == 0f) {
            askedRow = offset
            return Target(row = offset, remainderPx = 0f)
        }

        // Where the content's top is, in the engine's row space.
        var position = offset + fraction

        // Down is a smaller row: the content moves down, so what is above it —
        // older output — comes into view.
        position = (position - distancePx / spacing).coerceIn(0f, history.toFloat())

        // The engine shows whole rows, so it gets the nearest one and the
        // fraction between them is drawn.
        val row = Math.round(position).toInt().coerceIn(0, history)

        // What is left over: the distance from the row the engine shows to the
        // row the finger asked for. Positive when the finger is below the row,
        // which moves the content down.
        val remainder = (row - position) * spacing

        fraction = position - row
        askedRow = row

        return Target(row = row, remainderPx = remainder)
    }

    /**
     * Forget the fraction.
     *
     * The viewport was moved by something other than a drag, so the picture
     * starts again from the row the engine is at.
     */
    fun reset() {
        fraction = 0f
        askedRow = 0
    }
}
