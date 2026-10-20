package dev.drosh.terminal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping from a session's raw output to the lines a log shows.
 *
 * Neither terminal can be built in a plain JVM test: the emulator needs a
 * PTY behind it and the engine needs the native core. The mapping is what
 * decides what the user reads, though, and that is a function of text — so
 * it is tested here, on the text, and both sides are pinned to the
 * behaviour the log sheet has today.
 */
class SessionTranscriptTest {

    // ── the Termux side ────────────────────────────────────────────────────

    @Test fun `the trailing spaces of a line are trimmed`() {
        assertEquals(
            listOf("hello", "world"),
            SessionTranscript.Termux.linesOf("hello   \nworld  "),
        )
    }

    @Test fun `a trailing tab is trimmed rather than expanded`() {
        // The trimming comes first, so a tab at the end of a line is gone
        // before the expansion can turn it into four spaces.
        assertEquals(
            listOf("hello"),
            SessionTranscript.Termux.linesOf("hello\t"),
        )
    }

    @Test fun `a tab becomes four spaces`() {
        assertEquals(
            listOf("a    b"),
            SessionTranscript.Termux.linesOf("a\tb"),
        )
    }

    @Test fun `the escape sequences of the scrollback are stripped`() {
        assertEquals(
            listOf("hello"),
            SessionTranscript.Termux.linesOf("\u001b[33mhello\u001b[0m"),
        )
    }

    @Test fun `the blank lines are kept`() {
        assertEquals(
            listOf("a", "", "b"),
            SessionTranscript.Termux.linesOf("a\n\nb"),
        )
    }

    @Test fun `only the last lines are kept`() {
        val raw = (0 until (SessionTranscript.Termux.MAX_LINES + 5))
            .joinToString("\n") { "line $it" }

        val lines = SessionTranscript.Termux.linesOf(raw)

        assertEquals(SessionTranscript.Termux.MAX_LINES, lines.size)
        assertEquals("line 5", lines.first())
        assertEquals("line ${SessionTranscript.Termux.MAX_LINES + 4}", lines.last())
    }

    // ── the engine side ────────────────────────────────────────────────────

    @Test fun `the engine tabs become four spaces`() {
        assertEquals(
            listOf("a    b"),
            SessionTranscript.Engine.linesOf(listOf("a\tb")),
        )
    }

    @Test fun `the engine lines are not stripped again`() {
        // The core's parser has already removed the escape sequences, so a
        // line that prints one as text still has it. A second pass would eat
        // the characters that were meant to be literal.
        val literal = "before\u001b[31mafter"

        assertEquals(
            listOf(literal),
            SessionTranscript.Engine.linesOf(listOf(literal)),
        )
        // The Termux side is the other way round: its scrollback arrives with
        // the sequences in it, so the same text comes out plain there.
        assertEquals(
            listOf("beforeafter"),
            SessionTranscript.Termux.linesOf(literal),
        )
    }

    // ── the two kinds ──────────────────────────────────────────────────────

    @Test fun `a termux transcript is not an engine transcript`() {
        val termux: SessionTranscript = SessionTranscript.Termux(listOf("hello"))
        val engine: SessionTranscript = SessionTranscript.Engine(listOf("hello"))

        assertTrue(termux is SessionTranscript.Termux)
        assertFalse(termux is SessionTranscript.Engine)
        assertTrue(engine is SessionTranscript.Engine)
        assertFalse(engine is SessionTranscript.Termux)
        // The same lines, and still not the same transcript: nothing can
        // present one kind as the other.
        assertNotEquals(termux, engine)
    }

    @Test fun `an empty transcript keeps its kind`() {
        val termux: SessionTranscript = SessionTranscript.Termux(emptyList())
        val engine: SessionTranscript = SessionTranscript.Engine(emptyList())

        assertTrue(termux is SessionTranscript.Termux)
        assertTrue(engine is SessionTranscript.Engine)
        assertNotEquals(termux, engine)
    }
}
