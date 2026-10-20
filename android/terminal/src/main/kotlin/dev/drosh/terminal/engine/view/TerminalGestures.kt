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
                    scrollAccumulator += distanceY
                    val rowsMoved = (scrollAccumulator / view.metrics.height).toInt()
                    if (rowsMoved != 0) {
                        scrollAccumulator -= rowsMoved * view.metrics.height
                        scrollByRows(rowsMoved)
                    }
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
    private fun scrollByRows(rows: Int, allowAlternate: Boolean = true) {
        if (rows == 0) {
            return
        }

        val bits = view.modes()
        val alternate = allowAlternate &&
            bits and NativeCore.MODE_ALT_SCREEN != 0 &&
            bits and NativeCore.MODE_ALTERNATE_SCROLL != 0
        if (alternate) {
            val sequence = if (rows > 0) "\u001bOA" else "\u001bOB"
            repeat(abs(rows)) { view.session.writeUtf8(sequence) }
            return
        }

        view.session.scroll(rows)
        view.requestRender()
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
}
