package dev.drosh.terminal.engine.render

/**
 * The clock the terminal's cursor blinks on.
 *
 * One number, held in one place, because three things have to agree about it:
 * the loop that wakes up to redraw, the renderer that decides whether the
 * cursor is in its visible half, and the setting the user changed. When each
 * had its own copy, changing the setting moved one of them and the cursor
 * either stopped blinking or blinked at the old rate until the next restart.
 *
 * Zero means "do not blink": the cursor stays visible, which is what a blink
 * rate of zero asks for.
 */
object RenderClock {

    /** The blink period in milliseconds, or zero for a steady cursor. */
    @Volatile
    var blinkMillis: Long = DEFAULT_BLINK_MILLIS

    /** How long the loop waits when nothing else wakes it. */
    fun blinkWaitMillis(): Long = if (blinkMillis > 0) blinkMillis else STEADY_WAIT_MILLIS

    /** Whether a blinking cursor is in its visible half right now. */
    fun blinkVisible(): Boolean {
        val period = blinkMillis
        if (period <= 0) return true
        return (System.currentTimeMillis() / period) % 2L == 0L
    }

    /** The blink period a terminal starts with. */
    const val DEFAULT_BLINK_MILLIS = 500L

    /** How long the loop waits when the cursor does not blink at all. */
    const val STEADY_WAIT_MILLIS = 1000L
}