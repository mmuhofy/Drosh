package dev.drosh.terminal.engine.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One frame of the terminal: a header and a flat array of cells.
 *
 * The bytes are owned by the frame, not by the native library: [parse] copies
 * the header out and keeps the buffer, so the native side is free to reuse its
 * own buffer the moment the call returns. That is the ownership rule the whole
 * boundary rests on — nothing is shared, so nothing can be read after it moved.
 *
 * [valid] is the only check the renderer needs. A frame that fails it is not
 * drawn; the previous picture stays up, which is what a terminal should do when
 * its engine has nothing coherent to say.
 */
class Frame private constructor(
    private val buffer: ByteBuffer,
    val sequence: Int,
    val columns: Int,
    val rows: Int,
    val rowCount: Int,
    val overscanAbove: Int,
    val overscanBelow: Int,
    val cursorColumn: Int,
    val cursorRow: Int,
    val cursorStyle: Int,
    val cursorVisible: Boolean,
    val cursorBlinking: Boolean,
    /**
     * True when the cursor is a thin rectangle (bar or underline) rather than a
     * filled cell.
     */
    val cursorIsRectangle: Boolean,
    /** The cursor's colour, packed by [FrameFormat.packRgba]. */
    val cursorColor: Int,
    val dirty: Int,
    val cellCount: Int,
    val cellStride: Int,
) {

    /** True when the header said what this class expects. */
    val valid: Boolean
        get() = columns > 0 && rows > 0 && cellStride >= FrameFormat.CELL_BYTES

    /** True when the whole viewport changed and nothing may be skipped. */
    val fullyDamaged: Boolean
        get() = dirty == FrameFormat.DIRTY_FULL

    /**
     * The viewport, top to bottom, base codepoint per cell.
     *
     * Overscan rows are skipped: this is what the user sees, not the rows a
     * smooth scroll reveals at the edges. Trailing blanks within a line are
     * stripped; the caller decides what to do with the rest.
     */
    fun lines(): List<String> {
        val lines = ArrayList<String>(rows)
        val first = overscanAbove
        for (row in first until first + rows) {
            val start = row * columns
            val sb = StringBuilder(columns)
            for (col in 0 until columns) {
                val cp = codepoint(start + col)
                if (cp > 0) sb.appendCodePoint(cp) else sb.append(' ')
            }
            lines += sb.toString().trimEnd()
        }
        return lines
    }

    /** True when nothing changed since the previous frame. */
    val clean: Boolean
        get() = dirty == FrameFormat.DIRTY_NONE

    /** The base codepoint of cell [index], or 0 when it has no text. */
    fun codepoint(index: Int): Int = buffer.getInt(offsetOf(index) + FrameFormat.CELL_CODEPOINT)

    /** Resolved foreground, as `0xAARRGGBB`. */
    fun foreground(index: Int): Int = buffer.getInt(offsetOf(index) + FrameFormat.CELL_FOREGROUND)

    /** Resolved background, as `0xAARRGGBB`. */
    fun background(index: Int): Int = buffer.getInt(offsetOf(index) + FrameFormat.CELL_BACKGROUND)

    fun attributes(index: Int): Int = buffer.getInt(offsetOf(index) + FrameFormat.CELL_ATTRIBUTES)

    /** 1 for a normal cell, 2 for a wide character, 0 for a spacer tail. */
    fun width(index: Int): Int =
        buffer.get(offsetOf(index) + FrameFormat.CELL_WIDTH).toInt() and 0xff

    fun flags(index: Int): Int =
        buffer.get(offsetOf(index) + FrameFormat.CELL_FLAGS).toInt() and 0xff

    fun isSelected(index: Int): Boolean =
        flags(index) and FrameFormat.CELL_FLAG_SELECTED != 0

    fun hasGrapheme(index: Int): Boolean =
        flags(index) and FrameFormat.CELL_FLAG_GRAPHEME != 0

    private fun offsetOf(index: Int): Int {
        require(index in 0 until cellCount) { "cell $index outside 0 until $cellCount" }
        return FrameFormat.HEADER_BYTES + index * cellStride
    }

    companion object {

        /**
         * Parse the bytes the native side produced.
         *
         * Returns null when the header does not describe a frame this class
         * understands: a magic mismatch means the two sides disagree about the
         * format, and guessing would draw garbage.
         */
        fun parse(bytes: ByteArray?): Frame? {
            if (bytes == null || bytes.size < FrameFormat.HEADER_BYTES) {
                return null
            }

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            if (buffer.getInt(FrameFormat.OFFSET_MAGIC) != FrameFormat.MAGIC) {
                return null
            }
            val version = buffer.getShort(FrameFormat.OFFSET_VERSION).toInt() and 0xffff
            if (version != FrameFormat.VERSION) {
                return null
            }
            val headerBytes = buffer.getShort(FrameFormat.OFFSET_HEADER_BYTES).toInt() and 0xffff
            if (headerBytes != FrameFormat.HEADER_BYTES) {
                return null
            }

            val columns = buffer.getShort(FrameFormat.OFFSET_COLUMNS).toInt() and 0xffff
            val rows = buffer.getShort(FrameFormat.OFFSET_ROWS).toInt() and 0xffff
            val rowCount = buffer.getShort(FrameFormat.OFFSET_ROW_COUNT).toInt() and 0xffff
            val cellCount = buffer.getInt(FrameFormat.OFFSET_CELL_COUNT)
            val cellStride = buffer.getInt(FrameFormat.OFFSET_CELL_STRIDE)

            // The body has to be there before any cell is read: a truncated
            // array is a frame that must not be drawn.
            val needed = FrameFormat.HEADER_BYTES.toLong() + cellCount.toLong() * cellStride
            if (needed > bytes.size) {
                return null
            }

            val cursorFlags = buffer.getShort(FrameFormat.OFFSET_CURSOR_FLAGS).toInt() and 0xffff

            return Frame(
                buffer = buffer,
                sequence = buffer.getInt(FrameFormat.OFFSET_SEQUENCE),
                columns = columns,
                rows = rows,
                rowCount = rowCount,
                overscanAbove = buffer.getShort(FrameFormat.OFFSET_OVERSCAN_ABOVE).toInt(),
                overscanBelow = buffer.getShort(FrameFormat.OFFSET_OVERSCAN_BELOW).toInt(),
                cursorColumn = buffer.getShort(FrameFormat.OFFSET_CURSOR_X).toInt() and 0xffff,
                cursorRow = buffer.getShort(FrameFormat.OFFSET_CURSOR_Y).toInt() and 0xffff,
                cursorStyle = buffer.getShort(FrameFormat.OFFSET_CURSOR_STYLE).toInt() and 0xffff,
                cursorVisible = cursorFlags and FrameFormat.CURSOR_VISIBLE != 0,
                cursorBlinking = cursorFlags and FrameFormat.CURSOR_BLINKING != 0,
                cursorIsRectangle = cursorFlags and FrameFormat.CURSOR_IS_RECTANGLE != 0,
                cursorColor = buffer.getInt(FrameFormat.OFFSET_CURSOR_COLOR),
                dirty = buffer.getShort(FrameFormat.OFFSET_DIRTY).toInt() and 0xffff,
                cellCount = cellCount,
                cellStride = cellStride,
            )
        }
    }
}
