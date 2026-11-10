package dev.drosh.terminal.engine.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scroll's arithmetic, without a phone.
 *
 * The cases chosen here are the ones the old implementation got wrong: the
 * over-scroll at the live edge, the drift at the far end of the history, and the
 * remainder that must survive a whole-row move.
 */
class ScrollMachineTest {

    private val spacing = 20f

    @Test
    fun `a small drag keeps the remainder and does not move a row`() {
        val scroll = ScrollMachine()
        scroll.setHistory(10)
        assertTrue(scroll.drag(5f, spacing))
        assertEquals(0, scroll.topRow)
        // Pushed down by five pixels: the row above the viewport shows through.
        assertEquals(5f, scroll.offsetPx, 0.0001f)
    }

    @Test
    fun `a drag past a row moves the row and keeps the remainder`() {
        val scroll = ScrollMachine()
        scroll.setHistory(100)
        scroll.drag(50f, spacing)
        assertEquals(-2, scroll.topRow)
        assertEquals(10f, scroll.offsetPx, 0.0001f)
    }

    @Test
    fun `the live edge cannot be over-scrolled`() {
        // The old bug: a negative remainder at topRow 0 pulled the grid up off
        // the live edge into an over-scroll with nothing below to show.
        val scroll = ScrollMachine()
        scroll.setHistory(10)
        scroll.drag(-15f, spacing)
        assertEquals(0, scroll.topRow)
        assertTrue("no over-scroll at the live edge", scroll.offsetPx >= 0f)
    }

    @Test
    fun `the oldest row cannot be over-scrolled`() {
        val scroll = ScrollMachine()
        scroll.setHistory(3)
        // Far more than three rows of history.
        scroll.drag(500f, spacing)
        assertEquals(-3, scroll.topRow)
        assertTrue("no drift past the oldest row", scroll.offsetPx <= 0f)
    }

    @Test
    fun `returning to the live edge clears the remainder`() {
        val scroll = ScrollMachine()
        scroll.setHistory(10)
        scroll.drag(30f, spacing)
        assertEquals(-1, scroll.topRow)
        scroll.toLiveEdge()
        assertEquals(0, scroll.topRow)
        assertEquals(0f, scroll.offsetPx, 0.0001f)
    }

    @Test
    fun `shrinking the history pulls the viewport in`() {
        val scroll = ScrollMachine()
        scroll.setHistory(50)
        scroll.drag(400f, spacing)
        assertEquals(-20, scroll.topRow)

        // The scrollback shrank — the shell printed nothing but the terminal
        // trimmed its history.
        scroll.setHistory(5)
        assertEquals(-5, scroll.topRow)
        assertTrue(scroll.offsetPx >= 0f)
    }

    @Test
    fun `a zero spacing moves nothing`() {
        val scroll = ScrollMachine()
        assertFalse(scroll.drag(20f, 0f))
        assertEquals(0, scroll.topRow)
        assertEquals(0f, scroll.offsetPx, 0.0001f)
    }
}