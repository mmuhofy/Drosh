package dev.drosh.terminal.engine.input

/**
 * The sub-line half of a scroll, with no Android in it.
 *
 * ## The model
 *
 * A terminal's scrollback is rows, but a finger moves in pixels. The old
 * terminal kept two numbers — whole rows, and a remainder smaller than one row —
 * and this is that pair plus the rules that keep them honest.
 *
 * The engine owns the rows: [topRow] changing is the caller's signal to move the
 * viewport there. The renderer owns the remainder: [offsetPx] shifts the grid
 * between two rows, which is what makes a drag follow the finger rather than
 * stepping a line at a time.
 *
 * ## Why one clamp
 *
 * The old code had the limits written out at the drag, and again at the wheel,
 * and again when new output pulled the viewport home. Two of the three were
 * wrong in the same way — they tested `topRow == 0` rather than `topRow >= 0` —
 * which let a drag push the grid past the live edge into an over-scroll the
 * finger could no longer undo. Every path goes through [legalOffset] here, so
 * there is one answer to "may the grid sit this far off".
 *
 * ## The ends
 *
 * The remainder is only legal where the row it reveals exists:
 *
 *   offset > 0  the grid moves **down**, opening the row above [topRow];
 *               legal only when `topRow > minTopRow`
 *   offset < 0  the grid moves **up**, opening the row below the last visible
 *               one; legal only when `topRow < 0`
 *
 * At the live edge (`topRow == 0`) a positive remainder is exactly the
 * over-scroll, so it becomes zero. At the far end of the history the same is
 * true of a negative one.
 */
class ScrollMachine {

    /** The rows above the live edge. Zero means the viewport is at the live edge. */
    var topRow: Int = 0
        private set

    /** The sub-line remainder, in pixels; always smaller than one row. */
    var offsetPx: Float = 0f
        private set

    /** Rows of history above the live edge, as a positive count. */
    var historyRows: Int = 0
        private set

    /** Declare how much history there is. Called when the engine's answer changes. */
    fun setHistory(rows: Int) {
        historyRows = rows.coerceAtLeast(0)
        topRow = topRow.coerceIn(-historyRows, 0)
        offsetPx = legalOffset(topRow, offsetPx)
    }

    /**
     * Move by a pixel delta.
     *
     * `distancePx` is finger travel: **positive means the finger moved down**,
     * which drags the content down and reveals older output. That is the
     * direction every terminal on the platform uses, and it is the one the
     * renderer's positive remainder already draws.
     *
     * Returns true when anything moved, so the caller can skip a redraw.
     */
    fun drag(distancePx: Float, spacingPx: Float): Boolean {
        if (spacingPx <= 0f) return false
        if (!distancePx.isFinite() || distancePx == 0f) return false

        val beforeRow = topRow
        val beforeOffset = offsetPx

        // A finger moving down pushes the grid down, which is what opening the
        // row above the top one looks like.
        var offset = offsetPx + distancePx
        var row = topRow
        val minRow = -historyRows

        // Carry whole rows out of the remainder, in either direction.
        while (offset >= spacingPx) {
            if (row > minRow) {
                row -= 1
                offset -= spacingPx
            } else {
                // At the oldest row there is nothing above to reveal.
                offset = 0f
                break
            }
        }
        while (offset <= -spacingPx) {
            if (row < 0) {
                row += 1
                offset += spacingPx
            } else {
                // At the live edge there is nothing below to reveal.
                offset = 0f
                break
            }
        }

        offset = legalOffset(row, offset)

        val moved = row != beforeRow || offset != beforeOffset
        topRow = row
        offsetPx = offset
        return moved
    }

    /**
     * Return to the live edge.
     *
     * What new output does, and what a "jump to bottom" does. The remainder goes
     * with it: a viewport at the live edge that is still shifted by a few pixels
     * is an over-scroll nobody asked for.
     */
    fun toLiveEdge() {
        topRow = 0
        offsetPx = 0f
    }

    /**
     * Clear the remainder without moving the rows.
     *
     * A wheel notch and a PageUp are whole-line requests: there is no sub-line
     * position to preserve across one. The row count is left alone because the
     * caller may be mid-line deliberately.
     */
    fun snapOffset() {
        offsetPx = 0f
    }

    /**
     * Restrict a remainder to one the current row can actually reveal.
     *
     * The one place that decides this; see the class comment for why it is not
     * written out at each call site.
     */
    internal fun legalOffset(row: Int, offset: Float): Float {
        val minRow = -historyRows
        var out = offset
        // At the live edge the grid may not sit pushed down: the row above it
        // does not exist.
        if (row >= 0) out = maxOf(out, 0f)
        // At the oldest row it may not sit pushed up: the row below is the last
        // one the history has.
        if (row <= minRow) out = minOf(out, 0f)
        return out
    }
}