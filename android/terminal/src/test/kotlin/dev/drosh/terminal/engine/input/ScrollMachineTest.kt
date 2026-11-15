package dev.drosh.terminal.engine.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scroll's arithmetic, without a phone.
 *
 * The model is two numbers: the engine moves by whole rows and the picture
 * hangs at most one row above the engine's window. The cases here are the
 * ones that used to go wrong — the picture jumping to a row boundary while
 * the finger was still down, the shift pointing the wrong way, and the ends
 * of the scrollback not fitting exactly.
 */
class ScrollMachineTest {

    private val rowHeight = 20f

    /**
     * The engine's row, as the caller sees it.
     *
     * The engine moves by the delta the machine asks for, so the row the next
     * drag starts from is the row the last one landed on.
     */
    private class Engine(var row: Int)

    @Test
    fun `a small drag keeps the shift and moves no row`() {
        val scroll = ScrollMachine()
        val engine = Engine(50)
        val target = scroll.drag(
            distancePx = 5f,
            rowHeight = rowHeight,
            engineRow = engine.row,
            maxRow = 100,
        )
        // The viewport stays where it was: a quarter of a row is not a row.
        assertEquals(0, target.delta)
        // The picture moved down by the finger's travel, which is what opens
        // the row above — the older one.
        assertEquals(5f, target.offsetPx, 0.0001f)
    }

    @Test
    fun `a whole row of travel moves the engine and leaves no shift`() {
        val scroll = ScrollMachine()
        val engine = Engine(50)
        val target = scroll.drag(
            distancePx = rowHeight,
            rowHeight = rowHeight,
            engineRow = engine.row,
            maxRow = 100,
        )
        // One row up into the history: the engine counts its rows from the
        // top of the scrollback, so the history is the negative direction.
        assertEquals(-1, target.delta)
        assertEquals(0f, target.offsetPx, 0.0001f)
    }

    @Test
    fun `the shift follows the finger exactly while it is down`() {
        val scroll = ScrollMachine()
        val engine = Engine(50)
        // Three quarters of a row, in three steps. The picture hangs the
        // whole travel above the engine's window: it is not rounded away
        // while the finger is on the screen.
        repeat(3) {
            val target = scroll.drag(
                distancePx = 5f,
                rowHeight = rowHeight,
                engineRow = engine.row,
                maxRow = 100,
            )
            engine.row += target.delta
            assertEquals(5f * (it + 1), target.offsetPx, 0.0001f)
            assertEquals(0, target.delta)
        }
    }

    @Test
    fun `the shift never leaves a row`() {
        val scroll = ScrollMachine()
        val engine = Engine(50)
        repeat(50) {
            val target = scroll.drag(
                distancePx = 7f,
                rowHeight = rowHeight,
                engineRow = engine.row,
                maxRow = 100,
            )
            engine.row += target.delta
            assertTrue(
                "the shift stays inside a row",
                target.offsetPx >= 0f && target.offsetPx < rowHeight,
            )
        }
    }

    @Test
    fun `dragging the other way moves toward the live edge`() {
        val scroll = ScrollMachine()
        val engine = Engine(50)
        // Down into the history by a row and a half: the engine moves a whole
        // row, and half a row of the travel is left hanging above it.
        val first = scroll.drag(
            distancePx = 1.5f * rowHeight,
            rowHeight = rowHeight,
            engineRow = engine.row,
            maxRow = 100,
        )
        engine.row += first.delta
        assertEquals(-1, first.delta)
        assertEquals(10f, first.offsetPx, 0.0001f)

        // And back: the same travel the other way.
        val second = scroll.drag(
            distancePx = -1.5f * rowHeight,
            rowHeight = rowHeight,
            engineRow = engine.row,
            maxRow = 100,
        )
        assertEquals(1, second.delta)
        assertEquals(0f, second.offsetPx, 0.0001f)
    }

    @Test
    fun `the oldest row stops the picture exactly`() {
        val scroll = ScrollMachine()
        val engine = Engine(2)
        // A long drag up into the history, past the oldest row.
        val target = scroll.drag(
            distancePx = 500f,
            rowHeight = rowHeight,
            engineRow = engine.row,
            maxRow = 100,
        )
        // The engine goes to the oldest row and the shift is zero: the first
        // line of the output is whole, with no strip of nothing above it.
        assertEquals(-2, target.delta)
        assertEquals(0f, target.offsetPx, 0.0001f)
        assertTrue("the edge stops the picture", target.pinned)
    }

    @Test
    fun `the live edge stops the picture exactly`() {
        val scroll = ScrollMachine()
        val engine = Engine(100)
        // The viewport is at the live edge and the finger keeps pulling up.
        val target = scroll.drag(
            distancePx = -500f,
            rowHeight = rowHeight,
            engineRow = engine.row,
            maxRow = 100,
        )
        // The newest line is flush at the bottom: no fraction of a row of
        // nothing below it.
        assertEquals(0, target.delta)
        assertEquals(0f, target.offsetPx, 0.0001f)
        assertTrue("the edge stops the picture", target.pinned)
    }

    @Test
    fun `the picture does not come back when it hits an edge`() {
        val scroll = ScrollMachine()
        val engine = Engine(1)
        // Drag down into the history until the oldest row stops it.
        val first = scroll.drag(
            distancePx = 40f,
            rowHeight = rowHeight,
            engineRow = engine.row,
            maxRow = 100,
        )
        engine.row += first.delta
        assertEquals(0f, first.offsetPx, 0.0001f)

        // And keep pulling: the picture stays where it stopped.
        val second = scroll.drag(
            distancePx = 40f,
            rowHeight = rowHeight,
            engineRow = engine.row,
            maxRow = 100,
        )
        assertEquals(0, second.delta)
        assertEquals(0f, second.offsetPx, 0.0001f)
    }

    @Test
    fun `settling puts the picture on a row boundary`() {
        val scroll = ScrollMachine()
        scroll.drag(
            distancePx = 7f,
            rowHeight = rowHeight,
            engineRow = 50,
            maxRow = 100,
        )
        val settled = scroll.settle(rowHeight = rowHeight, engineRow = 50, maxRow = 100)
        // The picture was a fraction of a row above the engine's row, and the
        // nearest row is the engine's own: no row has to move.
        assertEquals(0f, settled.offsetPx, 0.0001f)
        assertEquals(0, settled.delta)
    }

    @Test
    fun `settling moves the engine when the picture is nearer the row above`() {
        val scroll = ScrollMachine()
        // Three quarters of a row above the engine's row: the picture belongs
        // to the row above it, and the engine has to move there for the
        // picture to be whole.
        scroll.drag(
            distancePx = 15f,
            rowHeight = rowHeight,
            engineRow = 50,
            maxRow = 100,
        )
        val settled = scroll.settle(rowHeight = rowHeight, engineRow = 50, maxRow = 100)
        assertEquals(0f, settled.offsetPx, 0.0001f)
        assertEquals(-1, settled.delta)
    }

    @Test
    fun `settling at the ends leaves the picture whole`() {
        val scroll = ScrollMachine()
        // Into the history, past the oldest row.
        scroll.drag(
            distancePx = 500f,
            rowHeight = rowHeight,
            engineRow = 2,
            maxRow = 100,
        )
        val oldest = scroll.settle(rowHeight = rowHeight, engineRow = 0, maxRow = 100)
        assertEquals(0, oldest.delta)
        assertEquals(0f, oldest.offsetPx, 0.0001f)

        // And at the live edge.
        val other = ScrollMachine()
        other.drag(
            distancePx = -500f,
            rowHeight = rowHeight,
            engineRow = 100,
            maxRow = 100,
        )
        val live = other.settle(rowHeight = rowHeight, engineRow = 100, maxRow = 100)
        assertEquals(0, live.delta)
        assertEquals(0f, live.offsetPx, 0.0001f)
    }

    @Test
    fun `a new gesture starts from a row boundary`() {
        val scroll = ScrollMachine()
        scroll.drag(
            distancePx = 7f,
            rowHeight = rowHeight,
            engineRow = 50,
            maxRow = 100,
        )
        scroll.reset()
        assertEquals(0f, scroll.shift(), 0.0001f)
    }

    @Test
    fun `the engine's refusal puts the picture on the engine's row`() {
        val scroll = ScrollMachine()
        scroll.drag(
            distancePx = 7f,
            rowHeight = rowHeight,
            engineRow = 50,
            maxRow = 100,
        )
        // The engine did not go where it was asked to: the picture goes where
        // the engine is.
        scroll.snapToEngine()
        assertEquals(0f, scroll.shift(), 0.0001f)
    }

    @Test
    fun `a zero row height moves nothing`() {
        val scroll = ScrollMachine()
        val target = scroll.drag(
            distancePx = 20f,
            rowHeight = 0f,
            engineRow = 50,
            maxRow = 100,
        )
        assertEquals(0, target.delta)
    }
}
