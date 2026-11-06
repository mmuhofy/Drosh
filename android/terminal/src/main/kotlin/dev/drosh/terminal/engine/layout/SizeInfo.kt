package dev.drosh.terminal.engine.layout

/**
 * The surface and the cell that sits in it, in one place.
 *
 * ## Why one type
 *
 * The previous engine kept six independent fields — surface width, surface
 * height, cell width, cell height, columns, rows — and four different callers
 * wrote them. Each write was correct at its own instant and none of them were
 * correct together, so a frame could be built from a surface of one size and a
 * cell of another. That is what "the text is inside itself while the keyboard
 * opens" was.
 *
 * Here the surface and the cell are the only inputs, and everything else —
 * columns, rows, the pixel step, the projection — is derived. A derived value
 * cannot disagree with its source, and there is exactly one place to change
 * when the rule changes.
 *
 * ## What a pinch does and does not do
 *
 * A pinch changes the size of the *text*, not of the grid. The cells stay where
 * they are and the glyphs inside them grow, which is what [pinchScale] is for.
 * Scaling [stepX] as well would change how many columns fit, which is a
 * different terminal rather than a bigger one — the whole screen would zoom
 * like an image and the text would stop lining up with the cursor.
 */
data class SizeInfo(
    /** The surface, in pixels. */
    val width: Int = 0,
    val height: Int = 0,
    /** The cell the font measured, in pixels. */
    val cellWidth: Float = 1f,
    val cellHeight: Float = 1f,
    /** The live pinch, 1 when there is none. */
    val pinchScale: Float = 1f,
    /** Where the pinch is anchored, in surface pixels. */
    val pinchFocusX: Float = 0f,
    val pinchFocusY: Float = 0f,
    /**
     * The sub-line part of the scroll, in pixels.
     *
     * The whole grid is drawn shifted by this, so a drag follows the finger
     * between rows instead of stepping a line at a time. The frame carries one
     * extra line above and below the viewport — the render state's overscan —
     * and those lines fill the strips the shift reveals.
     */
    val scrollOffsetPx: Float = 0f,
) {

    /** Columns that fit: a whole number of cells, rounded down. */
    val columns: Int
        get() = if (cellWidth <= 0f) MIN_COLUMNS else maxOf((width / cellWidth).toInt(), MIN_COLUMNS)

    /** Rows that fit. */
    val rows: Int
        get() = if (cellHeight <= 0f) MIN_ROWS else maxOf((height / cellHeight).toInt(), MIN_ROWS)

    /**
     * How far apart cells are placed, in pixels.
     *
     * The surface divided by the cells that fit in it, so the grid ends exactly
     * on the edge. The pinch is deliberately absent: see the class comment.
     */
    val stepX: Float
        get() = if (columns <= 0) cellWidth else width.toFloat() / columns

    val stepY: Float
        get() = if (rows <= 0) cellHeight else height.toFloat() / rows

    /**
     * The projection, as `(x0, y0, sx, sy)` for `clip = xy + zw * pixel`.
     *
     * A plain pixel-to-clip map with no scale in it. The pinch lives in the
     * glyph size the shader applies, not here: scaling the projection would
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

    /** The same size at another surface. */
    fun withSurface(width: Int, height: Int): SizeInfo = copy(width = width, height = height)

    /** True when the grid this describes is the same shape as [other]'s. */
    fun sameGridAs(other: SizeInfo): Boolean = columns == other.columns && rows == other.rows

    private companion object {
        /**
         * The smallest grid worth drawing.
         *
         * A surface can be zero-sized for a frame while a pane is being laid
         * out, and a grid of zero columns is a division by zero waiting in the
         * renderer. Two columns and one row is the smallest thing that is still
         * a terminal.
         */
        const val MIN_COLUMNS = 2
        const val MIN_ROWS = 1
    }
}
