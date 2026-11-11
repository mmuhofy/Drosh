package dev.drosh.terminal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's transcript, in the shape the log sheet shows.
 *
 * The engine's lines arrive already plain: its own parser removed the escape
 * sequences and trimmed the trailing spaces. The one thing that changes here
 * is the tab — it has no width in a text sheet, and the grid it came from is
 * gone, so it becomes four spaces.
 */
class SessionTranscriptTest {

    @Test fun `a tab becomes four spaces`() {
        assertEquals(
            listOf("hello    "),
            SessionTranscript.Engine.linesOf(listOf("hello\t")),
        )
    }

    @Test fun `a tab between two words becomes four spaces`() {
        assertEquals(
            listOf("a    b"),
            SessionTranscript.Engine.linesOf(listOf("a\tb")),
        )
    }

    @Test fun `the lines are kept as they are`() {
        assertEquals(
            listOf("hello", "world"),
            SessionTranscript.Engine.linesOf(listOf("hello", "world")),
        )
    }

    @Test fun `an empty transcript is still a transcript`() {
        val engine: SessionTranscript = SessionTranscript.Engine(emptyList())
        assertTrue(engine is SessionTranscript.Engine)
        assertEquals(0, engine.lines.size)
    }

    @Test fun `a transcript knows what it is`() {
        val engine: SessionTranscript = SessionTranscript.Engine(listOf("hello"))
        assertTrue(engine is SessionTranscript.Engine)
        assertFalse(engine.lines.isEmpty())
    }
}
