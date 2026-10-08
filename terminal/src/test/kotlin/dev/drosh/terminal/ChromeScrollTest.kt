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
): Boolean = !hasScrolled || !chromeIsAtLiveEdge(topRow, wasAtLiveEdge)

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

    /** Scrolling once and returning is enough to make it a terminal with a history. */
    @Test
    fun `one scroll then back to the edge is the normal state`() {
        var hasScrolled = false
        var atEdge = true

        hasScrolled = true
        atEdge = chromeIsAtLiveEdge(-2, atEdge)
        assertTrue("scrolled up is collapsed", chromeCollapsed(hasScrolled, -2, atEdge))

        atEdge = chromeIsAtLiveEdge(0, atEdge)
        assertFalse("back at the edge is normal", chromeCollapsed(hasScrolled, 0, atEdge))
    }

    /**
     * The whole gesture, start to finish.
     *
     * Open a session → collapsed. Drag up a little → still collapsed. Keep going
     * into history → collapsed. Come back to the live edge → normal. The status
     * bar changes state exactly twice, and never on the first flick.
     */
    @Test
    fun `a session opens collapsed and reaches normal only by coming back down`() {
        var hasScrolled = false
        var atEdge = true
        var transitions = 0
        var wasCollapsed = chromeCollapsed(hasScrolled, 0, atEdge)

        // Opening, and the first flicks of the thumb.
        for (topRow in listOf(0, -1, -2, -3, -4)) {
            atEdge = chromeIsAtLiveEdge(topRow, atEdge)
            if (topRow != 0) hasScrolled = true
            val collapsed = chromeCollapsed(hasScrolled, topRow, atEdge)
            if (collapsed != wasCollapsed) transitions++
            wasCollapsed = collapsed
        }
        assertEquals("nothing moved during the first flicks", 0, transitions)

        // Up into history.
        for (topRow in -5 downTo -40) {
            atEdge = chromeIsAtLiveEdge(topRow, atEdge)
            val collapsed = chromeCollapsed(hasScrolled, topRow, atEdge)
            if (collapsed != wasCollapsed) transitions++
            wasCollapsed = collapsed
        }
        assertEquals("already collapsed, still collapsed", 0, transitions)

        // Back down to the live edge.
        for (topRow in -39..0) {
            atEdge = chromeIsAtLiveEdge(topRow, atEdge)
            val collapsed = chromeCollapsed(hasScrolled, topRow, atEdge)
            if (collapsed != wasCollapsed) transitions++
            wasCollapsed = collapsed
        }
        assertEquals("one change for the whole gesture", 1, transitions)
        assertFalse("the last state is normal", wasCollapsed)
    }
}
