package dev.drosh.terminal.engine.view

import android.content.Context
import android.view.GestureDetector
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

    /**
     * Sub-line scroll offset, in pixels.
     *
     * The engine's viewport is a whole number of rows, so a drag that moved
     * half a row had nowhere to go and was thrown away — which is what made
     * scrolling read as a series of jumps. The remainder is kept here and the
     * renderer shifts the grid by it, so the grid follows the finger.
     */
    private var scrollOffsetPx = 0f

    /** Momentum, from the same `Scroller` the vendored terminal used. */
    private var scroller: android.widget.Scroller? = null

    private var scrollAccumulator = 0f
    private var draggingSelection = false
    private var lastMouseColumn = -1
    private var lastMouseLine = -1

    /** The touch listener the surface attaches. */
    fun listener(): View.OnTouchListener {
        val detector = GestureDetector(
            view.context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onSingleTapUp(event: MotionEvent): Boolean {
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
                    first: MotionEvent?,
                    current: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    if (draggingSelection) {
                        view.selectionOverlay.extend(cellRow(current.y), cellColumn(current.x))
                        return true
                    }

                    // While the application tracks the mouse, a drag is a wheel:
                    // xterm's 64/65 buttons. Scrolling the local history instead
                    // would hide the program's own scrolling from it.
                    if (view.mouseMode()) {
                        wheelBy(distanceY, current.x, current.y)
                        return true
                    }

                    // Finger down (positive dy) scrolls back into the history.
                    scrollByPixels(distanceY)
                    return true
                }

                override fun onFling(
                    first: MotionEvent?,
                    current: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    if (draggingSelection || view.mouseMode()) {
                        return true
                    }
                    startFling(velocityY)
                    return true
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
     * Scroll by a finger distance, keeping the sub-line remainder.
     *
     * The vendored terminal's rule, moved over whole: a drag advances the
     * viewport by as many whole rows as it crossed and keeps the rest in
     * [scrollOffsetPx], which the renderer draws as a shift of the grid. Adding
     * the remainder to a whole-row scroll is what makes a drag follow the
     * finger instead of stepping.
     *
     * A program that tracks the mouse, or an alternate screen, needs discrete
     * rows — those cannot carry a sub-line offset — so the pixel path hands
     * them over to [scrollByRows] instead.
     */
    private fun scrollByPixels(distanceYPx: Float) {
        val spacing = view.metrics.height
        if (spacing <= 0f || distanceYPx == 0f) {
            return
        }

        val bits = view.modes()
        val alternate = bits and NativeCore.MODE_ALT_SCREEN != 0
        if (view.mouseMode() || alternate) {
            // Discrete events; no sub-line offset can be drawn and the program
            // would not understand one.
            scrollAccumulator += distanceYPx
            val rows = (scrollAccumulator / spacing).toInt()
            if (rows != 0) {
                scrollAccumulator -= rows * spacing
                scrollByRows(rows)
            }
            return
        }

        // GestureDetector reports distanceY as lastY - currentY, so dragging
        // the finger down is negative — and that is the gesture for revealing
        // older output, which moves the viewport offset in the other direction.
        var offset = scrollOffsetPx + distanceYPx
        var rows = 0
        // Whole rows in either direction, one at a time so the clamp at each
        // end is the engine's own answer rather than an estimate of it.
        while (offset >= spacing) {
            if (!scrollByRows(-1, allowAlternate = false)) {
                offset = 0f
                break
            }
            rows -= 1
            offset -= spacing
        }
        while (offset <= -spacing) {
            if (!scrollByRows(1, allowAlternate = false)) {
                offset = 0f
                break
            }
            rows += 1
            offset += spacing
        }

        scrollOffsetPx = offset
        view.applyScrollOffset(scrollOffsetPx)
        view.requestRender()
    }

    /**
     * Momentum after the finger lifts.
     *
     * The same `Scroller` and the same 0.25 scale the vendored terminal used:
     * the velocity the detector reports is measured for a fling over a list, and
     * a terminal that moved at that speed scrolled a screenful per gesture. The
     * fling then goes through [scrollByPixels] like a drag, so it carries the
     * sub-line remainder and the ends ease instead of slamming.
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

    /** Settle the sub-line offset back onto a whole row, smoothly. */
    private fun snapToWholeRow() {
        if (scrollOffsetPx == 0f) {
            return
        }
        view.applyScrollOffset(0f)
        scrollOffsetPx = 0f
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

        // The engine clamps at the history's ends; a row it refused is a row
        // the finger cannot push past, which is what stops the sub-line offset
        // from growing while the grid stays put.
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
    }
}
