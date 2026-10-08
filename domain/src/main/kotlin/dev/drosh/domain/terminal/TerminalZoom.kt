package dev.drosh.domain.terminal

/**
 * Terminal zoom limits and gesture tuning.
 *
 * In `:domain` because both ends of the zoom need them and neither may import
 * the other: [com.termux.view.TerminalView] lives in `:terminal`, and
 * `TerminalViewModel` lives in `:ui`, which is forbidden from importing
 * `:terminal` (AGENT.md). `:domain` is the one module both already depend on.
 */
object TerminalZoom {
    /** Smallest readable terminal font, in sp. */
    const val MIN_SP: Float = 9f

    /** Largest terminal font, in sp. Beyond this a line is a handful of pixels. */
    const val MAX_SP: Float = 48f

    /** Font size a double-tap returns to. */
    const val DEFAULT_SP: Float = 14f

    /**
     * Quantisation of a zoomed size.
     *
     * 0.1sp is below what the eye resolves at any realistic font size, so it
     * reads as continuous — but a fractional size that never quantises would
     * persist as a long float tail (14.300000000000001) and make every reload
     * start from a slightly different number.
     */
    const val STEP_SP: Float = 0.1f

    /**
     * Pinch travel, in log-scale, before a gesture counts as a zoom.
     *
     * Without it a two-finger landing wobbles the size by a percent or two
     * before the fingers have settled, so scrolling out to read something and
     * pinching to get closer both move the font a little. 4% is roughly the
     * width of one character at the default size — smaller than that, the
     * terminal looks unchanged anyway, so nothing is lost by ignoring it.
     */
    const val DEAD_ZONE: Float = 0.04f

    /**
     * Per-event clamp on the scale factor.
     *
     * [android.view.ScaleGestureDetector] reports the distance ratio between
     * events, and a finger that jumps — or a third finger landing — produces a
     * ratio that would zoom several steps in one frame. Clamping keeps one
     * event from being worth more than about a third of a step.
     */
    const val MAX_STEP_FACTOR: Float = 1.35f
}