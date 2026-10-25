package dev.drosh.terminal.engine.render

import dev.drosh.terminal.engine.core.NativeCore
import java.nio.ByteBuffer

/**
 * The shaped rows: what the font says the text of a row looks like.
 *
 * ## Why the terminal is not asked
 *
 * A terminal's cell holds a character, not a glyph. The glyph is the font's
 * answer to a run of characters, and a ligature is that answer covering more
 * than one character: `->` drawn as one glyph that covers two cells.
 *
 * alacritty splits it the same way — the terminal knows the cells, and the
 * *renderer* shapes them — so the shaping happens here, on the render thread,
 * and the terminal never learns that a ligature exists.
 *
 * ## Only the ligatures take over
 *
 * A shaped run is compared with the cells it came from, and only the cells
 * where the two differ are drawn from the shaped glyphs. Everything else keeps
 * the per-codepoint path, which knows the font's *fallback* chain: a
 * character the primary face does not have is drawn from another face, and
 * the shaped path — which names the primary face's glyphs — would lose that.
 *
 * ## The cache
 *
 * A row is shaped again only when its text changes. The key is a hash of the
 * row's characters and their weights, so a row that is merely scrolled by
 * costs nothing, and a row that is redrawn costs one JNI call.
 */
internal class RowShaper {

    /** The glyph id per cell; 0 is "no ligature here", which is most cells. */
    private var glyphIds = IntArray(0)

    /** True for the cells a ligature covers after its first cell. */
    private var covered = BooleanArray(0)

    /** The key each row was shaped with, so a change is noticed. */
    private var keys = LongArray(0)

    /** The shaped glyphs, as the core answers them: the id, then the offset. */
    private val shaped = IntArray(SHAPE_BUFFER_INTS)

    /** The row's text, reused between rows so a frame does not allocate. */
    private val text = StringBuilder()

    /** The cell each character of the row belongs to. */
    private var cellOfEntry = IntArray(0)

    /** The codepoint of each character of the row. */
    private var codepointOfEntry = IntArray(0)

    /** Whether each character of the row is bold. */
    private var boldOfEntry = BooleanArray(0)

    /** The character index in the row's text where each character starts. */
    private var textStartOfEntry = IntArray(0)

    /** The byte offset within the run where each character starts. */
    private var byteOffsetOfEntry = IntArray(0)

    fun resize(columns: Int, rows: Int) {
        glyphIds = IntArray(columns * rows)
        covered = BooleanArray(columns * rows)
        keys = LongArray(rows)
        cellOfEntry = IntArray(columns)
        codepointOfEntry = IntArray(columns)
        boldOfEntry = BooleanArray(columns)
        textStartOfEntry = IntArray(columns)
        byteOffsetOfEntry = IntArray(columns)
    }

    /**
     * Shape the rows whose text changed since the last time.
     *
     * The rows are shaped in order and each one is keyed by its own text, so
     * a frame that changed one line shapes one line.
     */
    fun shape(cells: ByteBuffer, columns: Int, rows: Int, fontPx: Float) {
        // The cache is sized by the grid the caller is about to read, every
        // time — not only when it grew. A buffer that shrank and then grew
        // again (a pinch in, a pinch out) had arrays from the old shape, and
        // reading them past their end is the index out of bounds a fast pinch
        // produced.
        if (glyphIds.size != columns * rows || keys.size != rows) {
            resize(columns, rows)
        }
        for (row in 0 until rows) {
            val key = rowKey(row, cells, columns, fontPx)
            if (keys[row] == key) {
                continue
            }
            keys[row] = key
            shapeRow(row, cells, columns, fontPx)
        }
    }

    /** The ligature's glyph id for a cell, or 0 when the cell draws itself. */
    fun glyphId(row: Int, column: Int, columns: Int): Int = glyphIds[row * columns + column]

    /** True when a ligature that started earlier covers this cell. */
    fun isCovered(row: Int, column: Int, columns: Int): Boolean = covered[row * columns + column]

    /**
     * The row's key: its characters, their weights and the size they are
     * rasterised at.
     *
     * A hash, so a collision would show a stale row; the odds are the same
     * ones the atlas's own cache lives with.
     */
    private fun rowKey(row: Int, cells: ByteBuffer, columns: Int, fontPx: Float): Long {
        var key = FNV_OFFSET_BASIS
        key = key * FNV_PRIME + fontPx.toBits().toLong()
        val base = row * columns * NativeCore.CELL_BYTES
        // A frame that arrives between a resize and the session catching up can
        // hold a buffer for a different grid. Nothing is drawable in it, so the
        // row is left as it was rather than read past the end.
        if (base < 0 || base + columns * NativeCore.CELL_BYTES > cells.limit()) {
            return key
        }
        for (column in 0 until columns) {
            val at = base + column * NativeCore.CELL_BYTES
            val codepoint = cells.getInt(at + NativeCore.CELL_OFFSET_CODEPOINT)
            val flags = cells.getShort(at + NativeCore.CELL_OFFSET_FLAGS).toInt() and 0xffff
            key = key * FNV_PRIME + codepoint.toLong()
            key = key * FNV_PRIME + flags.toLong()
        }
        return key
    }

    /**
     * Shape one row and record the ligatures in it.
     */
    private fun shapeRow(row: Int, cells: ByteBuffer, columns: Int, fontPx: Float) {
        val base = row * columns * NativeCore.CELL_BYTES
        if (base < 0 || base + columns * NativeCore.CELL_BYTES > cells.limit()) {
            return
        }
        text.setLength(0)
        var entries = 0

        for (column in 0 until columns) {
            val at = base + column * NativeCore.CELL_BYTES
            val codepoint = cells.getInt(at + NativeCore.CELL_OFFSET_CODEPOINT)
            val flags = cells.getShort(at + NativeCore.CELL_OFFSET_FLAGS).toInt() and 0xffff
            if (flags and NativeCore.FLAG_HIDDEN != 0) {
                continue
            }
            if (flags and NativeCore.FLAG_WIDE_CHAR_SPACER != 0 ||
                flags and NativeCore.FLAG_LEADING_WIDE_CHAR_SPACER != 0
            ) {
                continue
            }
            if (codepoint <= 0 || codepoint > Character.MAX_CODE_POINT) {
                continue
            }
            textStartOfEntry[entries] = text.length
            cellOfEntry[entries] = column
            codepointOfEntry[entries] = codepoint
            boldOfEntry[entries] = flags and NativeCore.FLAG_BOLD != 0
            entries++
            text.appendCodePoint(codepoint)
        }

        // The row's own cells are the default: the shaped path only takes over
        // where the font said something different.
        for (column in 0 until columns) {
            glyphIds[row * columns + column] = 0
            covered[row * columns + column] = false
        }
        if (entries == 0) {
            return
        }

        // Runs of the same weight, because the two faces shape differently.
        var start = 0
        while (start < entries) {
            var end = start + 1
            while (end < entries && boldOfEntry[end] == boldOfEntry[start]) {
                end++
            }
            shapeRun(row, columns, start, end, entries)
            start = end
        }
    }

    /**
     * Shape one run of the row and record the ligatures in it.
     */
    private fun shapeRun(row: Int, columns: Int, start: Int, end: Int, entries: Int) {
        val bold = boldOfEntry[start]
        val runStart = textStartOfEntry[start]
        val runEnd = if (end < entries) textStartOfEntry[end] else text.length
        if (runEnd <= runStart) {
            return
        }
        val run = text.substring(runStart, runEnd)

        // The byte offset of every character within the run, because the core
        // reports the clusters as byte offsets.
        var byteOffset = 0
        for (index in start until end) {
            byteOffsetOfEntry[index - start] = byteOffset
            byteOffset += utf8Length(codepointOfEntry[index])
        }

        val count = NativeCore.nativeShape(run, bold, true, shaped)
        if (count <= 0) {
            return
        }

        // The character each shaped glyph starts at.
        val indices = IntArray(count)
        for (index in 0 until count) {
            val cluster = shaped[index * 2 + 1]
            indices[index] = start + entryAt(byteOffsetOfEntry, end - start, cluster)
        }

        // A glyph covers the characters from its own cluster to the next
        // glyph's cluster. More than one *cell* is a ligature: the glyph is
        // drawn once, over the cells it covers, and those cells draw nothing
        // of their own.
        for (index in 0 until count) {
            val entry = indices[index]
            if (entry < start || entry >= end) {
                continue
            }
            val nextEntry = if (index + 1 < count) indices[index + 1] else end
            if (nextEntry <= entry) {
                continue
            }
            val firstCell = cellOfEntry[entry]
            val lastCell = cellOfEntry[(nextEntry - 1).coerceIn(entry, end - 1)]
            if (lastCell <= firstCell) {
                continue
            }
            glyphIds[row * columns + firstCell] = shaped[index * 2]
            for (coveredColumn in firstCell + 1..lastCell) {
                covered[row * columns + coveredColumn] = true
            }
        }
    }

    /**
     * The character a byte offset falls on, or the run's length when the
     * offset is not one of the characters'.
     */
    private fun entryAt(offsets: IntArray, length: Int, cluster: Int): Int {
        for (index in 0 until length) {
            if (offsets[index] == cluster) {
                return index
            }
        }
        return length
    }

    /** The length of a codepoint in UTF-8 bytes. */
    private fun utf8Length(codepoint: Int): Int = when {
        codepoint < 0x80 -> 1
        codepoint < 0x800 -> 2
        codepoint < 0x10000 -> 3
        else -> 4
    }

    private companion object {
        /** The core's shaped-glyph buffer: a glyph id and an offset each. */
        const val SHAPE_BUFFER_INTS = 512

        /** FNV's offset basis, so the hash of an empty row is not zero. */
        const val FNV_OFFSET_BASIS = -3750763034362895579L

        /** FNV's prime. */
        const val FNV_PRIME = 1099511628211L
    }
}
