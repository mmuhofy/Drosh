package dev.drosh.ui.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import dev.drosh.terminal.engine.core.CellColor
import dev.drosh.terminal.engine.core.EngineSession

/**
 * The engine-backed terminal's appearance, read from the cells of its session.
 *
 * ## Why there are no pixels
 *
 * The engine draws through OpenGL. Its output lives in a surface of its own,
 * outside the view hierarchy, so there is nothing to read back the way the
 * Termux view's pixels are read back. What the engine can report is the grid
 * — and the colours of the cells in it.
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
class EngineAppearanceSource(private val session: EngineSession) : TerminalAppearanceSource {

    override val kind: TerminalAppearanceKind get() = TerminalAppearanceKind.ENGINE

    /**
     * The width of the grid, in pixels.
     *
     * The grid is what the engine draws: the surface may be a little wider
     * than the cells in it, and that sliver is the engine's clear colour
     * rather than a cell.
     */
    override val widthPx: Int get() = session.columns * session.cellWidth

    override val heightPx: Int get() = session.rows * session.cellHeight

    /**
     * A token that is different on every read.
     *
     * The engine has no generation counter to offer: its output arrives as a
     * stream of bytes, and the only thing that says whether the grid moved is
     * the grid itself — which is the very thing being sampled. So the token
     * changes every time it is read, and the interval the caller waits between
     * captures is the rate limit here — the same limit the Termux path has.
     */
    private var token = 0

    override val contentToken: Int get() = ++token

    override fun captureStrip(heightPx: Int): Bitmap? {
        val cellWidth = session.cellWidth
        val cellHeight = session.cellHeight
        val columns = session.columns
        if (cellWidth <= 0 || cellHeight <= 0 || columns <= 0 || heightPx <= 0) {
            return null
        }
        // Only the rows the strip covers. The whole grid would be tens of
        // kilobytes of text across the JNI boundary, and the strip is a
        // handful of rows.
        val rows = ((heightPx + cellHeight - 1) / cellHeight).coerceAtMost(session.rows)
        if (rows <= 0) {
            return null
        }
        val cells = session.cellColors(0, 0, rows, columns)
        if (cells.isEmpty()) {
            return null
        }
        // One cell of padding on the right: the surface is a little wider
        // than the grid, and that sliver shows the engine's clear colour —
        // which is the terminal's background, the same colour the padding is
        // filled with.
        val width = columns * cellWidth + cellWidth
        val height = rows * cellHeight
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = opaque(averageBackground(cells))
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        // One rectangle per cell, in the cell's own colour.
        for (cell in cells) {
            val left = (cell.column * cellWidth).toFloat()
            val top = (cell.row * cellHeight).toFloat()
            val right = left + cellWidth
            val bottom = top + cellHeight

            paint.color = opaque(cell.backgroundRgb)
            canvas.drawRect(left, top, right, bottom, paint)

            // And the character in it, as a block.
            //
            // The blur over this backdrop is only a blur if there is
            // something to blur: a field of flat background colours blurs to
            // a flat colour, and the pill reads as a tint rather than as
            // glass. The block stands in for the glyph — the engine reports
            // the cell's colours, not its shape — and the blur does the rest.
            if (!cell.blank && cell.foregroundRgb != cell.backgroundRgb) {
                paint.color = opaque(cell.foregroundRgb)
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
        return bitmap
    }

    override fun captureRegion(left: Int, top: Int, width: Int, height: Int): Bitmap? {
        val cellWidth = session.cellWidth
        val cellHeight = session.cellHeight
        if (cellWidth <= 0 || cellHeight <= 0 || width <= 0 || height <= 0) {
            return null
        }
        // The cells the region covers. The engine clips the region to the
        // grid, so a region that hangs over the edge is answered by the cells
        // that are there.
        val firstRow = (top / cellHeight).coerceAtLeast(0)
        val firstColumn = (left / cellWidth).coerceAtLeast(0)
        val rows = (top + height + cellHeight - 1) / cellHeight - firstRow
        val columns = (left + width + cellWidth - 1) / cellWidth - firstColumn
        if (rows <= 0 || columns <= 0) {
            return null
        }
        val cells = session.cellColors(firstRow, firstColumn, rows, columns)
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
    private fun averageBackground(cells: List<CellColor>): Int {
        var red = 0L
        var green = 0L
        var blue = 0L
        for (cell in cells) {
            val rgb = cell.backgroundRgb
            red += ((rgb shr 16) and 0xff).toLong()
            green += ((rgb shr 8) and 0xff).toLong()
            blue += (rgb and 0xff).toLong()
        }
        val count = cells.size
        return ((red / count).toInt() shl 16) or
            ((green / count).toInt() shl 8) or
            (blue / count).toInt()
    }

    /** An `0xRRGGBB` colour as the `0xAARRGGBB` a bitmap is filled with. */
    private fun opaque(rgb: Int): Int = OPAQUE or (rgb and 0xffffff)

    private companion object {
        /** The alpha byte of a colour that is not transparent: `0xFF000000`. */
        val OPAQUE: Int = 0xFF000000.toInt()
    }
}
