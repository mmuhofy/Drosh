package dev.drosh.agent.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Framing is driven from literal wire text rather than through a server, because
 * the awkward cases — a proxy closing mid-frame, a keep-alive between chunks —
 * are exactly the ones that do not reproduce on demand.
 */
class SseFrameReaderTest {

    private fun reader() = SseFrameReader()

    @Test
    fun `dispatches accumulated payload on the blank line`() {
        val reader = reader()

        reader.accept("data: hello")
        val payload = reader.accept("")

        assertEquals("hello", payload)
    }

    @Test
    fun `dispatches successive frames`() {
        val reader = reader()

        reader.accept("data: one")
        val first = reader.accept("")
        reader.accept("data: two")
        val second = reader.accept("")

        assertEquals("one", first)
        assertEquals("two", second)
    }

    @Test
    fun `ignores comment keep-alives`() {
        val reader = reader()

        reader.accept(": ping")
        assertNull(reader.accept(""))
        reader.accept("data: real")
        assertEquals("real", reader.accept(""))
    }

    @Test
    fun `ignores id and retry fields`() {
        val reader = reader()

        assertNull(reader.accept("id: 42"))
        assertNull(reader.accept("retry: 3000"))
        assertNull(reader.accept(""))
    }

    @Test
    fun `only one leading space is stripped from data`() {
        val reader = reader()

        reader.accept("data:  two leading spaces")
        assertEquals(" two leading spaces", reader.accept(""))
    }

    @Test
    fun `multi-line data is joined with newlines`() {
        val reader = reader()

        reader.accept("data: line one")
        reader.accept("data: line two")

        assertEquals("line one\nline two", reader.accept(""))
    }

    @Test
    fun `a blank line with nothing buffered yields null`() {
        val reader = reader()

        assertNull(reader.accept(""))
        assertNull(reader.accept(""))
    }

    @Test
    fun `flush recovers a frame that arrived without a trailing blank line`() {
        // A proxy closing mid-frame is how the last tool call used to vanish.
        val reader = reader()
        reader.accept("data: truncated payload")

        assertEquals("truncated payload", reader.flush())
        assertNull(reader.flush())
    }

    @Test
    fun `flush after a dispatched frame yields null`() {
        val reader = reader()
        reader.accept("data: complete")
        reader.accept("")

        assertNull(reader.flush())
    }

    @Test
    fun `the done marker is passed through untouched`() {
        val reader = reader()

        reader.accept("data: [DONE]")
        assertEquals("[DONE]", reader.accept(""))
    }
}
