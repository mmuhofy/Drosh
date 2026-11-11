package dev.drosh.terminal.engine.input

/**
 * Where a scroll gesture lands, with no Android in it.
 *
 * ## What the engine gives us and what it does not
 *
 * The engine moves the viewport by whole rows: it is asked for a row, and it
 * shows that row at the top of the screen. The finger, meanwhile, moves in
 * pixels, and between two rows there is nothing in the engine to show.
 *
 * So a scroll has two parts, and they are kept apart:
 *
 *  - **the row** the viewport shows, in the engine's row space, where row 0 is
 *    the oldest row of the scrollback and the largest row is the live edge;
 *  - **the shift**, in pixels, of the picture within the viewport. A positive
 *    shift moves the picture *down*, which opens the row above the viewport —
 *    the older one, which the engine captured as overscan.
 *
 * ## Why they are not one number
 *
 * A finger moves in pixels and the engine moves in rows. A machine that turns
 * the finger's position into a row on every event throws the fraction away:
 * the picture snaps to the row boundary and back, over and over, and the
 * scroll feels like a spring.
 *
 * So the finger's travel goes into the shift, and only a *whole* row moves
 * the viewport — with the shift keeping the picture where the finger is. The
 * shift stays below one row and the viewport moves under it in whole steps,
 * so the picture never jumps.
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
     * Whether the machine has ever taken the engine's position.
     *
     * The first drag has to start somewhere, and the only honest place is where
     * the engine is. After that the machine is the truth until a gesture
     * begins, which is what `sync` is for.
     */
    private var synced = false

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

        // The engine's own position is *not* consulted while a gesture runs.
        //
        // It cannot be: the move the last event asked for reaches the engine a
        // frame later, so the engine is always one event behind, and reading it
        // as "the engine moved on its own" threw the shift away on every event.
        // That is the stutter — the picture jumped back to the row boundary and
        // started again, dozens of times a second.
        //
        // The machine's row is the truth during a gesture. Where the engine
        // actually is is settled once the gesture ends — see `sync`.
        //
        // The first drag is the exception: the machine has no position of its
        // own yet, so it takes the engine's.
        if (!synced) {
            row = engineOffset.coerceIn(0, bottom)
            shiftPx = 0f
            synced = true
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
     * Take the engine's position as the starting point.
     *
     * Called when a gesture begins and when it ends, which are the only two
     * moments the engine and the picture have to agree: the pending moves have
     * all landed by then, and whatever the engine did on its own — new output
     * at the live edge, a resize — is the position the user should be looking
     * at.
     */
    fun sync(engineOffset: Int, historyRows: Int) {
        val bottom = historyRows.coerceAtLeast(0)
        row = engineOffset.coerceIn(0, bottom)
        shiftPx = 0f
        askedRow = row
        synced = true
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
        synced = false
    }
}
