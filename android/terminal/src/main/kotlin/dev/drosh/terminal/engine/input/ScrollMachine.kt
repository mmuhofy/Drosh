package dev.drosh.terminal.engine.input

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Where the picture sits inside the viewport.
 *
 * ## The model
 *
 * The engine's viewport is a whole number of rows: it is the window the
 * engine draws, and it can only be placed on row boundaries. The picture,
 * though, can sit anywhere between two of them — that is what makes a scroll
 * smooth.
 *
 * So there are two numbers, and they are not independent:
 *
 *  - the **engine's row**, the row the engine's window starts at. The engine
 *    counts rows from the top of the scrollback, so the row grows as the
 *    picture moves down the output and shrinks as it moves up into the
 *    history;
 *  - the **shift**, how far the picture hangs above the engine's window, in
 *    pixels. It is always less than one row.
 *
 * The picture's top edge is therefore one number — the engine's row less the
 * shift — and it is that number the edges apply to.
 *
 * ## Why the position is clamped and not the shift
 *
 * The picture's top edge cannot go above the first row of the output and it
 * cannot go below the live edge. Clamping *that* is what makes the picture
 * sit exactly on the first and the last line: the first line flush at the
 * top, the newest flush at the bottom.
 *
 * Clamping the *shift* instead — the obvious thing to do — is the bug this
 * machine exists to not have. The shift would grow past the edge, the engine
 * would refuse the row the shift asked for, and the picture would snap back
 * to where it started: the scroll pulls itself back.
 *
 * Because the position at an edge is a whole row, the shift there is zero:
 * the picture is exactly on a row boundary and the output is whole.
 *
 * ## Why the picture settles when the finger lifts
 *
 * While a finger is on the screen the picture follows it exactly, so a line
 * of output can be half on the screen. When the finger lifts, the picture goes
 * onto the row it is nearest: the engine moves the one row that makes the
 * picture whole, and every line of output is a whole line again.
 */
class ScrollMachine {

    /**
     * Where a drag lands.
     *
     * @property delta how many whole rows the engine must move: negative into
     *   the history, a positive one toward the live edge.
     * @property offsetPx the picture's shift, in pixels: how far the picture
     *   hangs above the engine's window. Always between zero and one row.
     * @property pinned whether an edge stopped the picture.
     */
    data class Target(
        val delta: Int,
        val offsetPx: Float,
        val pinned: Boolean,
    )

    /**
     * The picture's shift, in pixels.
     *
     * Always between zero and one row: a whole row of travel moves the engine
     * instead, and the shift keeps the picture where the finger left it.
     */
    private var shiftPx: Float = 0f

    /**
     * Move the picture by a finger's travel.
     *
     * @param distancePx the finger's travel. **Positive means the finger moved
     *   down**, which drags the picture down and opens the older row above.
     * @param rowHeight the height of a row.
     * @param engineRow the row the engine's window starts at.
     * @param maxRow the last row the engine's window can start at: the live
     *   edge, where the newest line sits flush at the bottom.
     */
    fun drag(
        distancePx: Float,
        rowHeight: Float,
        engineRow: Int,
        maxRow: Int,
    ): Target {
        if (rowHeight <= 0f) {
            return Target(delta = 0, offsetPx = shiftPx, pinned = false)
        }

        // Where the picture's top edge is now: the engine's row, less the
        // fraction of a row it already hangs above it.
        val position = engineRow - shiftPx / rowHeight

        // The finger down moves the picture down, which moves its top edge up
        // the scrollable area: the older rows come into view.
        val wanted = position - distancePx / rowHeight

        // The edges stop the picture, and they stop it *at* the edge: the
        // first line of the output stays flush at the top and the newest one
        // flush at the bottom. The travel that would have gone past the edge
        // is dropped, and the picture does not move — it neither jumps nor
        // comes back.
        val landed = snap(wanted.coerceIn(0f, maxRow.toFloat()))
        val pinned = wanted != landed

        // The engine's window is the row the picture's top edge hangs into:
        // the row just below it. Asking for that row is what keeps the engine
        // and the picture describing the same picture.
        val row = ceil(landed).toInt()
        shiftPx = (row - landed) * rowHeight
        return Target(
            delta = row - engineRow,
            offsetPx = shiftPx,
            pinned = pinned,
        )
    }

    /**
     * Put the picture back on a row boundary.
     *
     * The shift belongs to a finger that is on the screen. When the finger
     * lifts — or the coast ends — the picture is left a fraction of a row off
     * the row it belongs to, and the last line of the output is cut in half.
     * This is the moment to settle: the picture goes onto the row it is
     * nearest, and the engine moves the one row that makes it fit.
     *
     * @param rowHeight the height of a row.
     * @param engineRow the row the engine's window starts at.
     * @param maxRow the last row the engine's window can start at.
     */
    fun settle(rowHeight: Float, engineRow: Int, maxRow: Int): Target {
        if (rowHeight <= 0f) {
            return Target(delta = 0, offsetPx = shiftPx, pinned = false)
        }
        // The picture's top edge, rounded to the row it is nearest.
        val position = engineRow - shiftPx / rowHeight
        val row = position.roundToInt().coerceIn(0, maxRow)
        shiftPx = 0f
        return Target(delta = row - engineRow, offsetPx = 0f, pinned = false)
    }

    /**
     * The picture's shift, in pixels.
     *
     * The renderer pushes the grid down by this, so the row above the engine's
     * window fills the top of the screen.
     */
    fun shift(): Float = shiftPx

    /**
     * The picture's top edge, in rows, given the engine's row.
     *
     * The position is derived rather than kept, so it can never disagree
     * with the engine: the engine's row is the fact both sides agree on, and
     * a position that disagreed with it would be a picture that is somewhere
     * the engine is not.
     */
    fun position(engineRow: Int, rowHeight: Float): Float =
        if (rowHeight <= 0f) engineRow.toFloat()
        else engineRow - shiftPx / rowHeight

    /**
     * The engine refused the move.
     *
     * The engine clamps at the ends of its scrollable area, and a viewport
     * that did not move leaves the picture a fraction of a row off from a
     * window that is not there. The picture goes to where the engine is: the
     * engine's row is the truth.
     */
    fun snapToEngine() {
        shiftPx = 0f
    }

    /**
     * The picture starts again from a row boundary.
     *
     * The engine's window has moved for a reason of its own — it jumped to a
     * prompt, or the grid changed shape — and the shift belonged to the grid
     * that is gone.
     */
    fun reset() {
        shiftPx = 0f
    }

    /**
     * Snap a position to a row boundary when it is one.
     *
     * A position that is a hair off a whole row — because the arithmetic that
     * produced it was not exact — would make the picture jump a whole row
     * when it crossed the boundary. The rounding is where that jump comes
     * from, so it happens here instead, where it is a rounding and not a
     * jump.
     */
    private fun snap(position: Float): Float {
        val nearest = position.roundToInt().toFloat()
        return if (kotlin.math.abs(position - nearest) < EPSILON) nearest else position
    }

    private companion object {
        /** How close to a row boundary counts as being on it. */
        const val EPSILON = 1e-4f
    }
}
