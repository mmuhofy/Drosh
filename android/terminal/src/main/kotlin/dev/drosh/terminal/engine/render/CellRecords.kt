package dev.drosh.terminal.engine.render

import dev.drosh.terminal.engine.core.NativeCore
import dev.drosh.terminal.engine.core.Palette
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

    /**
     * One 8-byte record per cell: its grid position and its colour.
     *
     * The position is stored rather than derived from `gl_InstanceID`. Deriving
     * it in the shader needs `uColumns` and an assumption that instance `n` is
     * cell `n` — true only while the buffer holds the whole grid in order, and
     * silently wrong the moment anything else is drawn from it. Alacritty stores
     * the position on the vertex (`gridCoords` in `res/glsl3/text.v.glsl`) for
     * the same reason: a vertex that says where it is cannot be mislaid.
     */
    private var backgrounds: ByteBuffer

    /** The foreground records; only the first [foregroundCount] are drawn. */
    private var foregrounds: ByteBuffer

    val backgroundBytes: ByteBuffer get() = backgrounds
    val foregroundBytes: ByteBuffer get() = foregrounds

    var foregroundCount = 0
        private set

    /** Cells whose background is not the terminal's own, this frame. */
    var coloredCells = 0
        private set

    /**
     * The first few coloured cells, as the engine encoded them and as they came
     * out after resolution.
     *
     * `coloredCells` says how many; this says which colours. A block that is
     * drawn but indistinguishable from the background has the same count as a
     * block that is missing, and only the values tell them apart.
     */
    val colouredSamples = ArrayList<String>(SAMPLES)

    var columns = columns
        private set

    var rows = rows
        private set

    /**
     * The shaped rows: the ligatures the font made of the text.
     *
     * The shaper is asked only when the application wants ligatures; the
     * per-codepoint path below draws everything else.
     */
    private val shaper = RowShaper()

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
        shaper.resize(columns, rows)
    }

    /**
     * Write one cell's background record.
     *
     * Written in grid order, one after another, so the buffer's position ends
     * at the end of the records — which is what `flip()` needs to publish them.
     * An absolute write (`putShort(at, …)`) leaves the position where it was,
     * and a buffer that is flipped from position zero has no data in it at all.
     */
    private fun putBackground(column: Int, row: Int, color: Int) {
        backgrounds.putShort(column.toShort())
        backgrounds.putShort(row.toShort())
        backgrounds.put((color shr 16 and 0xff).toByte())
        backgrounds.put((color shr 8 and 0xff).toByte())
        backgrounds.put((color and 0xff).toByte())
        backgrounds.put(0xff.toByte())
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
        shaper.resize(newColumns, newRows)
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
        ligatures: Boolean,
    ) {
        // The buffer must hold the grid the caller asked for.
        //
        // A frame can arrive while the session is still resizing: the renderer
        // has laid its grid out for the new size and the session has not yet
        // been resized to match, or the other way round. Reading it anyway is
        // an index out of bounds in the middle of a pinch; drawing nothing and
        // leaving the last frame on screen is the honest answer.
        if (cells.limit() < columns * rows * NativeCore.CELL_BYTES) {
            backgrounds.clear()
            foregrounds.clear()
            foregroundCount = 0
            return
        }

        if (cachedFontPx != fontPx) {
            resetCellCache(columns, rows)
            cachedFontPx = fontPx
        }

        // The ligatures, when the application wants them. A row is shaped
        // again only when its text changed, so this is a hash per row and a
        // JNI call for the rows that moved.
        if (ligatures) {
            shaper.shape(cells, columns, rows, fontPx)
        }

        val cursorRow = state[NativeCore.STATE_CURSOR_ROW]
        val cursorColumn = state[NativeCore.STATE_CURSOR_COL]
        val cursorShape = state[NativeCore.STATE_CURSOR_SHAPE]
        // DECTCEM (`?25l`/`?25h`) is a mode bit, not a shape: the application
        // says "draw no cursor" and the shape says how it would look if it
        // were drawn. The bit was never read here, so a program that hid its
        // cursor left one on screen, and the blink alone decided whether any
        // was drawn at all.
        val cursorShown = state[NativeCore.STATE_MODE_BITS] and NativeCore.MODE_SHOW_CURSOR != 0
        val cellWidth = metrics.width
        val cellHeight = metrics.height

        backgrounds.clear()
        foregrounds.clear()
        foregroundCount = 0
        coloredCells = 0
        colouredSamples.clear()

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

                // Bold text in the bright colours, when the application asked
                // for it (alacritty's `draw_bold_text_with_bright_colors`).
                // The named colour is the dim one, so the bright one is the
                // same hue with the dim factor undone.
                if (bold && Palette.boldBrightens) {
                    foreground = Palette.brightened(foreground)
                }

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

                // A cell with a background other than the terminal's own is a
                // block the application drew — nano's menu bar, vim's status
                // line, an inverse-video highlight. Counted so a device run can
                // tell "the program did not send one" from "we did not draw
                // one", which are the same picture and opposite bugs.
                if ((background and 0xffffff) != (Palette.background and 0xffffff)) {
                    if (colouredSamples.size < SAMPLES) {
                        colouredSamples += describeBackground(bgEncoded, background)
                    }
                    coloredCells += 1
                }

                val hidden = flags and NativeCore.FLAG_HIDDEN != 0
                val cursorHere = cursorVisible &&
                    cursorShown &&
                    cursorShape != NativeCore.CURSOR_HIDDEN &&
                    row == cursorRow &&
                    column == cursorColumn
                if (cursorHere && cursorShape == NativeCore.CURSOR_BLOCK) {
                    background = Palette.cursor
                    foreground = Palette.background
                }

                // Hidden text still draws its background. A program that picks
                // a colour and then hides the character — what nano and every
                // other menu draws a filled block with — must show the block,
                // not the terminal's own colour: skipping the background too
                // was why those programs looked like blank space with no
                // colours at all.
                if (hidden) {
                    putBackground(column, row, background)
                    continue
                }

                // A wide character's spacer has no glyph of its own; its
                // background is already written and it draws nothing on top.
                val spacer = flags and NativeCore.FLAG_WIDE_CHAR_SPACER != 0 ||
                    flags and NativeCore.FLAG_LEADING_WIDE_CHAR_SPACER != 0
                putBackground(column, row, background)
                if (spacer) {
                    continue
                }

                // A ligature covers cells the font merged; the cells it
                // covers after the first one draw nothing of their own.
                val coveredByLigature = ligatures && shaper.isCovered(row, column, columns)
                val shapedId = if (ligatures && !coveredByLigature) {
                    shaper.glyphId(row, column, columns)
                } else {
                    0
                }

                if (shapedId != 0) {
                    val entry = atlas.glyphById(shapedId, bold, fontPx)
                    if (entry != null && !entry.isBlank) {
                        writeGlyph(
                            entry,
                            entry.left.toFloat(),
                            metrics.ascent - entry.top,
                            column,
                            row,
                            foreground,
                        )
                    }
                } else if (!coveredByLigature && codepoint != ' '.code) {
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

    /**
     * A coloured cell's background, as `raw→#hex`.
     *
     * The raw value is the wire encoding: its top two bits are the tag — named,
     * indexed or a literal RGB — so the sample says whether a program asked for
     * an ANSI colour, a palette index, or an exact value.
     */
    private fun describeBackground(encoded: Int, resolved: Int): String {
        val tag = when (encoded ushr NativeCore.COLOR_TAG_SHIFT) {
            NativeCore.COLOR_TAG_NAMED -> "n"
            NativeCore.COLOR_TAG_INDEXED -> "i"
            else -> "s"
        }
        val payload = encoded and NativeCore.COLOR_VALUE_MASK
        return tag + payload + "→#" +
            (resolved and 0xffffff).toString(16).padStart(6, '0')
    }

    /** `0xAARRGGBB`, which is the order the background shader unpacks. */
    private fun pack(color: Int): Int = (0xff shl 24) or (color and 0xffffff)

    private fun clampShort(value: Float): Int = value.toInt().coerceIn(-32768, 32767)

    /**
     * Forget the glyph each cell was drawn with.
     *
     * The cache is keyed by codepoint and weight; a size change makes every
     * entry stale without changing its key, so the whole thing goes.
     */
    fun invalidateCells() {
        resetCellCache(columns, rows)
        cachedFontPx = null
    }

    private fun resetCellCache(newColumns: Int, newRows: Int) {
        val cells = newColumns * newRows
        cellCodepoints = IntArray(cells) { -1 }
        cellBold = BooleanArray(cells)
        cellEntries = arrayOfNulls(cells)
    }

    private val capacity: Int get() = rows * columns * OVERDRAW

    companion object {
        /** Packed colour: four bytes. */
        const val BACKGROUND_BYTES = 8

        /** How many coloured cells the overlay describes. */
        const val SAMPLES = 5

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
