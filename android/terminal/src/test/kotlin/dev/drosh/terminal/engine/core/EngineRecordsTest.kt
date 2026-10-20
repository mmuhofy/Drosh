package dev.drosh.terminal.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The records the engine answers with.
 *
 * The search and the URL capabilities share this shape, and a record parsed
 * wrongly shows up as a highlight in the wrong column — a bug a device run
 * makes hard to read, and a unit test makes obvious.
 */
class EngineRecordsTest {

    @Test
    fun a_record_is_read() {
        assertEquals(listOf(Triple(3, 12, 7)), EngineRecords.parseTriples("3:12:7"))
    }

    @Test
    fun several_records_are_read_in_order() {
        val text = "0:4:3\n1:19:3\n2:0:5"
        assertEquals(
            listOf(Triple(0, 4, 3), Triple(1, 19, 3), Triple(2, 0, 5)),
            EngineRecords.parseTriples(text),
        )
    }

    @Test
    fun an_empty_answer_is_no_hits() {
        assertTrue(EngineRecords.parseTriples("").isEmpty())
    }

    @Test
    fun an_error_record_is_no_hits() {
        // What the engine answers for an unknown handle. A miss is better than
        // a hit in row zero.
        assertTrue(EngineRecords.parseTriples("error:3").isEmpty())
    }

    @Test
    fun a_record_that_is_not_in_the_shape_is_skipped() {
        val text = "0:4:3\ngarbage\n1:2\n2:4:3"
        assertEquals(
            listOf(Triple(0, 4, 3), Triple(2, 4, 3)),
            EngineRecords.parseTriples(text),
        )
    }

    @Test]
    fun a_record_with_a_non_number_is_skipped() {
        val text = "a:4:3\n0:4:3"
        assertEquals(listOf(Triple(0, 4, 3)), EngineRecords.parseTriples(text))
    }

    @Test
    fun a_url_record_is_read() {
        val hits = EngineRecords.parseUrlHits("2:10:19:https://example.com")
        assertEquals(1, hits.size)
        assertEquals(UrlHit(row = 2, col = 10, len = 19, text = "https://example.com"), hits[0])
    }

    @Test
    fun a_url_may_hold_colons_of_its_own() {
        // `https://` has two of them, and splitting the record into four
        // fields would cut the link in half.
        val hits = EngineRecords.parseUrlHits("0:0:21:https://example.com/a:b")
        assertEquals(1, hits.size)
        assertEquals("https://example.com/a:b", hits[0].text)
        assertEquals(21, hits[0].len)
    }

    @Test
    fun several_url_records_are_read_in_order() {
        val text = "0:4:19:https://example.com\n1:0:13:http://a.b/c"
        val hits = EngineRecords.parseUrlHits(text)
        assertEquals(2, hits.size)
        assertEquals(0, hits[0].row)
        assertEquals("https://example.com", hits[0].text)
        assertEquals(1, hits[1].row)
        assertEquals("http://a.b/c", hits[1].text)
    }

    @Test
    fun an_error_record_is_no_url_hits() {
        assertTrue(EngineRecords.parseUrlHits("error:3").isEmpty())
    }

    @Test
    fun a_url_record_without_a_text_is_skipped() {
        assertTrue(EngineRecords.parseUrlHits("0:4:19:").isEmpty())
    }
}
