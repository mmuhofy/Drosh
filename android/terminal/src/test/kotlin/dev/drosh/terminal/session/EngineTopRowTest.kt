package dev.drosh.terminal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's viewport offset, read by the chrome's own rule.
 *
 * The two terminals count the scrollback in opposite directions: the vendored
 * view's `mTopRow` is 0 at the live edge and negative in the history, while
 * the engine's display offset is 0 at the live edge and positive in the
 * history. The chrome's rule was written for the first one, so the sign is
 * the whole of the translation between them — and a sign that is wrong the
 * other way round does not look broken: the chrome collapses when the user is
 * at the prompt and comes back when they read the scrollback.
 *
 * That is why this is pinned here rather than left to the reading of the
 * manager: the numbers themselves are trivial, and the direction is the bug.
 */
class EngineTopRowTest {

    /** The live edge is the live edge, whichever terminal is drawing it. */
    @Test
    fun `an offset of zero is the live edge`() {
        assertEquals(0, engineTopRow(0))
        assertTrue(chromeIsAtLiveEdge(engineTopRow(0), wasAtLiveEdge = false))
    }

    /** Rows in the scrollback are rows above the live edge, not below it. */
    @Test
    fun `rows in the scrollback count upwards`() {
        assertEquals(-1, engineTopRow(1))
        assertEquals(-5, engineTopRow(5))
        assertEquals(-4000, engineTopRow(4000))
    }

    /** Deep in the history the chrome is collapsed, exactly as it is for Termux. */
    @Test
    fun `five rows back collapses the chrome`() {
        assertFalse(chromeIsAtLiveEdge(engineTopRow(5), wasAtLiveEdge = true))
        assertFalse(chromeIsAtLiveEdge(engineTopRow(4000), wasAtLiveEdge = true))
    }

    /**
     * The dead zone is the dead zone on this side too.
     *
     * Two to four rows back is reading the last line or two, and it must not
     * move the chrome in either direction. One row back is already the live
     * edge's own threshold — the chrome comes back there — so it is not part
     * of the dead zone.
     */
    @Test
    fun `the dead zone holds the previous answer`() {
        for (offset in 2..4) {
            val topRow = engineTopRow(offset)
            assertTrue(
                "offset $offset should not collapse a showing chrome",
                chromeIsAtLiveEdge(topRow, wasAtLiveEdge = true),
            )
            assertFalse(
                "offset $offset should not restore a collapsed chrome",
                chromeIsAtLiveEdge(topRow, wasAtLiveEdge = false),
            )
        }
    }

    /** Coming back to the live edge brings the chrome back. */
    @Test
    fun `coming back to the live edge brings the chrome back`() {
        assertTrue(chromeIsAtLiveEdge(engineTopRow(0), wasAtLiveEdge = false))
        assertTrue(chromeIsAtLiveEdge(engineTopRow(1), wasAtLiveEdge = false))
    }

    /**
     * A whole gesture, on the engine's numbers.
     *
     * The same walk the Termux path is pinned on, with the engine's offset
     * instead of the view's row: one change on the way up, one on the way
     * back.
     */
    @Test
    fun `a fling up and back flips the chrome once per direction`() {
        var atEdge = true
        var transitions = 0

        for (offset in 1..30) {
            val next = chromeIsAtLiveEdge(engineTopRow(offset), atEdge)
            if (next != atEdge) transitions++
            atEdge = next
        }
        assertEquals("one change for the whole fling up", 1, transitions)
        assertFalse("history is fullscreen", atEdge)

        for (offset in 29 downTo 0) {
            val next = chromeIsAtLiveEdge(engineTopRow(offset), atEdge)
            if (next != atEdge) transitions++
            atEdge = next
        }
        assertEquals("one change for the whole return", 2, transitions)
        assertTrue("the live edge brings it back", atEdge)
    }
}
