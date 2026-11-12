package dev.drosh.terminal.engine.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scroll's arithmetic, without a phone.
 *
 * The model is two numbers: the engine moves by whole rows and the picture
 * shifts by the fraction between them. The cases here are the ones that used
 * to go wrong — the picture jumping to a row boundary, the shift pointing the
 * wrong way, and the engine being asked for a row it would have to refuse.
 */
class ScrollMachineTest {

    private val spacing = 20f

    @Test
    fun `a small drag keeps the shift and moves no row`() {
        val scroll = ScrollMachine()
        val target = scroll.drag(distancePx = 5f, spacingPx = spacing)
        // The viewport stays where it was: a fifth of a row is not a row.
        assertEquals(0, target.delta)
        // The picture moved down by the finger's travel, which is what opens
        // the row above — the older one.
        assertEquals(5f, target.offsetPx, 0.0001f)
    }

    @Test
    fun `a whole row of travel moves the engine and leaves no shift`() {
        val scroll = ScrollMachine()
        val target = scroll.drag(distancePx = spacing, spacingPx = spacing)
        // One row into the history: the engine's row count grows downwards, so
        // the history is the negative direction.
        assertEquals(-1, target.delta)
        assertEquals(0f, target.offsetPx, 0.0001f)
    }

    @Test
    fun `the shift accumulates across events`() {
        val scroll = ScrollMachine()
        val first = scroll.drag(distancePx = 5f, spacingPx = spacing)
        assertEquals(5f, first.offsetPx, 0.0001f)

        val second = scroll.drag(distancePx = 5f, spacingPx = spacing)
        assertEquals(10f, second.offsetPx, 0.0001f)
        assertEquals(0, second.delta)
    }

    @Test
    fun `the shift never leaves a row`() {
        val scroll = ScrollMachine()
        var offset = 0f
        repeat(50) {
            offset = scroll.drag(distancePx = 7f, spacingPx = spacing).offsetPx
            assertTrue("the shift stays inside a row", offset >= 0f && offset < spacing)
        }
    }

    @Test
    fun `dragging the other way moves toward the live edge`() {
        val scroll = ScrollMachine()
        // Down into the history by a row and a half.
        scroll.drag(distancePx = 1.5f * spacing, spacingPx = spacing)
        // And back: the same travel the other way.
        val target = scroll.drag(distancePx = -1.5f * spacing, spacingPx = spacing)
        assertEquals(1, target.delta)
        assertEquals(0f, target.offsetPx, 0.0001f)
    }

    @Test
    fun `a refused move drops the shift`() {
        val scroll = ScrollMachine()
        scroll.drag(distancePx = 5f, spacingPx = spacing)
        scroll.pinned()
        assertEquals(0f, scroll.shift(), 0.0001f)
    }

    @Test
    fun `the oldest row drops the shift`() {
        val scroll = ScrollMachine()
        scroll.drag(distancePx = 5f, spacingPx = spacing)
        scroll.atOldestRow()
        assertEquals(0f, scroll.shift(), 0.0001f)
    }

    @Test
    fun `a new gesture starts from a row boundary`() {
        val scroll = ScrollMachine()
        scroll.drag(distancePx = 7f, spacingPx = spacing)
        scroll.reset()
        assertEquals(0f, scroll.shift(), 0.0001f)
    }

    @Test
    fun `a zero spacing moves nothing`() {
        val scroll = ScrollMachine()
        val target = scroll.drag(distancePx = 20f, spacingPx = 0f)
        assertEquals(0, target.delta)
    }
}
