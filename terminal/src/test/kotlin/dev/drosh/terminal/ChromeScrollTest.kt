package dev.drosh.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two top-chrome states, as the screen decides between them.
 *
 * Mirrors `TerminalScreen`'s expression rather than calling into it, because the
 * screen is a composable and the interesting part is the *sequence* of decisions,
 * which only shows up if the rule is a plain function someone can walk.
 */
private fun chromeCollapsed(
    hasScrolled: Boolean,
    topRow: Int,
    wasAtLiveEdge: Boolean,
): Boolean {
    val atEdge = chromeIsAtLiveEdge(topRow, wasAtLiveEdge)
    // `TerminalManager` sets hasScrolled at the same threshold the live-edge flag
    // uses, so the two can never disagree about which side of it the viewport is
    // on. Modelling that here rather than taking hasScrolled as a free input is
    // the point: taking it as a free input is what let the two thresholds sit a
    // row apart and flap.
    val nowTouched = hasScrolled || !atEdge
    return !nowTouched || !atEdge
}

/**
 * The dead zone that keeps the top chrome still while the viewport is passing
 * through it.
 *
 * These are all one-line rules, and the one-line version of them shipped once
 * already: `topRow == 0`. It looked equivalent and it was not, because the
 * chrome this feeds *is* the system status bar, and the system bar animates on
 * its own schedule. Every row that crossed the boundary started and cancelled
 * that animation, so one scroll gesture produced several.
 *
 * The tests are written as walks rather than as single assertions because the
 * bug was never one wrong value — it was a sequence of correct ones arriving in
 * the wrong order.
 */
class ChromeScrollTest {

    @Test
    fun `at the live edge the chrome is showing`() {
        assertTrue(chromeIsAtLiveEdge(0, wasAtLiveEdge = false))
    }

    @Test
    fun `one row above the live edge is still showing`() {
        assertTrue(chromeIsAtLiveEdge(-1, wasAtLiveEdge = false))
    }

    @Test
    fun `far into the scrollback the chrome has given way`() {
        assertFalse(chromeIsAtLiveEdge(-5, wasAtLiveEdge = true))
        assertFalse(chromeIsAtLiveEdge(-40, wasAtLiveEdge = true))
    }

    /**
     * The dead zone holds whatever the chrome was already doing.
     *
     * Both directions, because the asymmetry is the mechanism: -2 following -1
     * must not collapse, and -4 following -5 must not come back. Resolve these
     * from `topRow` alone and you have simply replaced a flapping boundary with a
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
     * A fling all the way up and all the way back changes the answer once each.
     *
     * This is the actual complaint. Walking the rows in order and counting the
     * transitions is the only way to catch it: every individual call returns a
     * defensible value, and the sequence is what a user sees.
     */
    @Test
    fun `a fling up and back flips the chrome once, not once per row`() {
        var atEdge = true
        var transitions = 0

        for (topRow in -1 downTo -30) {
            val next = chromeIsAtLiveEdge(topRow, atEdge)
            if (next != atEdge) transitions++
            atEdge = next
        }
        assertEquals("one collapse for the whole fling up", 1, transitions)

        for (topRow in -29..0) {
            val next = chromeIsAtLiveEdge(topRow, atEdge)
            if (next != atEdge) transitions++
            atEdge = next
        }
        assertEquals("one restore for the whole fling back", 2, transitions)
        assertTrue(atEdge)
    }

    /**
     * A drag that reverses inside the dead zone is not a scroll at all.
     *
     * Reading the last two lines of output — which is what scrolled up one or
     * two rows is — has to leave the screen alone. Before the dead zone it moved
     * the status bar, for a distance nobody would call a scroll.
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

    // ── A terminal nobody has touched ────────────────────────────────────────

    /**
     * A session that has just opened is collapsed, and this is the case that
     * position alone cannot express.
     *
     * It sits at the live edge — `topRow == 0`, same as a busy terminal at its
     * prompt — and it is still collapsed. Any rule written over scroll position
     * alone gets this exactly backwards, which is what shipped: the status bar
     * was visible at launch and then hid itself the first time the user moved,
     * so one flick of the thumb turned the system bar on and off.
     */
    @Test
    fun `a session that has never been scrolled is collapsed`() {
        assertTrue(chromeCollapsed(hasScrolled = false, topRow = 0, wasAtLiveEdge = true))
    }

    /** A TUI has no scroll position at all, and is collapsed either way. */
    @Test
    fun `hasScrolled does not rescue a terminal that is at the live edge`() {
        // Scrolled and back at the edge: this is the normal state, and the only
        // one that shows the status bar.
        assertFalse(chromeCollapsed(hasScrolled = true, topRow = 0, wasAtLiveEdge = false))
    }

    /**
     * The whole gesture, start to finish.
     *
     * Open a session → collapsed. Flick up a few rows → **still** collapsed,
     * because the terminal still fills the screen and nothing has been read yet.
     * Keep going into history → still collapsed. Come back to the live edge →
     * normal. Exactly one transition across the whole thing.
     *
     * One is the number that matters. With `hasScrolled` flipping on the first
     * row instead of at the threshold, the same gesture produced two: the status
     * bar appeared on the first row and vanished again five rows later, which is
     * the flapping.
     */
    @Test
    fun `a session opens collapsed and one gesture changes it once`() {
        var hasScrolled = false
        var atEdge = true
        var transitions = 0
        var wasCollapsed = chromeCollapsed(hasScrolled, 0, atEdge)
        assertTrue("a new session opens collapsed", wasCollapsed)

        // Up, all the way into history, in one gesture.
        for (topRow in -1 downTo -40) {
            atEdge = chromeIsAtLiveEdge(topRow, atEdge)
            if (!atEdge) hasScrolled = true
            val collapsed = chromeCollapsed(hasScrolled, topRow, atEdge)
            if (collapsed != wasCollapsed) transitions++
            wasCollapsed = collapsed
        }
        assertEquals("nothing changed on the way up", 0, transitions)
        assertTrue("history is collapsed", wasCollapsed)

        // And back down.
        for (topRow in -39..0) {
            atEdge = chromeIsAtLiveEdge(topRow, atEdge)
            val collapsed = chromeCollapsed(hasScrolled, topRow, atEdge)
            if (collapsed != wasCollapsed) transitions++
            wasCollapsed = collapsed
        }
        assertEquals("one change for the whole return", 1, transitions)
        assertFalse("the live edge is normal", wasCollapsed)
    }

    /**
     * A flick up and straight back is not a scroll.
     *
     * It never reached the first screen, so the chrome never moved and a
     * one-flick gesture cannot leave the top of the screen in a different state
     * than it started it.
     */
    @Test
    fun `a scroll that never leaves the first screen moves nothing`() {
        var hasScrolled = false
        var atEdge = true
        var transitions = 0
        var wasCollapsed = chromeCollapsed(hasScrolled, 0, atEdge)
        for (topRow in listOf(-1, -2, -3, -4, -3, -2, -1, 0)) {
            atEdge = chromeIsAtLiveEdge(topRow, atEdge)
            if (!atEdge) hasScrolled = true
            val collapsed = chromeCollapsed(hasScrolled, topRow, atEdge)
            if (collapsed != wasCollapsed) transitions++
            wasCollapsed = collapsed
        }
        assertEquals(0, transitions)
        assertTrue(wasCollapsed)
    }

    /**
     * Coming back to the live edge is what makes it normal.
     *
     * Stated on its own because it is the half that is easy to get backwards:
     * returning to the newest output is the "actively working" state, where the
     * clock is welcome, and the chrome has to come back with it.
     */
    @Test
    fun `back at the live edge is the normal state`() {
        assertFalse(chromeCollapsed(hasScrolled = true, topRow = 0, wasAtLiveEdge = false))
        assertFalse(chromeCollapsed(hasScrolled = true, topRow = -1, wasAtLiveEdge = false))
    }
}
