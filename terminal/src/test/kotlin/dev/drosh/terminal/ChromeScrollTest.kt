package dev.drosh.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

        for (topRow in -1..-30) {
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
        for (topRow in 0..-2..-1) {
            val next = chromeIsAtLiveEdge(topRow, atEdge)
            if (next != atEdge) transitions++
            atEdge = next
        }
        assertEquals(0, transitions)
        assertTrue(atEdge)
    }
}