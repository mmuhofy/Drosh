package dev.drosh.terminal.engine.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scroll's arithmetic, without a phone.
 *
 * The viewport's row and the picture's shift within it are two numbers, and
 * only a whole row moves the viewport. The cases here are the ones that used
 * to go wrong — the picture jumping to a row boundary, the shift pointing the
 * wrong way, and the view not landing on the live edge.
 */
class ScrollMachineTest {

    private val spacing = 20f

    /** A machine sitting on the live edge, which is row 10 of a ten-row history. */
    private fun atLiveEdge(history: Int = 10): ScrollMachine {
        val scroll = ScrollMachine()
        // One event that moves nothing puts the machine on the engine's row.
        scroll.drag(engineOffset = history, historyRows = history, distancePx = 0f, spacingPx = spacing)
        return scroll
    }

    @Test
    fun `a small drag keeps the shift and does not move the viewport`() {
        val scroll = atLiveEdge()
        val target = scroll.drag(
            engineOffset = 10,
            historyRows = 10,
            distancePx = 5f,
            spacingPx = spacing,
        )
        // The viewport stays where it was: a fifth of a row is not a row.
        assertEquals(10, target.row)
        // The picture moved down by the finger's travel, which is what opens the
        // row above — the older one.
        assertEquals(5f, target.remainderPx, 0.0001f)
    }

    @Test
    fun `a whole row of travel moves the viewport and leaves no shift`() {
        val scroll = atLiveEdge()
        val target = scroll.drag(
            engineOffset = 10,
            historyRows = 10,
            distancePx = spacing,
            spacingPx = spacing,
        )
        assertEquals(9, target.row)
        assertEquals(0f, target.remainderPx, 0.0001f)
    }

    @Test
    fun `the shift accumulates across events`() {
        val scroll = atLiveEdge()
        val first = scroll.drag(
            engineOffset = 10,
            historyRows = 10,
            distancePx = 5f,
            spacingPx = spacing,
        )
        assertEquals(5f, first.remainderPx, 0.0001f)

        // The engine did not move, so it is still at the row it was asked for.
        val second = scroll.drag(
            engineOffset = 10,
            historyRows = 10,
            distancePx = 5f,
            spacingPx = spacing,
        )
        assertEquals(10f, second.remainderPx, 0.0001f)
        assertEquals(10, second.row)
    }

    @Test
    fun `dragging back up returns to the live edge exactly`() {
        val scroll = atLiveEdge()
        // Two rows up into the history.
        scroll.drag(engineOffset = 10, historyRows = 10, distancePx = 2 * spacing, spacingPx = spacing)
        // And two rows back down.
        val target = scroll.drag(
            engineOffset = 8,
            historyRows = 10,
            distancePx = -2 * spacing,
            spacingPx = spacing,
        )
        assertEquals(10, target.row)
        assertEquals(0f, target.remainderPx, 0.0001f)
        assertTrue("the view is on the live edge", target.toBottom)
    }

    @Test
    fun `the live edge cannot be scrolled past`() {
        val scroll = atLiveEdge()
        // Dragging up at the live edge would show output that has not been
        // printed yet.
        val target = scroll.drag(
            engineOffset = 10,
            historyRows = 10,
            distancePx = -5f,
            spacingPx = spacing,
        )
        assertEquals(10, target.row)
        assertEquals(0f, target.remainderPx, 0.0001f)
    }

    @Test
    fun `the oldest row cannot be scrolled past`() {
        val scroll = ScrollMachine()
        // Start at the oldest row.
        scroll.drag(engineOffset = 0, historyRows = 10, distancePx = 0f, spacingPx = spacing)
        val target = scroll.drag(
            engineOffset = 0,
            historyRows = 10,
            distancePx = 40f,
            spacingPx = spacing,
        )
        assertEquals(0, target.row)
        assertTrue("no shift past the oldest row", target.remainderPx <= 0f)
    }

    @Test
    fun `a gesture starts from where the engine is`() {
        val scroll = atLiveEdge()
        scroll.drag(engineOffset = 10, historyRows = 10, distancePx = 5f, spacingPx = spacing)
        // New output arrived while the user was not touching the screen: the
        // engine moved to row 11 and the view went with it. The next gesture
        // starts there.
        scroll.sync(engineOffset = 11, historyRows = 11)
        val target = scroll.drag(
            engineOffset = 11,
            historyRows = 11,
            distancePx = 0f,
            spacingPx = spacing,
        )
        assertEquals(11, target.row)
        assertEquals(0f, target.remainderPx, 0.0001f)
    }

    @Test
    fun `a move the engine has not seen yet does not reset the picture`() {
        val scroll = atLiveEdge()
        val first = scroll.drag(
            engineOffset = 10,
            historyRows = 10,
            distancePx = 5f,
            spacingPx = spacing,
        )
        assertEquals(5f, first.remainderPx, 0.0001f)

        // The engine is still on the row the previous event asked for: the
        // move is in flight. The shift is not thrown away for it.
        val second = scroll.drag(
            engineOffset = 10,
            historyRows = 10,
            distancePx = 5f,
            spacingPx = spacing,
        )
        assertEquals(10f, second.remainderPx, 0.0001f)
    }

    @Test
    fun `a zero spacing moves nothing`() {
        val scroll = ScrollMachine()
        val target = scroll.drag(
            engineOffset = 0,
            historyRows = 10,
            distancePx = 20f,
            spacingPx = 0f,
        )
        assertEquals(0, target.row)
        assertEquals(0f, target.remainderPx, 0.0001f)
    }
}
