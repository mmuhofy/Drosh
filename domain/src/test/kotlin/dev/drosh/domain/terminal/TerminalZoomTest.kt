package dev.drosh.domain.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The zoom gesture's arithmetic, which is the part that decides whether a pinch
 * feels attached to the fingers or merely happens near them.
 *
 * Pure maths over the constants in [TerminalZoom] — the behaviour around it
 * (reflow, anchoring, persistence) is Android-side and covered by the device.
 */
class TerminalZoomTest {

    /** The quantisation a view applies to a zoomed size. */
    private fun quantise(value: Float): Float {
        val clamped = value.coerceIn(TerminalZoom.MIN_SP, TerminalZoom.MAX_SP)
        val steps = Math.round(clamped / TerminalZoom.STEP_SP)
        return (steps * TerminalZoom.STEP_SP).coerceIn(TerminalZoom.MIN_SP, TerminalZoom.MAX_SP)
    }

    /** Whether a gesture's total travel is past the dead zone. */
    private fun pastDeadZone(accumulated: Float): Boolean =
        Math.abs(Math.log(accumulated.toDouble())) >= TerminalZoom.DEAD_ZONE

    /** Per-event clamp the recognizer applies before accumulating. */
    private fun clampStep(scale: Float): Float =
        scale.coerceIn(1f / TerminalZoom.MAX_STEP_FACTOR, TerminalZoom.MAX_STEP_FACTOR)

    @Test
    fun `quantise lands on a tenth of an sp`() {
        assertEquals(14.3f, quantise(14.26f), TOLERANCE)
        assertEquals(14.3f, quantise(14.34f), TOLERANCE)
        assertEquals(14.0f, quantise(14.0f), TOLERANCE)
    }

    /**
     * The reason the size is quantised at all: a float multiplied by a gesture
     * ratio carries a long tail, and the value that reaches storage should be
     * the one the chip showed.
     */
    @Test
    fun `quantise does not accumulate a float tail`() {
        val once = quantise(14.3f)
        val twice = quantise(once / 14.3f * 14.3f)
        assertEquals(once, twice, 0f)
    }

    @Test
    fun `quantise clamps to the limits`() {
        assertEquals(TerminalZoom.MIN_SP, quantise(1f), 0f)
        assertEquals(TerminalZoom.MAX_SP, quantise(500f), 0f)
    }

    @Test
    fun `dead zone swallows a two finger landing`() {
        // Two fingers rarely land at exactly the same moment; a few percent of
        // travel happens before they settle.
        assertEquals(false, pastDeadZone(1.01f))
        assertEquals(false, pastDeadZone(0.99f))
        assertEquals(true, pastDeadZone(1.06f))
        assertEquals(true, pastDeadZone(0.94f))
    }

    /**
     * Symmetry: shrinking by 6% has to clear the same threshold as growing by
     * 6%, or the terminal would zoom out from a gesture that would not have
     * zoomed in.
     */
    @Test
    fun `dead zone is the same in both directions`() {
        assertEquals(pastDeadZone(1.05f), pastDeadZone(0.95f))
    }

    /**
     * Crossing the dead zone once does not limit how far the same gesture may
     * then travel, in either direction.
     *
     * The regression this exists for: gating every frame on the zone left the
     * terminal at the peak of a pinch once the fingers came back, so the zoom
     * could not be undone by the gesture that made it.
     */
    @Test
    fun `once past the dead zone the zone stops gating`() {
        val base = 14f
        var accumulated = 1f
        var latched = false
        fun apply(scale: Float): Float {
            accumulated *= clampStep(scale)
            if (!latched) {
                if (!pastDeadZone(accumulated)) return Float.NaN
                latched = true
            }
            return quantise(base * accumulated)
        }
        // Out past the zone...
        assertTrue(apply(1.08f) > base)
        // ...all the way to the stop...
        assertTrue(apply(1.6f) > 0f)
        // ...and back to the origin: the size follows the fingers home.
        apply(1 / 1.08f)
        val backHome = apply(1 / 1.6f)
        assertEquals(base, backHome, 0.05f)
    }

    @Test
    fun `one event cannot be worth more than a bounded step`() {
        // A third finger landing reads as a large ratio; without the clamp it
        // would be several steps in one frame.
        assertEquals(TerminalZoom.MAX_STEP_FACTOR, clampStep(4f), 0f)
        assertEquals(1f / TerminalZoom.MAX_STEP_FACTOR, clampStep(0.1f), TOLERANCE)
        assertEquals(1.02f, clampStep(1.02f), TOLERANCE)
    }

    /**
     * A pinch that ends where it started ends where it started.
     *
     * The size is recomputed from the gesture's origin every frame, so a pinch
     * out and back is not allowed to leave a fraction behind — the drift a
     * step-by-step accumulation produces. The frames are chosen to multiply
     * back to 1, which is what "returned to where it started" means here.
     */
    @Test
    fun `a pinch that returns to its origin returns to its size`() {
        val base = 14.2f
        val frames = listOf(1.06f, 1.12f, 1 / 1.06f, 1 / 1.12f)
        var accumulated = 1f
        var last = base
        var latched = false
        var peak = base
        for (scale in frames) {
            accumulated *= clampStep(scale)
            if (!latched) {
                if (!pastDeadZone(accumulated)) continue
                latched = true
            }
            last = quantise(base * accumulated)
            if (last > peak) peak = last
        }
        // The size during the gesture tracked the fingers...
        assertEquals(quantise(base * 1.06f * 1.12f), peak, TOLERANCE)
        // ...and the size after it is the size it started at, not the peak.
        assertEquals(base, last, TOLERANCE)
    }

    /**
     * Zooming in and out by the same gesture ends at the original size, not
     * near it.
     *
     * 20 frames out and 20 back is roughly a two-second pinch each way, which
     * is where an accumulating implementation would visibly drift.
     */
    @Test
    fun `symmetric gesture is size neutral`() {
        val base = 14f
        var accumulated = 1f
        repeat(20) { accumulated *= clampStep(1.02f) }
        repeat(20) { accumulated *= clampStep(1 / 1.02f) }
        assertEquals(base, quantise(base * accumulated), 0.05f)
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}