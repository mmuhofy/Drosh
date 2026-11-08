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
 * A pinch never reaches this machine. It changes the glyph size, not the grid;
 * when the fingers lift, the new font is committed as a cell change and the
 * machine reflows once. That is the difference between a zoom that is
 * continuous and one that steps from grid shape to grid shape.
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

    /** True once the font has measured the cell — nothing worth drawing yet. */
    val cellReady: Boolean
        get() = hasCell

    /** The state, for a diagnostic surface. */
    val state: State get() = current

    /** The size the renderer should draw at, whatever state it is in. */
    val size: SizeInfo
        get() = when (val state = current) {
            is State.Idle -> state.size
            is State.Changing -> state.size
            is State.Settled -> state.size
        }

    /** The grid shape currently in force. */
    val appliedColumns: Int
        get() = when (val state = current) {
            is State.Idle -> state.size.columns
            is State.Changing -> state.appliedColumns
            is State.Settled -> state.appliedColumns
        }

    val appliedRows: Int
        get() = when (val state = current) {
            is State.Idle -> state.size.rows
            is State.Changing -> state.appliedRows
            is State.Settled -> state.appliedRows
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
        if (!hasCell) return size

        val next = size.withSurface(width, height)
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
     * A live pinch. Only the glyph size changes; the grid does not move.
     *
     * Returns the size the caller should draw with, which is the current one
     * with the scale applied.
     */
    fun onPinch(scale: Float, focusX: Float, focusY: Float): SizeInfo {
        val safe = if (scale.isFinite() && scale > 0f) scale else 1f
        val next = size.copy(pinchScale = safe, pinchFocusX = focusX, pinchFocusY = focusY)
        current = when (val state = current) {
            is State.Idle -> State.Idle(next)
            is State.Changing -> state.copy(size = next)
            is State.Settled -> state.copy(size = next)
        }
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
        val state = current as? State.Changing ?: return null
        if (now - state.lastChangeAt < settleMillis) return null

        val columns = state.size.columns
        val rows = state.size.rows
        current = State.Settled(
            size = state.size,
            appliedColumns = columns,
            appliedRows = rows,
        )

        if (columns == state.appliedColumns && rows == state.appliedRows) {
            return null
        }
        return GridChange(columns = columns, rows = rows, size = state.size)
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
