// Ported from: the vendored terminal's `GestureAndScaleRecognizer` and
// `TerminalView.zoomTo` (Iris/Drosh before the Rust migration).
// Adapted for Drosh — dev.drosh
// Changes: package moved, the Termux name dropped, and the pinch is reported
// with a "handled" flag so a two-finger gesture can never be read as a tap.
package dev.drosh.terminal.engine.view

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector

/**
 * A `GestureDetector` and a `ScaleGestureDetector` over one touch stream.
 *
 * The two have to see every event: a pinch that the tap detector also reads is
 * a tap, and a tap that opens the keyboard while two fingers are zooming is how
 * a pinch ends up typing into the terminal. So the scale detector runs first,
 * and once it is in progress the gesture detector is not told anything at all.
 */
internal class GestureAndScaleRecognizer(context: Context, private val listener: Listener) {

    interface Listener {
        fun onSingleTapUp(event: MotionEvent): Boolean
        fun onDoubleTap(event: MotionEvent): Boolean
        fun onScroll(second: MotionEvent, dx: Float, dy: Float): Boolean
        fun onFling(second: MotionEvent, velocityX: Float, velocityY: Float): Boolean

        /**
         * One frame of a pinch.
         *
         * `focusX`/`focusY` are where the fingers' midpoint is: the zoom keeps
         * the line under it in place, so the content does not slide away while
         * the size changes.
         */
        fun onScale(focusX: Float, focusY: Float, scale: Float): Boolean

        /**
         * Fingers down, a pinch is starting.
         *
         * The size is recomputed from the gesture's own origin rather than
         * accumulated frame by frame: accumulating turns one rounding error per
         * event into a visible drift, and shrinking back to where the fingers
         * started would not return to the size they found.
         */
        fun onScaleBegin()

        /** Fingers lifted; the size is final and worth keeping. */
        fun onScaleEnd()

        fun onLongPress(event: MotionEvent)
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onScroll(
                first: MotionEvent?,
                current: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean = listener.onScroll(current, distanceX, distanceY)

            override fun onFling(
                first: MotionEvent?,
                current: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean = listener.onFling(current, velocityX, velocityY)

            override fun onLongPress(event: MotionEvent) = listener.onLongPress(event)
        },
        null,
        // A pinch must not reach this detector as a scroll: with multitouch
        // ignored here, the only thing that sees two fingers is the scale one.
        true,
    )

    init {
        gestureDetector.setOnDoubleTapListener(object : GestureDetector.OnDoubleTapListener {
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean =
                listener.onSingleTapUp(event)

            override fun onDoubleTap(event: MotionEvent): Boolean = listener.onDoubleTap(event)

            override fun onDoubleTapEvent(event: MotionEvent): Boolean = true
        })
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                listener.onScaleBegin()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean =
                listener.onScale(detector.focusX, detector.focusY, detector.scaleFactor)

            override fun onScaleEnd(detector: ScaleGestureDetector) = listener.onScaleEnd()
        },
    ).apply {
        isQuickScaleEnabled = false
    }

    /** True from the moment a second finger lands until the last one lifts. */
    private var pinching = false

    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> pinching = true
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked != MotionEvent.ACTION_POINTER_UP) {
                    pinching = false
                }
            }
        }

        scaleDetector.onTouchEvent(event)

        // While a pinch is in progress the tap detector is not told anything.
        // Two fingers on a terminal must never be a tap — a tap focuses the
        // keyboard, and a keyboard over a pinch the user is still doing is the
        // gesture being read as something else entirely.
        if (pinching || scaleDetector.isInProgress) {
            return
        }
        gestureDetector.onTouchEvent(event)
    }
}
