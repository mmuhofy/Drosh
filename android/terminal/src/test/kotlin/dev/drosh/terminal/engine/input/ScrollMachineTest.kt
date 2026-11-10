package dev.drosh.terminal.engine.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scroll's arithmetic, without a phone.
 *
 * The cases chosen here are the ones the old implementation got wrong: the
 * over-scroll at the live edge, the drift at the far end of the history, the
 * remainder that must survive a whole-row move, and the drift between the
 * machine's own count and the engine's.
 *
 * The engine's row space runs from the oldest row (0) to the live edge
 * (`historyRows`), and the live edge is the *largest* number in it.
 */
class ScrollMachineTest {

    private val spacing = 20f

    @Test
    fun `a small drag keeps the remainder and does not move a row`() {
        val scroll = ScrollMachine()
        // The live edge is row 10 of a history of ten rows.
        val target = scroll.drag(engineOffset = 10, historyRows = 10, distancePx = 5f, spacingPx = spacing)
        assertEquals(10, target.row)
        // Pushed down by five pixels: the row above the viewport shows through.
        assertEquals(5f, target.remainderPx, 0.0001f)
    }

    @Test
    fun `a drag past a row moves the row and keeps the remainder`() {
        val scroll = ScrollMachine()
        val target = scroll.drag(engineOffset = 100, historyRows = 100, distancePx = 50f, spacingPx = spacing)
        // Two and a half rows up: the engine shows the nearest whole row, and
        // the half row that is left over moves the content down.
        assertEquals(98, target.row)
        assertEquals(10f, target.remainderPx, 0.0001f)
    }

    @Test
    fun `the remainder accumulates across events`() {
        val scroll = ScrollMachine()
        // Two events of five pixels each: ten pixels in total, so the picture
        // has moved half a row even though neither event did.
        val first = scroll.drag(engineOffset = 10, historyRows = 10, distancePx = 5f, spacingPx = spacing)
        assertEquals(10, first.row)
        assertEquals(5f, first.remainderPx, 0.0001f)

        // The engine did not move, so it is still at row 10.
        val second = scroll.drag(engineOffset = 10, historyRows = 10, distancePx = 5f, spacingPx = spacing)
        assertEquals(10, second.row)
        assertEquals(10f, second.remainderPx, 0.0001f)
    }

    @Test
    fun `the live edge cannot be over-scrolled`() {
        // Dragging *up* at the live edge would show content below it, and there
        // is none: the remainder must not push the grid past the live edge.
        val scroll = ScrollMachine()
        val target = scroll.drag(engineOffset = 10, historyRows = 10, distancePx = -15f, spacingPx = spacing)
        assertEquals(10, target.row)
        assertTrue("no over-scroll at the live edge", target.remainderPx >= 0f)
    }

    @Test
    fun `the oldest row cannot be over-scrolled`() {
        val scroll = ScrollMachine()
        // Far more than three rows of history.
        val target = scroll.drag(engineOffset = 3, historyRows = 3, distancePx = 500f, spacingPx = spacing)
        assertEquals(0, target.row)
        assertTrue("no drift past the oldest row", target.remainderPx <= 0f)
    }

    @Test
    fun `a move of the engine's own drops the remainder`() {
        val scroll = ScrollMachine()
        scroll.drag(engineOffset = 10, historyRows = 10, distancePx = 5f, spacingPx = spacing)
        // Something else moved the viewport — a program, or the scrollbar.
        val target = scroll.drag(engineOffset = 4, historyRows = 10, distancePx = 0f, spacingPx = spacing)
        assertEquals(4, target.row)
        assertEquals(0f, target.remainderPx, 0.0001f)
    }

    @Test
    fun `a zero spacing moves nothing`() {
        val scroll = ScrollMachine()
        val target = scroll.drag(engineOffset = 0, historyRows = 10, distancePx = 20f, spacingPx = 0f)
        assertEquals(0, target.row)
        assertEquals(0f, target.remainderPx, 0.0001f)
    }
}
