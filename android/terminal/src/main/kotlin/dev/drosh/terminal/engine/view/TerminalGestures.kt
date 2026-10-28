package dev.drosh.terminal.engine.view

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import dev.drosh.terminal.engine.core.NativeCore
import dev.drosh.terminal.engine.input.Encoder
import dev.drosh.terminal.engine.input.Mouse
import kotlin.math.abs

/**
 * Touch gestures, real-pointer (mouse/trackpad) reports and the scroll wheel,
 * mapped to terminal input exactly as Termux/alacritty do on mobile. Owns the
 * transient gesture state (drag anchor, scroll accumulator, last mouse cell); the
 * rest of the input vocabulary lives in [TerminalIme].
 */
internal class TerminalGestures(private val view: TerminalView) {

    /** Momentum, from the same `Scroller` the vendored terminal used. */
    private var scroller: android.widget.Scroller? = null

    private var scrollAccumulator = 0f
    private var draggingSelection = false

    /** The size the current pinch started at; reset per gesture. */
    private var zoomBaseSp = 0f

    /** Total finger travel this pinch, in log scale. */
    private var zoomScaleFactor = 1f

    /** Latched once the pinch leaves the dead zone; see `onScale`. */
    private var zoomPassedDeadZone = false
    private var lastMouseColumn = -1
    private var lastMouseLine = -1

    /** The touch listener the surface attaches. */
    fun listener(): View.OnTouchListener {
        val detector = GestureAndScaleRecognizer(
            view.context,
            object : GestureAndScaleRecognizer.Listener {
                override fun onSingleTapUp(event: MotionEvent): Boolean {
                    // The dead-zone latch is per gesture: a tap is a new one.
                    zoomScaleFactor = 1f
                    zoomBaseSp = 0f
                    zoomPassedDeadZone = false

                    // With mouse tracking on, a tap is a left click — a finger
                    // has no buttons, so this is the mobile mapping Termux uses.
                    if (view.mouseMode()) {
                        clickAt(event.x, event.y)
                        if (!view.selectionOverlay.isEmpty) {
                            view.selectionOverlay.clear()
                        }
                        return true
                    }

                    // A tap ends the selection instead of clearing the screen.
                    if (!view.selectionOverlay.isEmpty) {
                        view.selectionOverlay.clear()
                        return true
                    }
                    view.focusInput()
                    return true
                }

                override fun onLongPress(event: MotionEvent) {
                    // The mobile convention: a long press picks a word, and
                    // dragging from there extends the selection.
                    view.selectionOverlay.selectWord(cellRow(event.y), cellColumn(event.x))
                    draggingSelection = true
                }

                override fun onDoubleTap(event: MotionEvent): Boolean {
                    view.selectionOverlay.selectWord(cellRow(event.y), cellColumn(event.x))
                    return true
                }

                override fun onScroll(
                    second: MotionEvent,
                    dx: Float,
                    dy: Float,
                ): Boolean {
                    if (draggingSelection) {
                        view.selectionOverlay.extend(cellRow(second.y), cellColumn(second.x))
                        return true
                    }

                    // While the application tracks the mouse, a drag is a wheel:
                    // xterm's 64/65 buttons. Scrolling the local history instead
                    // would hide the program's own scrolling from it.
                    if (view.mouseMode()) {
                        wheelBy(dy, second.x, second.y)
                        return true
                    }

                    // Finger down (positive dy) scrolls back into the history.
                    scrollByPixels(dy)
                    return true
                }

                override fun onFling(
                    second: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    if (draggingSelection || view.mouseMode()) {
                        return true
                    }
                    startFling(velocityY)
                    return true
                }

                override fun onScaleBegin() {
                    // The gesture's own origin, so the size is recomputed from
                    // where the fingers started rather than from the last
                    // frame's rounded value.
                    zoomBaseSp = view.textSizeSp
                    zoomScaleFactor = 1f
                    zoomPassedDeadZone = false
                    // A long press that was already arming a word loses to the
                    // second finger: a pinch is not a selection, and the menu
                    // appearing over a zoom is the gesture being read as two.
                    draggingSelection = false
                    if (!view.selectionOverlay.isEmpty) {
                        view.selectionOverlay.clear()
                    }
                }

                override fun onScale(focusX: Float, focusY: Float, scale: Float): Boolean {
                    if (draggingSelection) {
                        return true
                    }
                    // One event, one bounded step: a finger that moves 40%
                    // between two events — or a third finger arriving — would
                    // otherwise take several steps in one frame and read as a
                    // jump.
                    val step = scale.coerceIn(1f / MAX_ZOOM_STEP, MAX_ZOOM_STEP)
                    zoomScaleFactor *= step

                    // A dead zone in log-scale, so it is symmetric: 4% either
                    // way reads as no movement. Latched once crossed, and the
                    // latch is the point: without it, returning the fingers to
                    // where they started lands back inside the zone and the
                    // terminal keeps the peak of the pinch — a zoom that the
                    // same gesture cannot undo.
                    if (!zoomPassedDeadZone) {
                        if (Math.abs(Math.log(zoomScaleFactor.toDouble())) < ZOOM_DEAD_ZONE) {
                            return true
                        }
                        zoomPassedDeadZone = true
                    }

                    // The terminal itself is resized, every frame the size
                    // actually changes — the vendored terminal's model, and the
                    // reason its zoom was continuous: the text grew, the grid
                    // reflowed, and the row under the fingers stayed put.
                    //
                    // `zoomTo` rounds to a tenth of a sp and returns at once
                    // when the size is one it already drew, so a frame that
                    // does not change the size costs a comparison.
                    val base = if (zoomBaseSp > 0f) zoomBaseSp else view.textSizeSp
                    val target = (base * zoomScaleFactor).coerceIn(MIN_ZOOM_SP, MAX_ZOOM_SP)
                    view.zoomTo(target, focusY)
                    // The chip reads the size the pinch has reached.
                    view.onZoomProgress(view.textSizeSp)
                    return true
                }

                override fun onScaleEnd() {
                    // The gesture is over: the dead zone is per-gesture, and
                    // the size is worth reporting once so the host can keep it.
                    zoomScaleFactor = 1f
                    zoomBaseSp = 0f
                    zoomPassedDeadZone = false
                    view.onZoomEnd(view.textSizeSp)
                }
            },
        )

        return View.OnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                draggingSelection = false
            }
            // A real pointer (USB/Bluetooth mouse, trackpad) is reported as it
            // is; touch falls through to the gesture mapping above.
            if (event.isFromSource(InputDevice.SOURCE_MOUSE) && handleMouseEvent(event)) {
                return@OnTouchListener true
            }
            detector.onTouchEvent(event)
            true
        }
    }

    /** Generic motion (wheel) events from a real pointer. */
    fun onGenericMotion(event: MotionEvent): Boolean = handleScrollWheel(event)

    /** A left click at a surface position: press and release, as xterm expects. */
    private fun clickAt(x: Float, y: Float) {
        val bits = view.modes()
        val column = cellColumn(x)
        val line = cellRow(y)
        Mouse.report(Mouse.BUTTON_LEFT, true, column, line, bits)?.let(view.session::write)
        Mouse.report(Mouse.BUTTON_LEFT, false, column, line, bits)?.let(view.session::write)
    }

    /**
     * A finger drag becomes wheel reports while the application tracks the
     * mouse — the mobile mapping Termux uses (alacritty has no touch screen to
     * map, and its wheel path reports 64/65 to the program in mouse mode).
     */
    private fun wheelBy(distanceY: Float, x: Float, y: Float) {
        scrollAccumulator += distanceY
        val rows = (scrollAccumulator / view.metrics.height).toInt()
        if (rows == 0) {
            return
        }
        scrollAccumulator -= rows * view.metrics.height

        // Positive is the same direction as the local scroll: back into the
        // history, i.e. wheel up.
        val code = if (rows > 0) Mouse.WHEEL_UP else Mouse.WHEEL_DOWN
        val bits = view.modes()
        val column = cellColumn(x)
        val line = cellRow(y)
        repeat(abs(rows)) {
            Mouse.report(code, true, column, line, bits)?.let(view.session::write)
        }
    }

    /**
     * A local scrollback step, or the arrow keys `?1007` asks for in the
     * alternate screen (alacritty's `scroll_terminal`). The sequences are SS3
     * regardless of DECCKM, matching alacritty's bytes.
     */
    /**
     * Scroll by a finger distance.
     *
     * alacritty's model, which is the only one there is: pixels accumulate in
     * this layer (`scroll_terminal` in `alacritty/src/input/mod.rs`) and only
     * whole lines ever reach the terminal. The leftover fraction stays here
     * and carries into the next event, so a slow drag still advances a row
     * every few frames instead of never.
     *
     * There is deliberately no sub-line offset. Carrying one — kitty's
     * `pixel_scroll_offset_y`, which was tried — meant the grid and the offset
     * were two descriptions of one position, and a frame could read the cells
     * of one and the offset of the other. That is a one-frame shift at every
     * row boundary, which is what a drag looked like when it shook; at the
     * ends of the history the offset kept moving while the grid did not, which
     * is what the bounce was.
     */
    private fun scrollByPixels(distanceYPx: Float) {
        val spacing = view.metrics.height
        if (spacing <= 0f || distanceYPx == 0f) {
            return
        }

        val bits = view.modes()
        val alternate = bits and NativeCore.MODE_ALT_SCREEN != 0

        // GestureDetector reports distanceY as lastY - currentY: dragging the
        // finger *down* (back into the history) is negative.
        scrollAccumulator += distanceYPx

        val rows = (scrollAccumulator / spacing).toInt()
        if (rows == 0) {
            return
        }
        scrollAccumulator -= rows * spacing

        if (view.mouseMode() || alternate) {
            // Discrete events; the program owns the scrolling.
            scrollByRows(rows)
            return
        }

        // Negative rows is the direction the engine calls "into the history",
        // because the accumulator counts finger travel downward.
        view.session.scroll(-rows)
        view.requestRender()
    }

    /**
     * Momentum after the finger lifts.
     *
     * The same `Scroller` and the same 0.25 scale the vendored terminal used:
     * the velocity the detector reports is measured for a fling over a list, and
     * a terminal that moved at that speed scrolled a screenful per gesture. The
     * fling then goes through [scrollByPixels] like a drag, so a fling and a pan
     * cannot disagree about where the viewport is.
     */
    private fun startFling(velocityY: Float) {
        val scroller = scroller ?: android.widget.Scroller(view.context).also { scroller = it }
        if (!scroller.isFinished) {
            // The previous fling owns the viewport until it lands.
            return
        }
        scroller.fling(0, 0, 0, -(velocityY * FLING_SCALE).toInt(), 0, 0, Int.MIN_VALUE, Int.MAX_VALUE)

        val step = object : Runnable {
            private var lastY = 0

            override fun run() {
                if (scroller.isFinished) {
                    snapToWholeRow()
                    return
                }
                scroller.computeScrollOffset()
                val y = scroller.currY
                if (y != lastY) {
                    scrollByPixels((y - lastY).toFloat())
                    lastY = y
                }
                view.post(this)
            }
        }
        view.post(step)
    }

    /**
     * Settle the fling.
     *
     * The viewport only ever moves in whole lines, so there is nothing to snap
     * to — only the pixel accumulator to clear, so the next drag starts from
     * zero rather than from the tail of the fling.
     */
    private fun snapToWholeRow() {
        scrollAccumulator = 0f
        view.requestRender()
    }

    private fun scrollByRows(rows: Int, allowAlternate: Boolean = true): Boolean {
        if (rows == 0) {
            return false
        }

        val bits = view.modes()
        val alternate = allowAlternate &&
            bits and NativeCore.MODE_ALT_SCREEN != 0 &&
            bits and NativeCore.MODE_ALTERNATE_SCROLL != 0
        if (alternate) {
            val sequence = if (rows > 0) "\u001bOA" else "\u001bOB"
            repeat(abs(rows)) { view.session.writeUtf8(sequence) }
            return true
        }

        // The engine clamps at the history's ends, so a row past the end is a
        // row the finger cannot push — the viewport simply stops.
        if (view.mouseMode() && !alternate) {
            // Mouse reporting owns the wheel; the local history is not ours to
            // move.
            return false
        }
        view.session.scroll(rows)
        view.requestRender()
        return true
    }

    /**
     * Buttons and motion from a real pointer, when the application asked for
     * mouse reports (alacritty's `mouse_report` path).
     */
    private fun handleMouseEvent(event: MotionEvent): Boolean {
        val bits = view.modes()
        if (!Mouse.active(bits)) {
            return false
        }

        val column = cellColumn(event.x)
        val line = cellRow(event.y)
        // A MotionEvent carries KeyEvent's meta-state bits, not the key-event
        // accessors (`isCtrlPressed` belongs to KeyEvent).
        val meta = event.metaState
        val mods = Encoder.Mods(
            ctrl = meta and KeyEvent.META_CTRL_ON != 0,
            alt = meta and KeyEvent.META_ALT_ON != 0,
            shift = meta and KeyEvent.META_SHIFT_ON != 0,
        )

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                lastMouseColumn = column
                lastMouseLine = line
                Mouse.report(mouseButton(event.actionButton), true, column, line, bits, mods)
                    ?.let(view.session::write)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> {
                lastMouseColumn = -1
                lastMouseLine = -1
                Mouse.report(mouseButton(event.actionButton), false, column, line, bits, mods)
                    ?.let(view.session::write)
            }
            MotionEvent.ACTION_MOVE -> {
                // One report per cell, not per pixel (alacritty does the same).
                if (column == lastMouseColumn && line == lastMouseLine) {
                    return true
                }
                lastMouseColumn = column
                lastMouseLine = line
                motionCode(bits, event.buttonState)?.let { code ->
                    Mouse.report(code, true, column, line, bits, mods)?.let(view.session::write)
                }
            }
            else -> return false
        }
        return true
    }

    /** xterm's button numbers: left 0, middle 1, right 2. */
    private fun mouseButton(button: Int): Int = when (button) {
        MotionEvent.BUTTON_SECONDARY -> Mouse.BUTTON_RIGHT
        MotionEvent.BUTTON_TERTIARY -> Mouse.BUTTON_MIDDLE
        else -> Mouse.BUTTON_LEFT
    }

    /** Which motion code applies, or `null` when this mode reports none. */
    private fun motionCode(bits: Int, buttons: Int): Int? = when {
        buttons and MotionEvent.BUTTON_PRIMARY != 0 -> Mouse.MOTION_LEFT
        buttons and MotionEvent.BUTTON_TERTIARY != 0 -> Mouse.MOTION_MIDDLE
        buttons and MotionEvent.BUTTON_SECONDARY != 0 -> Mouse.MOTION_RIGHT
        // 1003 reports bare motion; 1002 only reports motion while a button is down.
        bits and NativeCore.MODE_MOUSE_MOTION != 0 -> Mouse.MOTION_NONE
        else -> null
    }

    /**
     * A wheel notch from a real pointer: a wheel report when the application
     * tracks the mouse, a local scrollback step otherwise — the same split
     * alacritty's `scroll_terminal` makes.
     */
    private fun handleScrollWheel(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_SCROLL ||
            !event.isFromSource(InputDevice.SOURCE_MOUSE)
        ) {
            return false
        }

        val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (vertical != 0f) {
            if (view.mouseMode()) {
                val code = if (vertical > 0f) Mouse.WHEEL_UP else Mouse.WHEEL_DOWN
                Mouse.report(code, true, cellColumn(event.x), cellRow(event.y), view.modes())
                    ?.let(view.session::write)
            } else {
                // Shift bypasses `?1007`, as it does in alacritty.
                val shift = event.metaState and KeyEvent.META_SHIFT_ON != 0
                scrollByRows(if (vertical > 0f) 1 else -1, allowAlternate = !shift)
            }
        }

        val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        if (horizontal != 0f && view.mouseMode()) {
            val code = if (horizontal > 0f) Mouse.WHEEL_LEFT else Mouse.WHEEL_RIGHT
            Mouse.report(code, true, cellColumn(event.x), cellRow(event.y), view.modes())
                ?.let(view.session::write)
        }

        return true
    }

    /** The grid row under a vertical surface coordinate. */
    private fun cellRow(y: Float): Int {
        val rows = view.session.rows
        if (rows <= 0) {
            return 0
        }
        return (y / view.metrics.height).toInt().coerceIn(0, rows - 1)
    }

    /** The grid column under a horizontal surface coordinate. */
    private fun cellColumn(x: Float): Int {
        val columns = view.session.columns
        if (columns <= 0) {
            return 0
        }
        return (x / view.metrics.width).toInt().coerceIn(0, columns - 1)
    }

    private companion object {
        /**
         * How much of the reported fling velocity reaches the viewport.
         *
         * The vendored terminal's value, kept because it is what the gesture
         * felt like: the detector's velocity is measured for a list that
         * follows the finger 1:1, and a terminal that did the same shot a
         * whole screen past the point the user aimed at.
         */
        const val FLING_SCALE = 0.25f

        /** The largest size change a single scale event may apply. */
        const val MAX_ZOOM_STEP = 1.5f

        /** The same limits the app's zoom uses; the chip and the view must agree. */
        const val MIN_ZOOM_SP = dev.drosh.domain.terminal.TerminalZoom.MIN_SP
        const val MAX_ZOOM_SP = dev.drosh.domain.terminal.TerminalZoom.MAX_SP

        /**
         * How far a pinch must move, in log scale, before it is a zoom.
         *
         * Symmetric by construction: `|ln(factor)| < 0.04` is the same 4%
         * either way, which a linear threshold cannot be.
         */
        const val ZOOM_DEAD_ZONE = 0.04
    }
}
