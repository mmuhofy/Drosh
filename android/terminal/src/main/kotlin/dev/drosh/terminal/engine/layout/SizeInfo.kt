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
 * ## What a pinch does
 *
 * A pinch changes the size of the *font*, and the font's size is the cell's
 * size: the grid is laid out again, so the text is bigger and fewer columns
 * fit. That is a zoom.
 *
 * It is not a picture zoom. Scaling the projection would magnify the whole
 * surface — the grid, the cursor, the background — which is what an image
 * viewer does, and the user is left looking at a blown-up picture of a
 * terminal rather than a terminal with bigger text. Scaling the glyph inside
 * a fixed cell is the same mistake in the other direction: the letters grow
 * into the space around them and the text reads as though its tracking had
 * changed.
 */
data class SizeInfo(
    /** The surface, in pixels. */
    val width: Int = 0,
    val height: Int = 0,
    /** The cell the font measured, in pixels. */
    val cellWidth: Float = 1f,
    val cellHeight: Float = 1f,
    /**
     * The sub-line part of the scroll, in pixels.
     *
     * The whole grid is drawn shifted by this, so a drag follows the finger
     * between rows instead of stepping a line at a time. The frame carries one
     * extra line above and below the viewport — the render state's overscan —
     * and those lines fill the strips the shift reveals.
     */
    val scrollOffsetPx: Float = 0f,
    /**
     * True while a pinch is in progress.
     *
     * A live pinch changes the cell and nothing else: the grid keeps its
     * shape, so the text grows past the edge of the screen and is cut off.
     * That is what a zoom looks like, and it is why the zoom does not stutter:
     * the grid is not reflowed on every step of the gesture.
     *
     * When the gesture ends the cell is committed, the grid is laid out again
     * at the new cell size, and the picture fits the screen exactly.
     */
    val zooming: Boolean = false,
) {

    /**
     * Columns that fit: a whole number of cells, rounded down.
     *
     * Zero when there is no surface to fit them in. A surface reports 0×0
     * before it is laid out, and answering "two columns" for it — the old
     * minimum — drew a two-cell grid in the corner of an otherwise empty
     * window: the black square at the origin. A grid of no cells draws nothing,
     * which is the truth about a window that has no size yet.
     */
    val columns: Int
        get() = if (width <= 0 || cellWidth <= 0f) 0 else maxOf((width / cellWidth).toInt(), 1)

    /** Rows that fit. Zero before the surface exists, for [columns]' reason. */
    val rows: Int
        get() = if (height <= 0 || cellHeight <= 0f) 0 else maxOf((height / cellHeight).toInt(), 1)

    /**
     * How far apart cells are placed, in pixels.
     *
     * The surface divided by the cells that fit in it, so the grid ends exactly
     * on the edge.
     *
     * While a pinch is running the step is the cell itself: the grid still has
     * the shape it had, so the cells are bigger and the grid runs off the
     * edge. Dividing the surface by the old number of columns would squeeze
     * the bigger text back into the old cells, which is not a zoom.
     */
    val stepX: Float
        get() = when {
            zooming -> cellWidth
            columns <= 0 -> cellWidth
            else -> width.toFloat() / columns
        }

    val stepY: Float
        get() = when {
            zooming -> cellHeight
            rows <= 0 -> cellHeight
            else -> height.toFloat() / rows
        }

    /**
     * The projection, as `(x0, y0, sx, sy)` for `clip = xy + zw * pixel`.
     *
     * A plain pixel-to-clip map with no scale in it: the picture is the size
     * the surface is, and the text is the size the font is.
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

    /** The same size at another font cell, committed: the grid is laid out. */
    fun withCell(cellWidth: Float, cellHeight: Float): SizeInfo =
        copy(cellWidth = cellWidth, cellHeight = cellHeight, zooming = false)

    /** The same size at another font cell, mid-pinch: the grid does not move. */
    fun withZoomCell(cellWidth: Float, cellHeight: Float): SizeInfo =
        copy(cellWidth = cellWidth, cellHeight = cellHeight, zooming = true)

    /** The same size at another surface. */
    fun withSurface(width: Int, height: Int): SizeInfo = copy(width = width, height = height)

    /** True when the grid this describes is the same shape as [other]'s. */
    fun sameGridAs(other: SizeInfo): Boolean = columns == other.columns && rows == other.rows
}
