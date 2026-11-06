package dev.drosh.terminal.engine.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds a frame the way the native side does, for tests.
 *
 * The two sides agree on a wire format; a test that only ever runs one side
 * proves nothing about the agreement. This builder is the Kotlin half of that
 * agreement written down: the tests use it to produce exactly the bytes
 * `frame.zig` produces, and [Frame.parse] has to read them back.
 *
 * It lives in `main/` rather than `test/` because the smoke screen uses it to
 * decode a frame on a device where the native library may be missing — a
 * hand-built frame is a way to see whether the renderer works when the engine
 * does not.
 */
class FrameBuilder(
    private val columns: Int,
    private val rows: Int,
    private val overscanAbove: Int = 0,
    private val overscanBelow: Int = 0,
) {

    private val rowCount = rows + overscanAbove + overscanBelow
    private val cellCount = rowCount * columns

    private var sequence = 0
    private var cursorColumn = 0
    private var cursorRow = 0
    private var cursorStyle = 0
    private var cursorVisible = true
    private var cursorBlinking = false
    private var dirty = FrameFormat.DIRTY_FULL

    private val cells = ByteBuffer
        .allocate(cellCount * FrameFormat.CELL_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)

    fun sequence(value: Int): FrameBuilder = apply { sequence = value }

    fun cursor(column: Int, row: Int): FrameBuilder = apply {
        cursorColumn = column
        cursorRow = row
    }

    fun cursorVisible(value: Boolean): FrameBuilder = apply { cursorVisible = value }

    fun cursorBlinking(value: Boolean): FrameBuilder = apply { cursorBlinking = value }

    fun cursorStyle(value: Int): FrameBuilder = apply { cursorStyle = value }

    fun dirty(value: Int): FrameBuilder = apply { dirty = value }

    /** Set one cell. `index` is row-major over `rowCount` rows. */
    fun cell(
        index: Int,
        codepoint: Int,
        foreground: Int = 0xFFFFFFFF.toInt(),
        background: Int = 0xFF000000.toInt(),
        attributes: Int = 0,
        width: Int = 1,
        flags: Int = 0,
    ): FrameBuilder = apply {
        require(index in 0 until cellCount) { "cell $index outside 0 until $cellCount" }
        val at = index * FrameFormat.CELL_BYTES
        cells.putInt(at + FrameFormat.CELL_CODEPOINT, codepoint)
        cells.putInt(at + FrameFormat.CELL_FOREGROUND, foreground)
        cells.putInt(at + FrameFormat.CELL_BACKGROUND, background)
        cells.putInt(at + FrameFormat.CELL_ATTRIBUTES, attributes)
        cells.put(at + FrameFormat.CELL_WIDTH, width.toByte())
        cells.put(at + FrameFormat.CELL_FLAGS, flags.toByte())
    }

    /** Write a string into row [row], starting at [column]. */
    fun text(row: Int, column: Int, text: String): FrameBuilder = apply {
        var at = column
        text.codePoints().forEach { codepoint ->
            if (at < columns) {
                cell(row * columns + at, codepoint)
                at += 1
            }
        }
    }

    fun build(): ByteArray {
        val out = ByteBuffer
            .allocate(FrameFormat.HEADER_BYTES + cellCount * FrameFormat.CELL_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)

        out.putInt(FrameFormat.OFFSET_MAGIC, FrameFormat.MAGIC)
        out.putShort(FrameFormat.OFFSET_VERSION, FrameFormat.VERSION.toShort())
        out.putShort(FrameFormat.OFFSET_HEADER_BYTES, FrameFormat.HEADER_BYTES.toShort())
        out.putInt(FrameFormat.OFFSET_SEQUENCE, sequence)
        out.putShort(FrameFormat.OFFSET_COLUMNS, columns.toShort())
        out.putShort(FrameFormat.OFFSET_ROWS, rows.toShort())
        out.putShort(FrameFormat.OFFSET_ROW_COUNT, rowCount.toShort())
        out.putShort(FrameFormat.OFFSET_OVERSCAN_ABOVE, overscanAbove.toShort())
        out.putShort(FrameFormat.OFFSET_OVERSCAN_BELOW, overscanBelow.toShort())
        out.putShort(FrameFormat.OFFSET_CURSOR_X, cursorColumn.toShort())
        out.putShort(FrameFormat.OFFSET_CURSOR_Y, cursorRow.toShort())
        out.putShort(FrameFormat.OFFSET_CURSOR_STYLE, cursorStyle.toShort())
        out.putShort(
            FrameFormat.OFFSET_CURSOR_FLAGS,
            ((if (cursorVisible) FrameFormat.CURSOR_VISIBLE else 0) or
                (if (cursorBlinking) FrameFormat.CURSOR_BLINKING else 0)).toShort(),
        )
        out.putShort(FrameFormat.OFFSET_DIRTY, dirty.toShort())
        out.putInt(FrameFormat.OFFSET_CELL_COUNT, cellCount)
        out.putInt(FrameFormat.OFFSET_CELL_STRIDE, FrameFormat.CELL_BYTES)
        out.putInt(FrameFormat.OFFSET_CELLS_BYTES, cellCount * FrameFormat.CELL_BYTES)

        val body = cells.array()
        System.arraycopy(body, 0, out.array(), FrameFormat.HEADER_BYTES, body.size)

        return out.array()
    }
}
