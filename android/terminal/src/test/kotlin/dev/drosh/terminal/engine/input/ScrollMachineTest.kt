package dev.drosh.terminal.engine.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two numbers a drag produces: whole rows for the engine, and a fraction of
 * a row for the picture.
 *
 * The invariants are the whole reason the machine exists, and each of them is a
 * bug that used to be in the view:
 *
 * - the shift never reaches a whole row (a shift of a row is a row the engine
 *   should have moved);
 * - the picture stops *at* an edge rather than past it (a shift that grew past
 *   the edge and was then dropped is the scroll that pulled itself back);
 * - settling lands on a row boundary with no shift.
 */
class ScrollMachineTest {

    private val row = 20f

    @Test
    fun `a small drag moves the shift and not the engine`() {
        val machine = ScrollMachine()

        val target = machine.drag(distancePx = 6f, rowHeight = row, engineRow = 100, maxRow = 200)

        assertEquals(0, target.delta)
        assertEquals(6f, target.offsetPx, 1e-3f)
        assertFalse(target.pinned)
    }

    @Test
    fun `dragging past a row moves the engine and keeps the remainder`() {
        val machine = ScrollMachine()

        val target = machine.drag(distancePx = 26f, rowHeight = row, engineRow = 100, maxRow = 200)

        // The finger went 26px down: the picture is a row and 6px above where
        // it was, so the engine's window is that one row lower in the history.
        assertEquals(-1, target.delta)
        assertEquals(6f, target.offsetPx, 1e-3f)
    }

    @Test
    fun `the newest row stops flush at the bottom`() {
        val machine = ScrollMachine()

        // Already at the live edge and dragging down: nothing moves, and the
        // machine says so rather than letting the shift grow.
        val target = machine.drag(distancePx = -30f, rowHeight = row, engineRow = 200, maxRow = 200)

        assertEquals(0, target.delta)
        assertEquals(0f, target.offsetPx, 1e-3f)
        assertTrue(target.pinned)
    }

    @Test
    fun `the oldest row stops flush at the top`() {
        val machine = ScrollMachine()

        val target = machine.drag(distancePx = 40f, rowHeight = row, engineRow = 0, maxRow = 200)

        assertEquals(0, target.delta)
        assertEquals(0f, target.offsetPx, 1e-3f)
        assertTrue(target.pinned)
    }

    @Test
    fun `settling lands on a row boundary and drops the shift`() {
        val machine = ScrollMachine()
        machine.drag(distancePx = 26f, rowHeight = row, engineRow = 100, maxRow = 200)

        val settled = machine.settle(rowHeight = row, engineRow = 99, maxRow = 200)

        assertEquals(0f, settled.offsetPx, 1e-3f)
        assertEquals(0f, machine.shift(), 1e-3f)
        // The picture's top edge is 98.7, which is nearest to 99 — the row the
        // engine is already on, so nothing moves.
        assertEquals(0, settled.delta)
    }

    @Test
    fun `a settling paint moves the engine when the picture is more than half a row off`() {
        val machine = ScrollMachine()
        machine.drag(distancePx = 17f, rowHeight = row, engineRow = 100, maxRow = 200)

        val settled = machine.settle(rowHeight = row, engineRow = 100, maxRow = 200)

        assertEquals(-1, settled.delta)
        assertEquals(0f, settled.offsetPx, 1e-3f)
    }

    @Test
    fun `a zero row height is ignored rather than dividing by it`() {
        val machine = ScrollMachine()

        val target = machine.drag(distancePx = 10f, rowHeight = 0f, engineRow = 5, maxRow = 9)

        assertEquals(0, target.delta)
        assertEquals(0f, target.offsetPx, 1e-3f)
    }
}
