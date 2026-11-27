package dev.drosh.feature.terminal

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Follows the hyperlink, if any, under a tap on the terminal.
 *
 * ## Why a listener that returns false
 *
 * The surface handles its own touches — scroll, fling, pinch, focus — and this
 * must not take any of that away. An `OnTouchListener` runs before the view's
 * own `onTouchEvent`, and returning false hands the event straight on, so the
 * surface behaves exactly as it did. What is left here is the one thing the
 * surface does not do: spotting a tap and asking the engine about the cell
 * under it.
 *
 * ## Why the cell is read at tap time
 *
 * The cell size and the resolver are functions, not values: a pinch or a font
 * change moves the cell, and a listener built once would otherwise resolve the
 * tap against the cell it was built with. They are read when the tap lands.
 */
internal class HyperlinkTap(
    /** How far a touch may wander and still be a tap. */
    private val slop: Int,
    /** How long a touch may last and still be a tap. */
    private val timeoutMillis: Long,
    /** The pane's cell, in pixels, read at tap time. */
    private val cellSize: () -> Pair<Float, Float>,
    /** Whether touch is being reported to the program as mouse events. */
    private val mouseActive: () -> Boolean,
    /** The engine's link for a viewport cell, or null. */
    private val resolve: (row: Int, column: Int) -> String?,
    /** Told the URI that was found. */
    private val onLink: (String) -> Unit,
) : View.OnTouchListener {

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    /** True while the touch could still become a tap. */
    private var candidate = false

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downAt = SystemClock.uptimeMillis()
                // One finger only: a two-finger touch is a pinch's beginning,
                // and a pinch is not a tap on a link.
                candidate = event.pointerCount == 1
            }

            MotionEvent.ACTION_POINTER_DOWN -> candidate = false

            MotionEvent.ACTION_MOVE -> {
                if (candidate &&
                    (abs(event.x - downX) > slop || abs(event.y - downY) > slop)
                ) {
                    candidate = false
                }
            }

            MotionEvent.ACTION_UP -> {
                val wasTap = candidate && SystemClock.uptimeMillis() - downAt <= timeoutMillis
                candidate = false
                // Under mouse reporting the tap is the program's click: the
                // surface has already sent it, and opening a link on top of
                // the click the user did not ask for would be two actions for
                // one touch.
                if (wasTap && !mouseActive()) followLink(event.x, event.y)
            }

            MotionEvent.ACTION_CANCEL -> candidate = false
        }
        // Never consume: the surface's own gestures must go on receiving every
        // event exactly as they did before.
        return false
    }

    /** Resolve the tapped cell and report its link, if it has one. */
    private fun followLink(x: Float, y: Float) {
        val (width, height) = cellSize()
        if (width <= 0f || height <= 0f) return
        // Viewport coordinates: the grid is drawn from the surface's origin, so
        // the cell under the finger is the finger divided by the cell. The
        // sub-line scroll remainder is not applied — a tap after a scroll can
        // be one row out until the picture has settled on a whole row.
        val column = (x / width).toInt()
        val row = (y / height).toInt()
        if (column < 0 || row < 0) return
        val uri = resolve(row, column) ?: return
        if (uri.isNotEmpty()) onLink(uri)
    }
}

/**
 * How long a touch may last and still count as a tap.
 *
 * A terminal's own gestures are drags and pinches; a tap is the short, still
 * one between them. The window is short because a long press that happened to
 * move a pixel should scroll, not follow a link.
 */
internal const val TAP_TIMEOUT_MILLIS = 250L
