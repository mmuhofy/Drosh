package dev.drosh.terminal.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The records the engine answers a log search with.
 *
 * The parsing is tested on its own, because the app cannot test it against a
 * live session: the engine is a native library, and a record parsed wrongly
 * shows up as a match in the wrong column — a bug a device run makes hard to
 * read and a unit test makes obvious.
 */
class TranscriptHitTest {

    @Test
    fun a_record_is_read() {
        val hits = TranscriptHit.parse("3:12:15")
        assertEquals(listOf(TranscriptHit(line = 3, colStart = 12, colEnd = 15)), hits)
    }

    @Test
    fun several_records_are_read_in_order() {
        val text = "0:4:7\n1:19:22\n2:0:5"
        val hits = TranscriptHit.parse(text)
        assertEquals(3, hits.size)
        assertEquals(0, hits[0].line)
        assertEquals(4, hits[0].colStart)
        assertEquals(7, hits[0].colEnd)
        assertEquals(1, hits[1].line)
        assertEquals(19, hits[1].colStart)
        assertEquals(2, hits[2].line)
    }

    @Test
    fun an_empty_answer_is_no_hits() {
        assertTrue(TranscriptHit.parse("").isEmpty())
    }

    @Test
    fun an_error_record_is_no_hits() {
        // What the engine answers for an unknown handle. A miss is better than
        // a hit in line zero.
        assertTrue(TranscriptHit.parse("error:3").isEmpty())
    }

    @Test
    fun a_record_that_is_not_in_the_shape_is_skipped() {
        val text = "0:4:7\ngarbage\n1:2\n2:4:7"
        val hits = TranscriptHit.parse(text)
        assertEquals(2, hits.size)
        assertEquals(0, hits[0].line)
        assertEquals(2, hits[1].line)
    }

    @Test
    fun a_record_with_a_non_number_is_skipped() {
        val text = "a:4:7\n0:4:7"
        val hits = TranscriptHit.parse(text)
        assertEquals(listOf(TranscriptHit(line = 0, colStart = 4, colEnd = 7)), hits)
    }
}
