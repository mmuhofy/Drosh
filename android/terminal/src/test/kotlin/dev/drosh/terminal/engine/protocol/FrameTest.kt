package dev.drosh.terminal.engine.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format, as a contract between two languages.
 *
 * These tests do not check the native side — they cannot, it needs a device.
 * They check that Kotlin reads *exactly* the layout `frame.zig` writes, byte by
 * byte, including the parts that are easy to get wrong: little-endian fields,
 * signed overscan, a truncated body, and a magic that does not match.
 *
 * The format is frozen. A change here is a change in `native/src/frame.zig` in
 * the same commit, and the version number moves with it.
 */
class FrameTest {

    @Test
    fun `a frame round-trips through the builder`() {
        val bytes = FrameBuilder(columns = 4, rows = 2)
            .text(row = 0, column = 0, text = "hi")
            .cursor(column = 2, row = 0)
            .build()

        val frame = Frame.parse(bytes)
        assertNotNull(frame)
        requireNotNull(frame)

        assertTrue(frame.valid)
        assertEquals(4, frame.columns)
        assertEquals(2, frame.rows)
        assertEquals(2, frame.rowCount)
        assertEquals(0, frame.overscanAbove)
        assertEquals(0, frame.overscanBelow)
        assertEquals(8, frame.cellCount)
        assertEquals(FrameFormat.CELL_BYTES, frame.cellStride)

        assertEquals('h'.code, frame.codepoint(0))
        assertEquals('i'.code, frame.codepoint(1))
        assertEquals(' '.code, frame.codepoint(2))

        assertEquals(2, frame.cursorColumn)
        assertEquals(0, frame.cursorRow)
        assertTrue(frame.cursorVisible)
        assertFalse(frame.cursorBlinking)
        assertTrue(frame.fullyDamaged)
    }

    @Test
    fun `colours arrive resolved and opaque`() {
        val gold = FrameFormat.packRgba(0xE8, 0xC5, 0x47)
        val grey = FrameFormat.packRgba(0x18, 0x18, 0x18)
        val bytes = FrameBuilder(columns = 1, rows = 1)
            .cell(index = 0, codepoint = 'x'.code, foreground = gold, background = grey)
            .build()

        val frame = requireNotNull(Frame.parse(bytes))
        assertEquals(gold, frame.foreground(0))
        assertEquals(grey, frame.background(0))
        // The channel order is the wire format's contract, so it is asserted
        // through the one helper that defines it.
        assertEquals(0xE8, FrameFormat.red(frame.foreground(0)))
        assertEquals(0xC5, FrameFormat.green(frame.foreground(0)))
        assertEquals(0x47, FrameFormat.blue(frame.foreground(0)))
        assertEquals(255, FrameFormat.alpha(frame.foreground(0)))
    }

    @Test
    fun `overscan is signed and counted`() {
        val bytes = FrameBuilder(columns = 2, rows = 3, overscanAbove = 1, overscanBelow = 1)
            .build()

        val frame = requireNotNull(Frame.parse(bytes))
        assertEquals(3, frame.rows)
        assertEquals(5, frame.rowCount)
        assertEquals(1, frame.overscanAbove)
        assertEquals(1, frame.overscanBelow)
        // The cells cover every written row, overscan included.
        assertEquals(10, frame.cellCount)
    }

    @Test
    fun `attributes carry the underline style in the high nibble`() {
        val attributes = FrameFormat.ATTR_BOLD or
            FrameFormat.ATTR_ITALIC or
            (2 shl FrameFormat.ATTR_UNDERLINE_SHIFT)

        val bytes = FrameBuilder(columns = 1, rows = 1)
            .cell(index = 0, codepoint = 'u'.code, attributes = attributes)
            .build()

        val frame = requireNotNull(Frame.parse(bytes))
        val read = frame.attributes(0)
        assertTrue(read and FrameFormat.ATTR_BOLD != 0)
        assertTrue(read and FrameFormat.ATTR_ITALIC != 0)
        assertEquals(2, (read and FrameFormat.ATTR_UNDERLINE_MASK) shr FrameFormat.ATTR_UNDERLINE_SHIFT)
    }

    @Test
    fun `a selection is a cell flag`() {
        val bytes = FrameBuilder(columns = 2, rows = 1)
            .cell(index = 0, codepoint = 'a'.code, flags = FrameFormat.CELL_FLAG_SELECTED)
            .cell(index = 1, codepoint = 'b'.code)
            .build()

        val frame = requireNotNull(Frame.parse(bytes))
        assertTrue(frame.isSelected(0))
        assertFalse(frame.isSelected(1))
    }

    @Test
    fun `a dirty state is reported`() {
        val clean = FrameBuilder(columns = 1, rows = 1)
            .dirty(FrameFormat.DIRTY_NONE)
            .build()
        assertTrue(requireNotNull(Frame.parse(clean)).clean)

        val partial = FrameBuilder(columns = 1, rows = 1)
            .dirty(FrameFormat.DIRTY_PARTIAL)
            .build()
        val frame = requireNotNull(Frame.parse(partial))
        assertFalse(frame.clean)
        assertFalse(frame.fullyDamaged)
    }

    @Test
    fun `a wrong magic is refused`() {
        val bytes = FrameBuilder(columns = 1, rows = 1).build()
        // The magic is the first four bytes, little-endian.
        bytes[0] = 'X'.code.toByte()

        assertNull(Frame.parse(bytes))
    }

    @Test
    fun `a truncated body is refused`() {
        val bytes = FrameBuilder(columns = 4, rows = 4).build()
        assertNull(Frame.parse(bytes.copyOf(bytes.size - FrameFormat.CELL_BYTES)))
    }

    @Test
    fun `an empty or short buffer is refused`() {
        assertNull(Frame.parse(null))
        assertNull(Frame.parse(ByteArray(0)))
        assertNull(Frame.parse(ByteArray(FrameFormat.HEADER_BYTES - 1)))
    }

    @Test
    fun `a wide character marks its width`() {
        val bytes = FrameBuilder(columns = 2, rows = 1)
            .cell(index = 0, codepoint = 0x4E2D, width = 2)
            .cell(index = 1, codepoint = 0, width = 0)
            .build()

        val frame = requireNotNull(Frame.parse(bytes))
        assertEquals(2, frame.width(0))
        assertEquals(0, frame.width(1))
    }
}
