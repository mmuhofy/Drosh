package dev.drosh.terminal.engine.layout

/**
 * The one owner of the terminal's shape.
 *
 * ## The problem it exists to end
 *
 * Four callers used to write the renderer's size: the surface callback, the
 * font-size setter, the pinch gesture, and the Compose inset. Each write was
 * correct on its own; none of them were correct together. A frame could be
 * built from a surface of one size and a cell of another, which is the text
 * drawn inside itself while a keyboard animates.
 *
 * This machine is the only thing that decides a grid shape, and it decides it
 * from three inputs: the surface, the cell, and whether the surface has
 * stopped moving.
 *
 * ## The states
 *
 * ```
 * Idle ──surface/font changes──► Changing ──quiet for SETTLE_MS──► Settled
 *   ▲                                 │                              │
 *   └─────────── reflow applied ──────┴──────────────────────────────┘
 * ```
 *
 * A keyboard rising is sixty surface changes in three hundred milliseconds.
 * Each one would reflow every row of the engine's grid, which is what made the
 * terminal stutter and flash while the keyboard animated. So the *drawing*
 * follows the surface immediately — the step comes from the surface and the
 * rows from the grid, so the picture is stretched rather than wrong — and the
 * *grid* is laid out once, when the surface stops.
 *
 * ## Pinch
 *
 * A pinch reaches this machine as a *cell change*: the fingers change the
 * font's size, the font's size is the cell, and the grid is laid out again.
 * There is no separate path for the gesture and no scale in the projection,
 * because a zoom that magnifies the picture is not a zoom of a terminal.
 *
 * ## Why it is testable
 *
 * No Android import. The clock is passed in, the states are data, and the
 * transitions are pure. A layout bug that used to be found on a device by
 * opening and closing a keyboard is found here by advancing a number.
 */
class LayoutMachine(
    /** How long the surface must be still before the grid reflows. */
    private val settleMillis: Long = SETTLE_MILLIS,
) {

    /** What the machine is doing right now. */
    sealed interface State {
        /** Nothing has changed; the grid matches the surface. */
        data class Idle(val size: SizeInfo) : State

        /** The surface is moving; the grid is still the old shape. */
        data class Changing(
            /** The surface and cell as they are now, for drawing. */
            val size: SizeInfo,
            /** The grid shape still in force. */
            val appliedColumns: Int,
            val appliedRows: Int,
            /** When the surface was last seen moving. */
            val lastChangeAt: Long,
        ) : State

        /** The surface has stopped; the grid needs laying out. */
        data class Settled(
            val size: SizeInfo,
            val appliedColumns: Int,
            val appliedRows: Int,
        ) : State

        /**
         * A pinch is running: the cell has changed and the grid has not.
         *
         * The text grows and runs off the edge of the screen, and nothing is
         * reflowed until the fingers lift. A reflow on every step of the
         * gesture is what makes a zoom stutter: the grid is re-laid out, the
         * engine is resized, the shell redraws, and the next step does it
         * again.
         */
        data class Zooming(
            val size: SizeInfo,
            val appliedColumns: Int,
            val appliedRows: Int,
        ) : State
    }

    private var current: State = State.Idle(SizeInfo())

    /**
     * Whether a cell has ever been measured.
     *
     * A surface can arrive before the font has: the view is created and laid out
     * before the font stack has answered. A grid computed against the default
     * one-pixel cell is a grid of a thousand columns, and the reflow that
     * followed would allocate a frame buffer for it. Until a real cell exists,
     * the surface is recorded but not laid out.
     */
    private var hasCell: Boolean = false

    /**
     * True when there is something worth drawing: a measured cell and a surface
     * with a size.
     *
     * The render loop checks this before drawing. Without it, a frame can be
     * built while the surface is still 0×0 — and a 0×0 surface used to produce
     * a two-cell grid, which is the black square that used to appear at the
     * origin before the terminal had drawn anything.
     */
    val cellReady: Boolean
        get() = hasCell && size.width > 0 && size.height > 0

    /** The state, for a diagnostic surface. */
    val state: State get() = current

    /** The size the renderer should draw at, whatever state it is in. */
    val size: SizeInfo
        get() = when (val state = current) {
            is State.Idle -> state.size
            is State.Changing -> state.size
            is State.Settled -> state.size
            is State.Zooming -> state.size
        }

    /** The grid shape currently in force. */
    val appliedColumns: Int
        get() = when (val state = current) {
            // Nothing has been applied yet. The idle state means the surface
            // is not laid out, so there is no grid to have applied.
            is State.Idle -> 0
            is State.Changing -> state.appliedColumns
            is State.Settled -> state.appliedColumns
            is State.Zooming -> state.appliedColumns
        }

    val appliedRows: Int
        get() = when (val state = current) {
            is State.Idle -> 0
            is State.Changing -> state.appliedRows
            is State.Settled -> state.appliedRows
            is State.Zooming -> state.appliedRows
        }

    /**
     * The surface changed.
     *
     * The size is recorded at once — drawing uses it immediately — and the grid
     * is deferred. Returns the size the caller should draw with.
     */
    fun onSurface(width: Int, height: Int, now: Long): SizeInfo {
        // A surface that reports no size is a surface that has not been laid
        // out yet. Recording it would compute a grid of two columns, and the
        // reflow that followed would resize the session and throw its contents
        // away.
        if (width <= 0 || height <= 0) return size

        val next = size.withSurface(width, height)

        // The surface is recorded even before there is a cell to measure it
        // against — the cell comes later, and the surface it belongs to is
        // the one that was there when it arrived.
        //
        // Dropping it meant that a cell measured after the surface arrived
        // laid the grid out against a surface of no size at all: no columns,
        // no rows, and a grid that never matched the window.
        if (!hasCell) {
            current = State.Idle(next)
            return next
        }

        current = State.Changing(
            size = next,
            appliedColumns = appliedColumns,
            appliedRows = appliedRows,
            lastChangeAt = now,
        )
        return next
    }

    /**
     * The font's cell changed.
     *
     * A committed size — a pinch that ended, or a setting — so the grid is laid
     * out immediately rather than waiting: the user asked for this shape and
     * there is no animation to wait out.
     */
    fun onCell(cellWidth: Float, cellHeight: Float, now: Long): SizeInfo {
        if (cellWidth <= 0f || cellHeight <= 0f) return size
        hasCell = true
        val next = size.withCell(cellWidth, cellHeight)
        current = State.Settled(
            size = next,
            appliedColumns = appliedColumns,
            appliedRows = appliedRows,
        )
        return next
    }

    /**
     * A live pinch: the cell changed, the grid did not.
     *
     * The text is bigger from this moment and the grid keeps its shape, so
     * the picture runs off the edge of the screen. Nothing is reflowed until
     * the gesture ends and [onCell] commits the size.
     */
    fun onZoomCell(cellWidth: Float, cellHeight: Float, now: Long): SizeInfo {
        if (cellWidth <= 0f || cellHeight <= 0f) return size
        hasCell = true
        val next = size.withZoomCell(cellWidth, cellHeight)
        current = State.Zooming(
            size = next,
            appliedColumns = appliedColumns,
            appliedRows = appliedRows,
        )
        return next
    }

    /**
     * The sub-line scroll remainder moved.
     *
     * Drawing only: the grid does not change, so this cannot trigger a reflow.
     */
    fun onScrollOffset(pixels: Float): SizeInfo {
        val next = size.copy(scrollOffsetPx = pixels)
        current = when (val state = current) {
            is State.Idle -> State.Idle(next)
            is State.Changing -> state.copy(size = next)
            is State.Settled -> state.copy(size = next)
            is State.Zooming -> state.copy(size = next)
        }
        return next
    }

    /**
     * Give the machine a chance to settle. Called every frame.
     *
     * Returns the grid shape to apply, or null when there is nothing to do.
     * A non-null result means the caller must resize the session *before*
     * drawing this frame, or the cells and their buffer disagree.
     */
    fun poll(now: Long): GridChange? {
        val state = current

        // A pinch changes the cell and not the grid: the text grows and runs
        // off the edge of the screen, and nothing is reflowed until the
        // gesture ends. A change reported here would resize the engine on
        // every step of the gesture, which is the stutter the pinch is not.
        if (state is State.Zooming) return null

        if (state is State.Changing) {
            // The surface is still moving: the grid waits.
            if (now - state.lastChangeAt < settleMillis) return null
            current = State.Settled(
                size = state.size,
                appliedColumns = state.appliedColumns,
                appliedRows = state.appliedRows,
            )
        }

        // The size implies a shape, and the shape the engine has is the one it
        // was last told about. When they differ, the caller has to resize the
        // engine — and that is true for a *cell* change as much as for a
        // surface one: a committed font size is a new grid, and the grid has
        // to be laid out for it.
        //
        // This used to report a change only while the surface was moving,
        // which meant that a cell change — a font size, a pinch that ended —
        // was never reported at all, and the grid kept the shape it had.
        val current = size
        val columns = current.columns
        val rows = current.rows
        if (columns == appliedColumns && rows == appliedRows) {
            return null
        }
        return GridChange(columns = columns, rows = rows, size = current)
    }

    /**
     * The caller applied a grid change. The machine records it so it does not
     * ask twice.
     */
    fun onGridApplied(columns: Int, rows: Int) {
        current = when (val state = current) {
            is State.Idle -> State.Idle(state.size)
            is State.Changing -> state.copy(appliedColumns = columns, appliedRows = rows)
            is State.Settled -> State.Settled(
                size = state.size,
                appliedColumns = columns,
                appliedRows = rows,
            )
            is State.Zooming -> state.copy(appliedColumns = columns, appliedRows = rows)
        }
    }

    /** True while a surface change is waiting for the grid to catch up. */
    val settlePending: Boolean
        get() = current is State.Changing

    /** A grid shape to apply to the session. */
    data class GridChange(val columns: Int, val rows: Int, val size: SizeInfo)

    companion object {
        /**
         * How long the surface must be still before the grid reflows.
         *
         * Long enough to cover two frames of a slide, short enough that a
         * settled surface is laid out before the user can see it.
         */
        const val SETTLE_MILLIS = 48L
    }
}
