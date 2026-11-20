package dev.drosh.terminal.session

import dev.drosh.domain.block.BlockEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The Kotlin half of the wire contract in `native/src/block.rs`.
 *
 * These tests build records the way the Rust recorder does — hex-encoded text,
 * raw SGR in the output field — and check that the bridge's parser recovers the
 * same bytes and styles.
 */
class BlockWireParserTest {

    private val hex = BlockWireParser::encodeHex

    @Test
    fun `hex round trips wide text and control bytes`() {
        val original = "❯ ~/Drosh\t你好👋\n".toByteArray(Charsets.UTF_8)
        val decoded = BlockWireParser.decodeHex(hex(original))
        assertEquals(original.toList(), decoded?.toList())
        assertNull(BlockWireParser.decodeHex("abc"))
        assertNull(BlockWireParser.decodeHex("zz"))
    }

    @Test
    fun `records parse into started output and finished`() {
        val wire = listOf(
            "S 1 ${hex("prompt$ ".toByteArray())} ${hex("ls".toByteArray())} " +
                "${hex("/home/me".toByteArray())} 1000",
            "O 1 ${hex("one\n\u001b[31mtwo\u001b[0m".toByteArray())}",
            "F 1 7 2000",
        ).joinToString("\n")

        val records = BlockWireParser.parse(wire, SgrStyledLineParser())
        assertEquals(3, records.size)

        val started = records[0] as ParsedRecord.Started
        assertEquals("1", started.id)
        assertEquals("prompt$ ", started.prompt)
        assertEquals("ls", started.command)
        assertEquals("/home/me", started.cwd)
        assertEquals(1000L, started.startedAtMs)

        val output = records[1] as ParsedRecord.Output
        assertEquals("1", output.id)
        assertEquals("one\n\u001b[31mtwo\u001b[0m", output.text)
        assertEquals(listOf("one", "two"), output.lines.map { line ->
            line.spans.joinToString("") { it.text }
        })

        val finished = records[2] as ParsedRecord.Finished
        assertEquals("1", finished.id)
        assertEquals(7, finished.exitCode)
        assertEquals(2000L, finished.completedAtMs)

        val events = BlockWireParser.toEvents(records)
        assertEquals(BlockEvent.Started::class, events[0]::class)
        assertEquals(BlockEvent.Output::class, events[1]::class)
        assertEquals(BlockEvent.Finished::class, events[2]::class)
    }

    @Test
    fun `style is frozen per span and carried across lines and chunks`() {
        val parser = SgrStyledLineParser()
        val first = parser.parse("\u001b[1;31mhello\n")
        val hello = first.single().spans.single()
        assertEquals("hello", hello.text)
        assertEquals((0xffcd3131).toInt(), hello.fg)
        assertEquals(true, hello.bold)

        // The style set in the previous chunk still applies; resetting it after
        // "world" must not rewrite the span that already holds that text.
        val second = parser.parse("world\u001b[0m\nplain\n")
        val world = second[0].spans.single()
        assertEquals("world", world.text)
        assertEquals((0xffcd3131).toInt(), world.fg)
        assertEquals(true, world.bold)
        val plain = second[1].spans.single()
        assertEquals("plain", plain.text)
        assertNull(plain.fg)
        assertEquals(false, plain.bold)
    }

    @Test
    fun `a csi split across chunks is not printed as text`() {
        val parser = SgrStyledLineParser()
        val head = parser.parse("\u001b[38;5;")
        assertEquals(emptyList<Any>(), head)
        val tail = parser.parse("196mgreen\n")
        val span = tail.single().spans.single()
        assertEquals("green", span.text)
        assertEquals((0xffff0000).toInt(), span.fg)
    }

    @Test
    fun `malformed records are ignored`() {
        val wire = listOf(
            "X nope",
            "S 1 deadbeef",
            "O 1",
            "F 1 x 2",
            "F 9 0 3000",
        ).joinToString("\n")
        val records = BlockWireParser.parse(wire, SgrStyledLineParser())
        assertEquals(1, records.size)
        assertEquals(0, (records.single() as ParsedRecord.Finished).exitCode)
    }
}
