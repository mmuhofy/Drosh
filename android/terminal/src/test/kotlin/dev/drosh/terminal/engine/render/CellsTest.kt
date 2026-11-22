package dev.drosh.terminal.engine.render

import dev.drosh.terminal.engine.protocol.Frame
import dev.drosh.terminal.engine.protocol.FrameFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The cell storage's arithmetic, without a GPU.
 *
 * The row-based update is only correct if three things line up: the record of
 * a cell is at its row times the columns, the sprites of a row are packed
 * behind the rows before it, and a row that did not change is a run nobody
 * touches. All three are plain arithmetic — the kind that is wrong by one and
 * looks like a background drawn a row away from its text — and all three can
 * be checked here, where a phone is not needed.
 */
class CellsTest {

    /**
     * A frame with the shape and the text the test asks for.
     *
     * The bytes are the wire format exactly: the header, then one record per
     * cell. The text is a single character per cell, written the way the
     * engine writes it.
     */
    private fun frame(
        columns: Int,
        viewportRows: Int,
        overscanAbove: Int = 1,
        overscanBelow: Int = 1,
        text: (row: Int, column: Int) -> Char = { _, _ -> ' ' },
    ): Frame {
        val rowCount = viewportRows + overscanAbove + overscanBelow
        val cellCount = rowCount * columns
        val bytes = ByteBuffer
            .allocate(FrameFormat.HEADER_BYTES + cellCount * FrameFormat.CELL_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)

        bytes.putInt(FrameFormat.OFFSET_MAGIC, FrameFormat.MAGIC)
        bytes.putShort(FrameFormat.OFFSET_VERSION, FrameFormat.VERSION.toShort())
        bytes.putShort(FrameFormat.OFFSET_HEADER_BYTES, FrameFormat.HEADER_BYTES.toShort())
        bytes.putInt(FrameFormat.OFFSET_SEQUENCE, 1)
        bytes.putShort(FrameFormat.OFFSET_COLUMNS, columns.toShort())
        bytes.putShort(FrameFormat.OFFSET_ROWS, viewportRows.toShort())
        bytes.putShort(FrameFormat.OFFSET_ROW_COUNT, rowCount.toShort())
        bytes.putShort(FrameFormat.OFFSET_OVERSCAN_ABOVE, overscanAbove.toShort())
        bytes.putShort(FrameFormat.OFFSET_OVERSCAN_BELOW, overscanBelow.toShort())
        bytes.putShort(FrameFormat.OFFSET_DIRTY, 1) // full
        bytes.putInt(FrameFormat.OFFSET_CELL_COUNT, cellCount)
        bytes.putInt(FrameFormat.OFFSET_CELL_STRIDE, FrameFormat.CELL_BYTES)
        bytes.putInt(FrameFormat.OFFSET_CELLS_BYTES, cellCount * FrameFormat.CELL_BYTES)
        bytes.putShort(FrameFormat.OFFSET_FIRST_ROW_Y, (-overscanAbove).toShort())

        for (row in 0 until rowCount) {
            for (column in 0 until columns) {
                val at = FrameFormat.HEADER_BYTES +
                    (row * columns + column) * FrameFormat.CELL_BYTES
                val viewportRow = row - overscanAbove
                val character = text(viewportRow, column)
                // A space is what an empty cell is: the engine writes zero.
                bytes.putInt(at + FrameFormat.CELL_CODEPOINT, if (character == ' ') 0 else character.code)
                bytes.putInt(at + FrameFormat.CELL_FOREGROUND, 0xFFE8E8E8.toInt())
                bytes.putInt(at + FrameFormat.CELL_BACKGROUND, 0xFF101010.toInt())
                bytes.put(at + FrameFormat.CELL_WIDTH, 1.toByte())
            }
        }
        val parsed = Frame.parse(bytes.array())
        requireNotNull(parsed) { "the test's own frame did not parse" }
        return parsed
    }

    /** One sprite per cell that has text, the way the renderer writes them. */
    private fun writer(frame: Frame): (ByteBuffer, Int, Int, Int) -> Boolean =
        { buffer, index, column, gridRow ->
            if (frame.codepoint(index) == 0) {
                false
            } else {
                buffer.putShort(0.toShort())
                buffer.putShort(0.toShort())
                buffer.putShort(8.toShort())
                buffer.putShort(16.toShort())
                buffer.putShort(0.toShort())
                buffer.putShort(0.toShort())
                buffer.putShort(column.toShort())
                buffer.putShort(gridRow.toShort())
                buffer.putInt(0xFFE8E8E8.toInt())
                buffer.putShort(0.toShort())
                buffer.putShort(1.toShort())
                true
            }
        }

    @Test
    fun `a cell's record is its row times the columns`() {
        val cells = Cells()
        cells.resize(frame(columns = 10, viewportRows = 4))
        // The rows are the captured ones: the four of the viewport and the one
        // above and below it.
        assertEquals(10 * 6, cells.columns * cells.rows)
        // The overscan row above the viewport is row zero, so a cell's record
        // is exactly its own row and column — no translation.
        assertEquals(0, cells.backgroundAt(0, 0))
        assertEquals(2 * Cells.BACKGROUND_BYTES, cells.backgroundAt(0, 2))
        assertEquals(3 * 10 * Cells.BACKGROUND_BYTES, cells.backgroundAt(3, 0))
    }

    @Test
    fun `a background record carries the cell's column and its grid row`() {
        val cells = Cells()
        val frame = frame(columns = 4, viewportRows = 3, text = { _, _ -> ' ' })
        cells.resize(frame)

        // The viewport's first row is the captured row 1, and its grid row is
        // zero: the overscan row above it is grid row -1.
        cells.buildBackgroundRow(
            frame = frame,
            row = 1,
            colour = { 0xFF203040.toInt() },
            underline = { 0 },
            strikethrough = { 0 },
            overline = { 0 },
        )

        val record = cells.backgroundAt(1, 2)
        assertEquals(2, cells.backgrounds.getShort(record).toInt())
        assertEquals(0, cells.backgrounds.getShort(record + 2).toInt())
        // The colour, in the byte order the shader reads: red is the low byte
        // of the packed value, so it is the first byte written.
        assertEquals(0x40, cells.backgrounds.get(record + 4).toInt() and 0xff)
        assertEquals(0x30, cells.backgrounds.get(record + 5).toInt() and 0xff)
        assertEquals(0x20, cells.backgrounds.get(record + 6).toInt() and 0xff)
    }

    @Test
    fun `a background record carries the three rules`() {
        // The rules are the cell's decorations, and a rule the cell does not
        // have is transparent. Their offsets are the whole contract: a stride
        // that is one vec4 out puts every rule in another rule's place, and
        // the screen shows an underline across a line that only struck through.
        val cells = Cells()
        val frame = frame(columns = 4, viewportRows = 3, text = { _, _ -> ' ' })
        cells.resize(frame)

        cells.buildBackgroundRow(
            frame = frame,
            row = 1,
            colour = { 0 },
            underline = { 0xFF111111.toInt() },
            strikethrough = { 0xFF222222.toInt() },
            overline = { 0xFF333333.toInt() },
        )

        val record = cells.backgroundAt(1, 0)
        // Four vec4s after the two u16s: colour, underline, strikethrough,
        // overline.
        assertEquals(0x11, cells.backgrounds.get(record + 8).toInt() and 0xff)
        assertEquals(0x22, cells.backgrounds.get(record + 12).toInt() and 0xff)
        assertEquals(0x33, cells.backgrounds.get(record + 16).toInt() and 0xff)
        assertEquals(20, Cells.BACKGROUND_BYTES)
    }

    @Test
    fun `the sprites are packed per row`() {
        val cells = Cells()
        val frame = frame(columns = 4, viewportRows = 3, text = { _, _ -> 'x' })
        cells.resize(frame)
        cells.buildSprites(frame, 0, writer(frame))

        // Four columns of text in five captured rows — the viewport's three
        // and the overscan row above and below it: twenty sprites, packed.
        assertEquals(4 * 5, cells.spriteCount)
        assertTrue(cells.spritesRebuilt)
        assertEquals(0, cells.spriteUploadStart())
    }

    @Test
    fun `a row that did not change keeps its sprites`() {
        val cells = Cells()
        val frame = frame(columns = 4, viewportRows = 4, text = { row, _ -> if (row == 2) ' ' else 'x' })
        cells.resize(frame)
        cells.buildSprites(frame, 0, writer(frame))
        val full = cells.spriteCount
        assertEquals(4 * 6 - 4, full)

        // Only one row changed: the sprites built are that row's and the ones
        // after it, and the upload starts where that row's sprites start — not
        // at the top. Rows zero, one and two hold four sprites each, so the
        // captured row three starts at the twelfth.
        cells.buildSprites(frame, 3, writer(frame))
        assertEquals(full, cells.spriteCount)
        assertEquals(12 * Cells.SPRITE_BYTES, cells.spriteUploadStart())
    }

    @Test
    fun `a row that grew moves the rows after it`() {
        val cells = Cells()
        val empty = frame(columns = 4, viewportRows = 4, text = { row, _ -> if (row == 1) ' ' else 'x' })
        cells.resize(empty)
        cells.buildSprites(empty, 0, writer(empty))
        val before = cells.spriteCount

        // The row that was blank now has text: every sprite after it moves.
        val filled = frame(columns = 4, viewportRows = 4, text = { _, _ -> 'x' })
        cells.resize(filled)
        cells.buildSprites(filled, 1, writer(filled))
        assertEquals(before + 4, cells.spriteCount)
    }

    @Test
    fun `the frame's dirty rows are the captured rows`() {
        val frame = frame(columns = 2, viewportRows = 2, text = { _, _ -> ' ' })
        // The bitmap is the frame's own: one bit per captured row.
        assertEquals(false, frame.isRowDirty(0))
        // The viewport's first row is the captured row one.
        assertEquals(false, frame.isViewportRowDirty(0))
    }


    @Test
    fun `resize resets the sprite state`() {
        val cells = Cells()
        val small = frame(columns = 4, viewportRows = 3, text = { _, _ -> 'x' })
        cells.resize(small)
        cells.buildSprites(small, 0, writer(small))
        assertTrue(cells.spriteCount > 0)

        val bigger = frame(columns = 5, viewportRows = 3)
        cells.resize(bigger)
        assertEquals(0, cells.spriteCount)
        assertEquals(5 * (3 + 1 + 1), cells.columns * cells.rows)
    }

}
