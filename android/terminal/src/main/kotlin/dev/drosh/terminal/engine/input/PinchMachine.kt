package dev.drosh.terminal.engine.input

/**
 * The scalar half of a pinch, with no Android in it.
 *
 * ## Where this comes from
 *
 * The vendored Termux view had this exact arithmetic spread through its touch
 * handler, mixed with `mScaleFactor` fields and a renderer reference. It was
 * right — a dead zone in log scale, a bounded step per event, the size measured
 * from where the gesture began — and it was impossible to test. Pulling the
 * numbers out is the whole change: the rules are the same, the mistakes are now
 * caught by a JUnit run instead of a phone.
 *
 * ## The three rules, and why each one exists
 *
 * 1. **Measure from the gesture's origin.** `scale` is the ratio between the
 *    distance the fingers started at and the distance they are at now, so the
 *    target is `base * scale` every frame. Accumulating per-event factors
 *    multiplies one rounding error by the number of events, and a gesture that
 *    returns to its starting spread must return to its starting size.
 * 2. **Bound each step.** A finger that jumps, or a third finger landing,
 *    reports a ratio that would move several steps in one frame. Clamping the
 *    per-event step keeps one event from being worth more than a third of a
 *    step of text.
 * 3. **Dead zone in log scale, latched once.** `ln(scale)` is symmetric, so 4%
 *    either way reads as no movement; and the latch is the point — without it,
 *    returning the fingers to where they started lands back inside the zone and
 *    the terminal keeps the peak of the pinch, a zoom the same gesture cannot
 *    undo.
 */
class PinchMachine(
    private val minSp: Float,
    private val maxSp: Float,
    private val deadZone: Float,
    private val maxStepFactor: Float,
) {

    /** What one frame of the gesture decided. */
    data class Frame(
        /** The size the fingers have reached, in sp, already clamped. */
        val targetSp: Float,
        /**
         * The ratio to draw at while the gesture runs: `targetSp / baseSp`.
         *
         * This is what the glyph shader scales by, so the picture follows the
         * fingers without the grid being reflowed on every event.
         */
        val renderScale: Float,
        /** True once the gesture has crossed the dead zone and counts as a zoom. */
        val zooming: Boolean,
    )

    private var baseSp: Float = 0f
    private var factor: Float = 1f
    private var passedDeadZone: Boolean = false

    /** The size the gesture started from, or 0 when none is in progress. */
    val base: Float get() = baseSp

    /** True once the current gesture is a real zoom rather than a wobble. */
    val isZooming: Boolean get() = passedDeadZone

    /** Fingers down. `currentSp` is the size the terminal is at right now. */
    fun begin(currentSp: Float) {
        baseSp = if (currentSp > 0f) currentSp else minSp
        factor = 1f
        passedDeadZone = false
    }

    /**
     * One frame of the pinch.
     *
     * Returns null while the gesture is still inside the dead zone: nothing has
     * changed and the caller should not draw a scale that is not there.
     */
    fun next(scale: Float): Frame? {
        if (baseSp <= 0f) return null
        if (!scale.isFinite() || scale <= 0f) return null

        val step = scale.coerceIn(1f / maxStepFactor, maxStepFactor)
        factor *= step

        if (!passedDeadZone) {
            if (Math.abs(Math.log(factor.toDouble())) < deadZone) return null
            passedDeadZone = true
        }

        val target = (baseSp * factor).coerceIn(minSp, maxSp)
        return Frame(
            targetSp = target,
            renderScale = target / baseSp,
            zooming = true,
        )
    }

    /**
     * Fingers up.
     *
     * Returns the size to keep, or null when the gesture never became a zoom —
     * in which case the terminal stays exactly where it was and the caller does
     * nothing at all.
     */
    fun end(): Float? {
        val settled = if (passedDeadZone) {
            (baseSp * factor).coerceIn(minSp, maxSp)
        } else {
            null
        }
        baseSp = 0f
        factor = 1f
        passedDeadZone = false
        return settled
    }

    /** Round to the size grid the settings use, inside the limits. */
    fun quantise(value: Float, stepSp: Float): Float {
        if (stepSp <= 0f) return value.coerceIn(minSp, maxSp)
        val steps = Math.round(value.coerceIn(minSp, maxSp) / stepSp)
        return (steps * stepSp).coerceIn(minSp, maxSp)
    }
}