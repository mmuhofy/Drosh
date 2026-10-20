package dev.drosh.terminal.engine.render

import dev.drosh.terminal.engine.core.NativeCore
import dev.drosh.terminal.engine.input.Selection
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns the grid into the two record streams the renderer draws, in the shape
 * alacritty's GLES2 path and Ghostty's cell shader both use (PLAN.md decision
 * history, 2026-10-16 — sources read there, not remembered):
 *
 * - **backgrounds**: one 4-byte packed colour per cell, dense, and the cell's
 *   position is derived in the shader from `gl_InstanceID` — so no position is
 *   stored at all;
 * - **foregrounds**: a compact list of ~24-byte records, one per drawn thing
 *   (glyph, underline, strikeout, cursor bar), each carrying its atlas rectangle,
 *   its offset inside the cell, its grid position and its colour.
 *
 * That replaces up to five quads × six vertices × eight floats per cell (about a
 * kilobyte) with ~28 bytes per cell, which is the path the device captures blamed
 * the frame time on. The whole list is rebuilt every frame: at this size that is
 * cheaper than the bookkeeping that avoided it.
 */
internal class CellRecords(columns: Int, rows: Int) {

    /** Packed `0xAARRGGBB` colour per cell, `columns * rows` entries. */
    private var backgrounds: ByteBuffer

    /** The foreground records; only the first [foregroundCount] are drawn. */
    private var foregrounds: ByteBuffer

    val backgroundBytes: ByteBuffer get() = backgrounds
    val foregroundBytes: ByteBuffer get() = foregrounds

    var foregroundCount = 0
        private set

    var columns = columns
        private set

    var rows = rows
        private set

    /** Codepoint → atlas entry, so a glyph is looked up once, not per frame. */
    private var cellCodepoints = IntArray(0)
    private var cellBold = BooleanArray(0)
    private var cellEntries = arrayOfNulls<GlyphEntry>(0)

    /**
     * The pixel size the cached glyphs were rasterised at; `null` until the
     * first build.
     *
     * A mask of the wrong size is the wrong mask, so a change of size throws
     * the whole cache away rather than mixing sizes in one grid.
     */
    private var cachedFontPx: Float? = null

    init {
        backgrounds = allocate(columns * rows * BACKGROUND_BYTES)
        foregrounds = allocate(columns * rows * OVERDRAW * RECORD_BYTES)
        resetCellCache(columns, rows)
    }

    fun resize(newColumns: Int, newRows: Int) {
        if (newColumns == columns && newRows == rows) {
            return
        }
        columns = newColumns
        rows = newRows
        // Reallocated rather than grown in place: a resize happens a handful of
        // times per session and a fresh direct buffer is zeroed.
        backgrounds = allocate(newColumns * newRows * BACKGROUND_BYTES)
        foregrounds = allocate(newColumns * newRows * OVERDRAW * RECORD_BYTES)
        resetCellCache(newColumns, newRows)
    }

    /** Rebuilt each frame: every record is written, so the buffers stay valid. */
    fun build(
        cells: ByteBuffer,
        state: IntArray,
        metrics: CellMetrics,
        selection: Selection?,
        cursorVisible: Boolean,
        atlas: GlyphAtlas,
        fontPx: Float,
    ) {
        if (cachedFontPx != fontPx) {
            resetCellCache(columns, rows)
            cachedFontPx = fontPx
        }

        val cursorRow = state[NativeCore.STATE_CURSOR_ROW]
        val cursorColumn = state[NativeCore.STATE_CURSOR_COL]
        val cursorShape = state[NativeCore.STATE_CURSOR_SHAPE]
        val cellWidth = metrics.width
        val cellHeight = metrics.height

        backgrounds.clear()
        foregrounds.clear()
        foregroundCount = 0

        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val cell = row * columns + column
                val at = cell * NativeCore.CELL_BYTES
                val codepoint = cells.getInt(at + NativeCore.CELL_OFFSET_CODEPOINT)
                val fgEncoded = cells.getInt(at + NativeCore.CELL_OFFSET_FG)
                val bgEncoded = cells.getInt(at + NativeCore.CELL_OFFSET_BG)
                val flags = cells.getShort(at + NativeCore.CELL_OFFSET_FLAGS).toInt() and 0xffff

                var foreground = Palette.resolve(fgEncoded, Palette.DEFAULT_FOREGROUND)
                var background = Palette.resolve(bgEncoded, Palette.DEFAULT_BACKGROUND)
                val bold = flags and NativeCore.FLAG_BOLD != 0

                if (flags and NativeCore.FLAG_INVERSE != 0) {
                    val swap = foreground
                    foreground = background
                    background = swap
                }

                if (selection != null && selection.contains(row, column)) {
                    // alacritty's default selection colours are the inverted cell.
                    background = Palette.foreground
                    foreground = Palette.background
                }

                val cursorHere = cursorVisible &&
                    cursorShape != NativeCore.CURSOR_HIDDEN &&
                    row == cursorRow &&
                    column == cursorColumn
                if (cursorHere && cursorShape == NativeCore.CURSOR_BLOCK) {
                    background = Palette.cursor
                    foreground = Palette.background
                }

                backgrounds.putInt(pack(background))

                // A wide character's spacer has no glyph of its own; a hidden cell
                // draws nothing at all (its background is already written).
                val hidden = flags and NativeCore.FLAG_HIDDEN != 0
                val spacer = flags and NativeCore.FLAG_WIDE_CHAR_SPACER != 0 ||
                    flags and NativeCore.FLAG_LEADING_WIDE_CHAR_SPACER != 0
                if (hidden || spacer) {
                    continue
                }

                if (codepoint != ' '.code) {
                    val entry = glyphFor(cell, codepoint, bold, atlas, fontPx)
                    if (entry != null && !entry.isBlank) {
                        // In-cell offsets only: the shader adds the cell's own
                        // origin from the record's grid position. Passing the
                        // cell origin here as well doubled it — every column
                        // landed two cells to the right, which read as letter
                        // spacing on the device.
                        writeGlyph(
                            entry,
                            entry.left.toFloat(),
                            metrics.ascent - entry.top,
                            column,
                            row,
                            foreground,
                        )
                    }
                }

                if (flags and NativeCore.FLAG_ANY_UNDERLINE != 0) {
                    writeRect(
                        0f,
                        metrics.ascent + metrics.underline,
                        cellWidth,
                        metrics.rule,
                        column,
                        row,
                        foreground,
                        atlas,
                    )
                }
                if (flags and NativeCore.FLAG_STRIKEOUT != 0) {
                    writeRect(
                        0f,
                        metrics.ascent - metrics.ascent * 0.35f,
                        cellWidth,
                        metrics.rule,
                        column,
                        row,
                        foreground,
                        atlas,
                    )
                }

                if (cursorHere) {
                    when (cursorShape) {
                        NativeCore.CURSOR_BEAM -> writeRect(
                            0f,
                            0f,
                            cellWidth * 0.12f,
                            cellHeight,
                            column,
                            row,
                            Palette.cursor,
                            atlas,
                        )
                        NativeCore.CURSOR_UNDERLINE -> writeRect(
                            0f,
                            cellHeight * 0.86f,
                            cellWidth,
                            cellHeight * 0.14f,
                            column,
                            row,
                            Palette.cursor,
                            atlas,
                        )
                        NativeCore.CURSOR_HOLLOW -> writeRect(
                            0f,
                            cellHeight * 0.9f,
                            cellWidth,
                            cellHeight * 0.1f,
                            column,
                            row,
                            Palette.cursor,
                            atlas,
                        )
                    }
                }
            }
        }

        backgrounds.flip()
        foregrounds.flip()
    }

    private fun glyphFor(
        cell: Int,
        codepoint: Int,
        bold: Boolean,
        atlas: GlyphAtlas,
        fontPx: Float,
    ): GlyphEntry? {
        val cached = cellEntries[cell]
        if (cached != null && cellCodepoints[cell] == codepoint && cellBold[cell] == bold) {
            return cached
        }

        val entry = atlas.glyph(codepoint, bold, fontPx)
        cellEntries[cell] = entry
        cellCodepoints[cell] = codepoint
        cellBold[cell] = bold
        return entry
    }

    private fun writeGlyph(
        entry: GlyphEntry,
        left: Float,
        top: Float,
        column: Int,
        row: Int,
        color: Int,
    ) {
        if (foregroundCount >= capacity) {
            return
        }

        foregrounds.putShort(entry.x.toShort())
        foregrounds.putShort(entry.y.toShort())
        foregrounds.putShort(entry.width.toShort())
        foregrounds.putShort(entry.height.toShort())
        foregrounds.putShort(clampShort(left).toShort())
        foregrounds.putShort(clampShort(top).toShort())
        foregrounds.putShort(column.toShort())
        foregrounds.putShort(row.toShort())
        putColor(color)
        foregrounds.putShort(FLAG_NONE.toShort())
        foregrounds.putShort(0)
        foregroundCount += 1
    }

    /** A solid rectangle: underline, strikeout or a cursor bar. */
    private fun writeRect(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        column: Int,
        row: Int,
        color: Int,
        atlas: GlyphAtlas,
    ) {
        if (foregroundCount >= capacity) {
            return
        }

        foregrounds.putShort(atlas.solidX.toShort())
        foregrounds.putShort(atlas.solidY.toShort())
        foregrounds.putShort(width.toInt().coerceAtLeast(1).toShort())
        foregrounds.putShort(height.toInt().coerceAtLeast(1).toShort())
        foregrounds.putShort(clampShort(left).toShort())
        foregrounds.putShort(clampShort(top).toShort())
        foregrounds.putShort(column.toShort())
        foregrounds.putShort(row.toShort())
        putColor(color)
        // A solid quad samples one texel; its width/height are pixels, not
        // atlas texels, so the shader must not walk the UV with them.
        foregrounds.putShort(FLAG_SOLID.toShort())
        foregrounds.putShort(0)
        foregroundCount += 1
    }

    private fun putColor(color: Int) {
        // Normalised `u8x4`, the packing alacritty uses for its vertices.
        foregrounds.put((color shr 16 and 0xff).toByte())
        foregrounds.put((color shr 8 and 0xff).toByte())
        foregrounds.put((color and 0xff).toByte())
        foregrounds.put(0xff.toByte())
    }

    /** `0xAARRGGBB`, which is the order the background shader unpacks. */
    private fun pack(color: Int): Int = (0xff shl 24) or (color and 0xffffff)

    private fun clampShort(value: Float): Int = value.toInt().coerceIn(-32768, 32767)

    private fun resetCellCache(newColumns: Int, newRows: Int) {
        val cells = newColumns * newRows
        cellCodepoints = IntArray(cells) { -1 }
        cellBold = BooleanArray(cells)
        cellEntries = arrayOfNulls(cells)
    }

    private val capacity: Int get() = rows * columns * OVERDRAW

    companion object {
        /** Packed colour: four bytes. */
        const val BACKGROUND_BYTES = 4

        /**
         * Atlas rect (8) + in-cell offset (4) + grid position (4) + colour (4) +
         * flags (2) + padding (2) — the shape Ghostty's cell shader reads.
         */
        const val RECORD_BYTES = 24

        /**
         * Records a cell may need: glyph, underline, strikeout, cursor — Ghostty
         * reserves three slots per column for the same reason.
         */
        const val OVERDRAW = 4

        /** Record flag: the quad is a solid fill and samples one atlas texel. */
        const val FLAG_SOLID = 1
        const val FLAG_NONE = 0

        private fun allocate(bytes: Int): ByteBuffer =
            ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }
}
