package dev.drosh.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two top-chrome states, as the screen decides between them.
 *
 * The rule is `chromeCollapsed` in the terminal module rather than an expression
 * inside a composable, so it can be walked here. That matters because the rule is
 * not interesting as a value: every individual answer is defensible, and the bug
 * was always a *sequence* of defensable answers arriving in the wrong order.
 *
 * The polarity these tests pin is the whole point. Collapsed is the **up** state
 * — the scrollback — and expanded is the **live edge**. The other way round, a
 * clock sat permanently over the prompt and vanished the moment the user scrolled
 * back to read something.
 */
class ChromeScrollTest {

    /** Mirrors the screen: the manager resolves the hysteresis, then the rule. */
    private fun collapsed(
        topRow: Int,
        wasAtLiveEdge: Boolean,
        tuiActive: Boolean = false,
    ): Boolean =
        chromeCollapsed(topRow, chromeIsAtLiveEdge(topRow, wasAtLiveEdge), tuiActive)

    // ── The scrollback ────────────────────────────────────────────────────────

    /**
     * Up in the scrollback the system status bar is gone and the pills have taken
     * the space it left.
     *
     * This is the direction the whole feature exists for, and the one an earlier
     * revision got backwards.
     */
    @Test
    fun `up in the scrollback the chrome is collapsed`() {
        assertTrue(collapsed(topRow = -5, wasAtLiveEdge = true))
        assertTrue(collapsed(topRow = -4000, wasAtLiveEdge = true))
    }

    // ── The live edge ─────────────────────────────────────────────────────────

    /**
     * At the prompt the status bar is back and the pills sit below it.
     *
     * That is where they belong: the user is typing, not reading, and the clock is
     * not in the way of anything they need.
     */
    @Test
    fun `at the live edge the status bar is back`() {
        assertFalse(collapsed(topRow = 0, wasAtLiveEdge = false))
        assertFalse(collapsed(topRow = -1, wasAtLiveEdge = false))
    }

    /**
     * A session that has just opened gets the same answer, and needs no flag of
     * its own to get it.
     *
     * It sits at `topRow == 0`, exactly like one sitting at a busy prompt, so
     * position already says the right thing — "has this ever been scrolled" was a
     * distinction without a difference and cost a StateFlow.
     */
    @Test
    fun `a session that has never been scrolled is at the live edge`() {
        assertFalse(collapsed(topRow = 0, wasAtLiveEdge = true))
    }

    // ── The dead zone ─────────────────────────────────────────────────────────

    /**
     * The dead zone holds whatever the chrome was already doing.
     *
     * Both directions, because the asymmetry is the mechanism: -2 following -1
     * must not collapse, and -4 following -5 must not come back. Resolve these
     * from `topRow` alone and you have replaced a flapping boundary with a
     * jittering one.
     */
    @Test
    fun `the dead zone keeps the previous answer`() {
        for (topRow in -4..-2) {
            assertTrue(
                "topRow $topRow should not collapse a showing chrome",
                chromeIsAtLiveEdge(topRow, wasAtLiveEdge = true),
            )
            assertFalse(
                "topRow $topRow should not restore a collapsed chrome",
                chromeIsAtLiveEdge(topRow, wasAtLiveEdge = false),
            )
        }
    }

    /**
     * Reading one or two rows back does not move the chrome.
     *
     * That is what the dead zone is for. Reading the last line or two of output —
     * which is what scrolled up one or two rows is — has to leave the screen
     * alone; without it the status bar moves for a distance nobody would call a
     * scroll.
     */
    @Test
    fun `reading one or two rows back does not move the chrome`() {
        var atEdge = true
        var transitions = 0
        for (topRow in 0 downTo -1) {
            val next = chromeIsAtLiveEdge(topRow, atEdge)
            if (next != atEdge) transitions++
            atEdge = next
        }
        assertEquals(0, transitions)
        assertTrue(atEdge)
    }

    // ── TUI ───────────────────────────────────────────────────────────────────

    /**
     * A TUI is collapsed whatever the scroll position says.
     *
     * nano, vim and htop own the alternate screen buffer: fullscreen, no
     * scrollback, nothing to scroll. Stated rather than left to fall out of the
     * `topRow` arm because it is the case where a clock over somebody else's
     * fullscreen interface is most in the way.
     */
    @Test
    fun `a TUI is always collapsed`() {
        assertTrue(collapsed(topRow = 0, wasAtLiveEdge = false, tuiActive = true))
        assertTrue(collapsed(topRow = -1, wasAtLiveEdge = false, tuiActive = true))
        assertTrue(collapsed(topRow = -500, wasAtLiveEdge = true, tuiActive = true))
    }

    // ── The whole gesture ─────────────────────────────────────────────────────

    /**
     * A fling up and all the way back changes the answer once per direction.
     *
     * This is the actual complaint, and counting transitions over a walk is the
     * only way to catch it: every individual call in the loop returns a
     * defensible value and the sequence is what a user sees.
     */
    @Test
    fun `a fling up and back flips the chrome once per direction`() {
        var atEdge = true
        var transitions = 0

        for (topRow in -1 downTo -30) {
            val next = chromeIsAtLiveEdge(topRow, atEdge)
            if (next != atEdge) transitions++
            atEdge = next
        }
        assertEquals("one change for the whole fling up", 1, transitions)
        assertFalse("history is fullscreen", atEdge)

        for (topRow in -29..0) {
            val next = chromeIsAtLiveEdge(topRow, atEdge)
            if (next != atEdge) transitions++
            atEdge = next
        }
        assertEquals("one change for the whole return", 2, transitions)
        assertTrue("the live edge brings it back", atEdge)
    }

    /**
     * The whole session, start to finish.
     *
     * Open → at the live edge, status bar showing, and it stays that way through
     * every chunk of PTY output, because output arrives with the viewport still
     * at the live edge. Flick up into history → **one** change. Come back → **one**
     * more.
     *
     * Two is the number that matters. With the thresholds a row apart the same
     * gesture produced three: the status bar appeared on the first row, vanished
     * again five rows later, and came back on the way down.
     */
    @Test
    fun `a whole session changes state exactly twice`() {
        var atEdge = true
        var wasCollapsed = collapsed(0, atEdge)
        var transitions = 0
        assertFalse("a new session opens at the live edge", wasCollapsed)

        // Working: every chunk of output reports the live edge.
        repeat(200) {
            atEdge = chromeIsAtLiveEdge(0, atEdge)
            val collapsed = collapsed(0, atEdge)
            if (collapsed != wasCollapsed) transitions++
            wasCollapsed = collapsed
        }
        assertEquals("output alone never moves it", 0, transitions)

        for (topRow in -1 downTo -60) {
            atEdge = chromeIsAtLiveEdge(topRow, atEdge)
            val collapsed = collapsed(topRow, atEdge)
            if (collapsed != wasCollapsed) transitions++
            wasCollapsed = collapsed
        }
        for (topRow in -59..0) {
            atEdge = chromeIsAtLiveEdge(topRow, atEdge)
            val collapsed = collapsed(topRow, atEdge)
            if (collapsed != wasCollapsed) transitions++
            wasCollapsed = collapsed
        }
        assertEquals("one up, one back", 2, transitions)
        assertFalse("the live edge is the normal state", wasCollapsed)
    }

    /**
     * A flick up and straight back is not a scroll.
     *
     * It never reached the first screen, so the chrome never moved and a one-flick
     * gesture cannot leave the top of the screen in a different state than it
     * started it.
     */
    @Test
    fun `a scroll that never leaves the first screen moves nothing`() {
        var atEdge = true
        var wasCollapsed = collapsed(0, atEdge)
        var transitions = 0
        for (topRow in listOf(-1, -2, -3, -4, -3, -2, -1, 0)) {
            atEdge = chromeIsAtLiveEdge(topRow, atEdge)
            val collapsed = collapsed(topRow, atEdge)
            if (collapsed != wasCollapsed) transitions++
            wasCollapsed = collapsed
        }
        assertEquals(0, transitions)
        assertFalse(wasCollapsed)
    }
}
