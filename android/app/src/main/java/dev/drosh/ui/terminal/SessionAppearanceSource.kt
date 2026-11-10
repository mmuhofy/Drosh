package dev.drosh.ui.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import dev.drosh.terminal.engine.protocol.FrameFormat
import dev.drosh.terminal.session.TerminalEngine

/**
 * A terminal's appearance, read from the cells of its session.
 *
 * ## Why there are no pixels
 *
 * The terminal draws through OpenGL. Its output lives in a surface of its own,
 * outside the view hierarchy, so there is nothing to read back the way a
 * view's pixels are read back. What the engine can report is the grid — and
 * the colours of the cells in it.
 *
 * ## What comes out is a colour field, not a picture
 *
 * A cell's colours say what colour its background is, not what is drawn in
 * it. So the strip this source produces is the terminal's background colours,
 * cell by cell: the shape of the terminal's colours, with none of its text.
 * That is not the same thing as a picture of the terminal and is not claimed
 * to be — it is what a blur over the terminal can honestly be made of.
 *
 * The region, on the other hand, is the region's **average colour**: the menu
 * that floats over it wants a colour to tint itself with, and one colour is
 * what a region of cells can honestly be reduced to.
 */
class SessionAppearanceSource(
    private val pane: TerminalEngine,
) : TerminalAppearanceSource {

    override val kind: TerminalAppearanceKind get() = TerminalAppearanceKind.ENGINE

    /**
     * The width of the grid, in pixels.
     *
     * The grid is what the terminal draws: the surface may be a little wider
     * than the cells in it, and that sliver is the clear colour rather than a
     * cell.
     */
    override val widthPx: Int get() = pane.columns * pane.cellWidth.toInt()

    override val heightPx: Int get() = pane.rows * pane.cellHeight.toInt()

    /**
     * A token that is different on every read.
     *
     * The engine has no generation counter to offer: the grid is the only
     * thing that says whether it moved, and the grid is the very thing being
     * sampled. So the token changes every time it is read, and the interval
     * the caller waits between captures is the rate limit here.
     */
    private var token = 0

    override val contentToken: Int get() = ++token

    override fun captureStrip(heightPx: Int): Bitmap? {
        val cellWidth = pane.cellWidth
        val cellHeight = pane.cellHeight
        val columns = pane.columns
        if (cellWidth <= 0f || cellHeight <= 0f || columns <= 0 || heightPx <= 0) {
            return null
        }
        // Only the rows the strip covers. The whole grid would be tens of
        // kilobytes across the JNI boundary, and the strip is a handful of
        // rows.
        val rows = ((heightPx + cellHeight.toInt() - 1) / cellHeight.toInt())
            .coerceAtMost(pane.rows)
        if (rows <= 0) {
            return null
        }
        val cells = pane.cellColors(0, 0, rows, columns) ?: return null
        if (cells.isEmpty()) {
            return null
        }
        // One cell of padding on the right: the surface is a little wider
        // than the grid, and that sliver shows the terminal's background.
        val width = (columns * cellWidth).toInt() + cellWidth.toInt()
        val height = (rows * cellHeight).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = opaque(averageBackground(cells))
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        // One rectangle per cell, in the cell's own colour.
        var index = 0
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val foreground = cells.getOrNull(index * 2) ?: 0
                val background = cells.getOrNull(index * 2 + 1) ?: 0
                index += 1
                val left = (column * cellWidth)
                val top = (row * cellHeight)
                val right = left + cellWidth
                val bottom = top + cellHeight

                paint.color = opaque(background)
                canvas.drawRect(left, top, right, bottom, paint)

                // And the character in it, as a block.
                //
                // The blur over this backdrop is only a blur if there is
                // something to blur: a field of flat background colours blurs
                // to a flat colour, and the pill reads as a tint rather than
                // as glass. The block stands in for the glyph — the engine
                // reports the cell's colours, not its shape — and the blur does
                // the rest.
                if (foreground != 0 && foreground != background) {
                    paint.color = opaque(foreground)
                    val inset = cellWidth * 0.2f
                    val glyphTop = top + cellHeight * 0.18f
                    canvas.drawRect(
                        left + inset,
                        glyphTop,
                        right - inset,
                        bottom - cellHeight * 0.18f,
                        paint,
                    )
                }
            }
        }
        return bitmap
    }

    override fun captureRegion(left: Int, top: Int, width: Int, height: Int): Bitmap? {
        val cellWidth = pane.cellWidth
        val cellHeight = pane.cellHeight
        if (cellWidth <= 0f || cellHeight <= 0f || width <= 0 || height <= 0) {
            return null
        }
        // The cells the region covers. The grid clips the region, so a region
        // that hangs over the edge is answered by the cells that are there.
        val firstRow = (top / cellHeight.toInt()).coerceAtLeast(0)
        val firstColumn = (left / cellWidth.toInt()).coerceAtLeast(0)
        val rows = (top + height + cellHeight.toInt() - 1) / cellHeight.toInt() - firstRow
        val columns = (left + width + cellWidth.toInt() - 1) / cellWidth.toInt() - firstColumn
        if (rows <= 0 || columns <= 0) {
            return null
        }
        val cells = pane.cellColors(firstRow, firstColumn, rows, columns) ?: return null
        if (cells.isEmpty()) {
            return null
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(opaque(averageBackground(cells)))
        return bitmap
    }

    /**
     * The average of the cells' background colours, as `0xRRGGBB`.
     *
     * The background and not the foreground: what is behind the chrome is the
     * terminal's background, and a region of text on that background is still
     * that background as far as a tint is concerned.
     */
    private fun averageBackground(cells: IntArray): Int {
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        var index = 1
        while (index < cells.size) {
            val packed = cells[index]
            red += FrameFormat.red(packed).toLong()
            green += FrameFormat.green(packed).toLong()
            blue += FrameFormat.blue(packed).toLong()
            count += 1
            index += 2
        }
        if (count == 0) return 0
        return ((red / count).toInt() shl 16) or
            ((green / count).toInt() shl 8) or
            (blue / count).toInt()
    }

    /** A colour as the `0xAARRGGBB` a bitmap is filled with. */
    private fun opaque(rgb: Int): Int = OPAQUE or (rgb and 0xffffff)

    private companion object {
        /** The alpha byte of a colour that is not transparent: `0xFF000000`. */
        val OPAQUE: Int = 0xFF000000.toInt()
    }
}
