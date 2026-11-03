package dev.drosh.terminal.engine.render

/**
 * Everything the renderer knows about the size it is drawing at, in one value.
 *
 * ## Why one value
 *
 * alacritty's `SizeInfo` (`alacritty/src/display/mod.rs:145`) exists for this
 * reason, and it is the reason the terminal it draws never has a strip of
 * nothing at the edge or a frame drawn at the wrong shape: the surface, the
 * cell and the grid are three descriptions of one fact, and keeping them in
 * three fields lets them disagree.
 *
 * They did disagree here. `width`, `height`, `columns`, `rows`, `cellStepX`
 * and `cellStepY` were six independent fields written from four places, and
 * every screen bug of the last weeks was one of them being one update behind:
 *
 * - a step computed from the new surface over the old cell count — the dead
 *   strip at the right and bottom edges;
 * - a grid laid out for one size while the session held another — the frames
 *   that refused to draw, which is the black flash when the keyboard rose;
 * - a pinch that changed the step and the grid in different frames — the zoom
 *   that stepped instead of flowing.
 *
 * Here `columns`, `rows`, `cellStepX` and `cellStepY` are **derived** from the
 * surface and the cell, so they cannot be stale: ask for the grid and you get
 * the grid of the size you are holding.
 *
 * ## The step is not the cell
 *
 * [cellWidth] is what the font measured — the size a glyph mask is rasterised
 * at. [cellStepX] is how far apart cells are *placed*: the surface divided by
 * the number of cells that fit in it. They differ by less than a pixel, and
 * that difference is what fills the surface exactly instead of leaving the
 * remainder as a dead strip.
 *
 * ## The pinch lives here too
 *
 * [pinchScale] is the live gesture, and it is **not** folded into [stepX] or
 * [stepY]. A pinch changes the size of the *text*, not of the grid: the cells
 * stay where they are and the glyphs inside them grow. Scaling the step as
 * well would change how many columns fit, which is a different terminal rather
 * than a bigger one — the whole screen would zoom like an image, and the text
 * would stop lining up with the cursor.
 */
internal data class SizeInfo(
    /** The surface, in pixels. */
    val width: Int,
    val height: Int,
    /** The cell the font measured, in pixels. */
    val cellWidth: Float,
    val cellHeight: Float,
    /** The live pinch, 1 when there is none. */
    val pinchScale: Float = 1f,
    /** Where the pinch is anchored, in surface pixels. */
    val pinchFocusX: Float = 0f,
    val pinchFocusY: Float = 0f,
    /**
     * The sub-line part of the scroll position, in pixels.
     *
     * The whole grid is drawn shifted by this, so a drag follows the finger
     * between rows instead of stepping a line at a time. The snapshot carries
     * one extra line above the viewport — its first line — and that line fills
     * the strip the shift reveals. Without it the shift exposed a band of
     * unpainted cells at the top, which is why this was removed once before:
     * the shift was there, the line was not.
     */
    val scrollOffsetPx: Float = 0f,
) {
    /** Columns that fit: a whole number of cells, rounded down. */
    val columns: Int get() = maxOf((width / cellWidth).toInt(), MIN_COLUMNS)

    /** Rows that fit. */
    val rows: Int get() = maxOf((height / cellHeight).toInt(), MIN_ROWS)

    /**
     * How far apart cells are placed, in pixels.
     *
     * The surface divided by the cells that fit in it, so the grid ends exactly
     * on the edge. The pinch is deliberately absent: see the class comment.
     */
    val stepX: Float
        get() = width.toFloat() / columns

    val stepY: Float
        get() = height.toFloat() / rows

    /**
     * The projection, as `(x0, y0, sx, sy)` for `clip = xy + zw * pixel`.
     *
     * A plain pixel-to-clip map, with no scale in it at all. The pinch lives in
     * the glyph size the shader applies, not here: scaling the projection would
     * zoom the whole surface — grid, cursor, everything — which is an image
     * zoom rather than a text size.
     *
     * The scroll term moves the grid by the sub-line remainder. Clip y grows
     * upward, so a positive remainder — the content moving down, the user
     * dragging back into the history — is a subtraction.
     */
    fun projection(out: FloatArray) {
        val w = width.coerceAtLeast(1).toFloat()
        val h = height.coerceAtLeast(1).toFloat()

        out[0] = -1f
        out[1] = 1f - 2f * scrollOffsetPx / h
        out[2] = 2f / w
        out[3] = -2f / h
    }

    /** The same size at another font cell, with the pinch cleared. */
    fun withCell(cellWidth: Float, cellHeight: Float): SizeInfo =
        copy(
            cellWidth = cellWidth,
            cellHeight = cellHeight,
            pinchScale = 1f,
            pinchFocusX = 0f,
            pinchFocusY = 0f,
        )

    /** The same size at a new surface, keeping the cell and the scroll. */
    fun withSurface(width: Int, height: Int): SizeInfo = copy(width = width, height = height)

    companion object {
        /**
         * A grid narrower than this is not a grid.
         *
         * The limits are the engine's own (`GridSize::is_valid`): a terminal
         * with no columns has no cell to draw, and asking for one is a crash
         * rather than a small terminal.
         */
        const val MIN_COLUMNS = 2
        const val MIN_ROWS = 1

        /** A size with no surface yet: everything derived is the minimum. */
        fun empty(cellWidth: Float, cellHeight: Float): SizeInfo = SizeInfo(
            width = 0,
            height = 0,
            cellWidth = cellWidth.coerceAtLeast(1f),
            cellHeight = cellHeight.coerceAtLeast(1f),
        )
    }
}
