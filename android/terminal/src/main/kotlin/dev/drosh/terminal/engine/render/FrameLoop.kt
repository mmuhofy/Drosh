package dev.drosh.terminal.engine.render

import android.view.Choreographer

/**
 * The frame pump: a vsync-aligned callback instead of a `Handler` poll.
 *
 * GLSurfaceView stays in `RENDERMODE_WHEN_DIRTY`, so a frame is submitted only
 * when something actually changed. Each vsync this asks the engine whether it
 * has anything new ([poll] → the wakeup flag, one cheap JNI call), and it owns
 * the cursor blink so the two timers cannot drift apart.
 *
 * A frame callback always runs at vsync, which is the cost of the design: one
 * boolean call per frame (≈60/s) in exchange for engine wakeups being drawn on
 * the next screen refresh rather than up to 32 ms later.
 */
internal class FrameLoop(
    /** The engine has something new: pump and submit a frame. */
    private val onWakeup: () -> Unit,
    /** The cursor blinked: redraw the rows that hold it. */
    private val onBlink: () -> Unit,
    private val blinkMillis: Long = BLINK_MILLIS,
) : Choreographer.FrameCallback {

    /** Asked once per vsync; `true` when the engine has new output. */
    var poll: (() -> Boolean)? = null

    private var running = false
    private var lastBlinkNanos = 0L

    fun start() {
        if (running) {
            return
        }
        running = true
        lastBlinkNanos = System.nanoTime()
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        if (!running) {
            return
        }
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) {
            return
        }

        if (poll?.invoke() == true) {
            onWakeup()
        }

        if (frameTimeNanos - lastBlinkNanos >= blinkMillis * NANOS_PER_MILLI) {
            lastBlinkNanos = frameTimeNanos
            onBlink()
        }

        Choreographer.getInstance().postFrameCallback(this)
    }

    private companion object {
        const val BLINK_MILLIS = 500L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
