package dev.drosh.terminal.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cell-colour records the engine answers with.
 *
 * The app paints its chrome in the terminal's own colours, so a record read
 * wrongly shows up as a backdrop in the wrong colour — a bug a device run
 * makes hard to read, and a unit test makes obvious.
 */
class CellColorTest {

    @Test
    fun a_record_is_read() {
        val cells = CellColor.parse("0:0:256:257")
        assertEquals(1, cells.size)
        assertEquals(0, cells[0].row)
        assertEquals(0, cells[0].column)
        assertEquals(256, cells[0].foreground)
        assertEquals(257, cells[0].background)
    }

    @Test
    fun several_records_are_read_in_order() {
        val text = "0:4:256:257\n1:19:1073742020:1073742020"
        val cells = CellColor.parse(text)
        assertEquals(2, cells.size)
        assertEquals(0, cells[0].row)
        assertEquals(4, cells[0].column)
        assertEquals(1, cells[1].row)
        assertEquals(19, cells[1].column)
        assertEquals(1073742020, cells[1].foreground)
    }

    @Test
    fun an_empty_answer_is_no_cells() {
        assertTrue(CellColor.parse("").isEmpty())
    }

    @Test
    fun an_error_record_is_no_cells() {
        // What the engine answers for an unknown handle. A miss is better than
        // a cell in row zero.
        assertTrue(CellColor.parse("error:-1").isEmpty())
    }

    @Test
    fun a_record_that_is_not_in_the_shape_is_skipped() {
        val text = "0:4:256:257\ngarbage\n1:2\n2:4:256:257"
        val cells = CellColor.parse(text)
        assertEquals(2, cells.size)
        assertEquals(0, cells[0].row)
        assertEquals(2, cells[1].row)
    }

    @Test
    fun a_record_with_a_non_number_is_skipped() {
        val text = "a:4:256:257\n0:4:256:257"
        val cells = CellColor.parse(text)
        assertEquals(1, cells.size)
        assertEquals(0, cells[0].row)
    }

    @Test
    fun a_colour_that_is_larger_than_a_signed_int_keeps_its_bits() {
        // The "truecolour" tag sits in the top bit, so an encoded colour is
        // larger than `Int.MAX_VALUE` — the bits have to survive the trip.
        val cells = CellColor.parse("0:0:2147483648:2147483649")
        assertEquals(1, cells.size)
        assertEquals(-2147483648, cells[0].foreground)
        assertEquals(-2147483647, cells[0].background)
    }

    @Test
    fun a_colour_that_is_not_a_32_bit_value_is_skipped() {
        assertTrue(CellColor.parse("0:0:4294967296:257").isEmpty())
        assertTrue(CellColor.parse("0:0:256:4294967296").isEmpty())
    }
}
