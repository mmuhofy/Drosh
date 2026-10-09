package dev.drosh.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two top-chrome states, as the screen decides between them.
 *
 * `chromeCollapsed` is a plain function rather than an expression inside a
 * composable, so the rule can be walked here. That matters because the rule is
 * not interesting as a value — every individual answer is defensible, and the
 * bug was always a *sequence* of defensable answers arriving in the wrong order.
 * A fling through thirty rows produced a different answer on most of them, and
 * the status bar began and cancelled its own animation each time.
 */
class ChromeScrollTest {

    // ── The live edge ─────────────────────────────────────────────────────────

    /**
     * At the prompt the system status bar is gone and the pills have taken the
     * space it left.
     *
     * `mTopRow == 0` is the whole test. The previous rule ran the other way —
     * the bar was visible at the prompt and hid itself when the user scrolled —
     * so the one thing you want out of the way while typing was the one thing
     * permanently on screen.
     */
    @Test
    fun `at the live edge the chrome is collapsed`() {
        assertTrue(chromeCollapsed(topRow = 0, tuiActive = false, autoHideStatusBar = true))
    }

    /**
     * A session that has just opened is at the live edge too, and gets the same
     * answer for free.
     *
     * This is the case the old rule needed a second flag for. A freshly opened
     * terminal sits at `mTopRow == 0`, exactly like one sitting at a busy prompt,
     * so position alone already says the right thing — "has this ever been
     * scrolled" was a distinction without a difference and cost a StateFlow.
     */
    @Test
    fun `a session that has never been scrolled is collapsed`() {
        assertTrue(chromeCollapsed(topRow = 0, tuiActive = false, autoHideStatusBar = true))
    }

    // ── The scrollback ────────────────────────────────────────────────────────

    @Test
    fun `one row into the scrollback the status bar is back`() {
        assertFalse(chromeCollapsed(topRow = -1, tuiActive = false, autoHideStatusBar = true))
    }

    @Test
    fun `deep into the scrollback it stays back`() {
        assertFalse(chromeCollapsed(topRow = -5, tuiActive = false, autoHideStatusBar = true))
        assertFalse(chromeCollapsed(topRow = -4000, tuiActive = false, autoHideStatusBar = true))
    }

    /**
     * The boundary is at zero and nowhere else.
     *
     * `mTopRow` is an integer, so there is no partial state to resolve and no
     * previous answer to fall back on. The dead zone is off, which means one row
     * of movement is one transition — stated as a test so that reintroducing a
     * threshold has to delete a test rather than slip past one.
     */
    @Test
    fun `the boundary is exactly the live edge`() {
        assertTrue(chromeCollapsed(topRow = 0, tuiActive = false, autoHideStatusBar = true))
        assertFalse(chromeCollapsed(topRow = -1, tuiActive = false, autoHideStatusBar = true))
    }

    // ── TUI ───────────────────────────────────────────────────────────────────

    /**
     * A TUI is collapsed whatever the scroll position says.
     *
     * nano, vim and htop own the alternate screen buffer: fullscreen, no
     * scrollback, nothing to scroll. It is the live edge by definition. Stated
     * rather than left to fall out of the `topRow == 0` arm because it is the
     * case where a clock over somebody else's fullscreen interface is most in
     * the way.
     */
    @Test
    fun `a TUI is always collapsed`() {
        assertTrue(chromeCollapsed(topRow = 0, tuiActive = true, autoHideStatusBar = true))
        assertTrue(chromeCollapsed(topRow = -1, tuiActive = true, autoHideStatusBar = true))
        assertTrue(chromeCollapsed(topRow = -500, tuiActive = true, autoHideStatusBar = true))
    }

    // ── The setting ───────────────────────────────────────────────────────────

    /**
     * Off means the bar stays exactly where it is and nothing moves.
     *
     * Not "collapsed less often" — the pills do not travel either, so the top of
     * the screen is one fixed arrangement whatever the user does with the
     * viewport.
     */
    @Test
    fun `the setting off pins the chrome`() {
        assertFalse(chromeCollapsed(topRow = 0, tuiActive = false, autoHideStatusBar = false))
        assertFalse(chromeCollapsed(topRow = -1, tuiActive = false, autoHideStatusBar = false))
        assertFalse(chromeCollapsed(topRow = -500, tuiActive = true, autoHideStatusBar = false))
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
        var collapsed = chromeCollapsed(0, tuiActive = false, autoHideStatusBar = true)
        var transitions = 0

        for (topRow in -1 downTo -30) {
            val next = chromeCollapsed(topRow, tuiActive = false, autoHideStatusBar = true)
            if (next != collapsed) transitions++
            collapsed = next
        }
        assertEquals("one change for the whole fling up", 1, transitions)
        assertFalse("history shows the status bar", collapsed)

        for (topRow in -29..0) {
            val next = chromeCollapsed(topRow, tuiActive = false, autoHideStatusBar = true)
            if (next != collapsed) transitions++
            collapsed = next
        }
        assertEquals("one change for the whole return", 2, transitions)
        assertTrue("the live edge hides it again", collapsed)
    }

    /**
     * A drag that reverses inside the last few rows still moves it, twice.
     *
     * Said plainly because it is the cost of a dead zone of zero and it is a
     * choice, not an accident: reading one row back is still a scroll, and the
     * alternative — a band where the chrome holds still — is two answers to one
     * question. If it is judged wrong on a device, the fix is a threshold and
     * these two assertions are what should fail.
     */
    @Test
    fun `reading one row back does move the chrome`() {
        var collapsed = chromeCollapsed(0, tuiActive = false, autoHideStatusBar = true)
        var transitions = 0
        for (topRow in listOf(-1, 0, -1, 0)) {
            val next = chromeCollapsed(topRow, tuiActive = false, autoHideStatusBar = true)
            if (next != collapsed) transitions++
            collapsed = next
        }
        assertEquals(4, transitions)
    }

    /**
     * Opening a session and working in it, start to finish.
     *
     * Open → collapsed. Type, run things, watch output arrive: the viewport is
     * pinned at 0 the whole time, so it stays collapsed and the status bar never
     * flickers on a single chunk of PTY output. Scroll up → exactly one change.
     * Come back → exactly one more.
     */
    @Test
    fun `a whole session changes state exactly twice`() {
        var collapsed = chromeCollapsed(0, tuiActive = false, autoHideStatusBar = true)
        var transitions = 0
        assertTrue("a new session opens collapsed", collapsed)

        // Working: every chunk of output reports the live edge.
        repeat(200) {
            val next = chromeCollapsed(0, tuiActive = false, autoHideStatusBar = true)
            if (next != collapsed) transitions++
            collapsed = next
        }
        assertEquals("output alone never moves it", 0, transitions)

        for (topRow in -1 downTo -60) {
            val next = chromeCollapsed(topRow, tuiActive = false, autoHideStatusBar = true)
            if (next != collapsed) transitions++
            collapsed = next
        }
        for (topRow in -59..0) {
            val next = chromeCollapsed(topRow, tuiActive = false, autoHideStatusBar = true)
            if (next != collapsed) transitions++
            collapsed = next
        }
        assertEquals("one up, one back", 2, transitions)
    }
}