package dev.drosh.terminal.engine.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pinch's arithmetic, without a phone.
 *
 * Each case is a rule from [PinchMachine]'s documentation: the dead zone, the
 * latch, the bounded step, measuring from the gesture's origin, and the clamp.
 */
class PinchMachineTest {

    private fun machine() = PinchMachine(
        minSp = 9f,
        maxSp = 48f,
        deadZone = 0.04f,
        maxStepFactor = 1.35f,
    )

    @Test
    fun `a wobble inside the dead zone is not a zoom`() {
        val pinch = machine()
        pinch.begin(currentSp = 14f)

        // 2% — under the 4% dead zone.
        assertNull(pinch.next(1.02f))
        assertFalse(pinch.isZooming)
        assertNull("ending a gesture that never zoomed keeps the size", pinch.end())
    }

    @Test
    fun `crossing the dead zone starts a zoom and keeps it`() {
        val pinch = machine()
        pinch.begin(currentSp = 14f)

        val first = pinch.next(1.10f)
        assertTrue(first != null)
        assertTrue(pinch.isZooming)
        assertEquals(14f * 1.10f, first!!.targetSp, 0.001f)
    }

    @Test
    fun `returning to the start inside the latch does not return to the size`() {
        // The latch is the point: once a gesture is a zoom, coming back to the
        // starting spread must not leave the terminal at the peak.
        val pinch = machine()
        pinch.begin(currentSp = 14f)
        pinch.next(1.5f)
        val back = pinch.next(1f / 1.5f)
        // The factor is back to 1, so the size is back to the base — the latch
        // decides whether the gesture *counts*, not where it points.
        assertEquals(14f, back!!.targetSp, 0.001f)
        assertEquals(14f, pinch.end()!!, 0.001f)
    }

    @Test
    fun `one event cannot move more than the step clamp`() {
        val pinch = machine()
        pinch.begin(currentSp = 10f)
        // A jump worth five steps in one event.
        val frame = pinch.next(5f)!!
        // Clamped to 1.35.
        assertEquals(10f * 1.35f, frame.targetSp, 0.001f)
    }

    @Test
    fun `the size is clamped at both ends`() {
        val pinch = machine()
        pinch.begin(currentSp = 14f)
        var last = 0f
        repeat(20) { last = pinch.next(1.35f)?.targetSp ?: last }
        assertEquals(48f, last, 0.001f)

        val out = machine()
        out.begin(currentSp = 14f)
        repeat(20) { last = out.next(1f / 1.35f)?.targetSp ?: last }
        assertEquals(9f, last, 0.001f)
    }

    @Test
    fun `quantising lands on the size grid`() {
        val pinch = machine()
        assertEquals(14.3f, pinch.quantise(14.28f, 0.1f), 0.0001f)
        assertEquals(9f, pinch.quantise(1f, 0.1f), 0.0001f)
        assertEquals(48f, pinch.quantise(500f, 0.1f), 0.0001f)
    }
}