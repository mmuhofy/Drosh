package dev.drosh.terminal.engine.surface

import android.view.MotionEvent
import dev.drosh.terminal.engine.input.InputStats
import dev.drosh.terminal.engine.native.NativeInput
import dev.drosh.terminal.engine.native.NativeSession

/**
 * The mouse half of the surface's gestures.
 *
 * A program in a tracking mode has asked to be told about taps, drags and
 * wheels, and the surface reports them through here: the events are encoded by
 * the engine, in the wire format the program's own mode chose, and written to
 * the child in one crossing.
 *
 * What is *not* here is the decision of whether the program is tracking: the
 * host's master switch and the session's mode bits decide that, and the surface
 * asks before it routes a gesture this way.
 *
 * The state below is the gesture's, not the view's: a drag holds a button down
 * across many events, and a wheel's axis is locked on its first move so a
 * slightly diagonal swipe does not report both axes.
 *
 * @param host what it reports to: the session, the grid, the cell, and the
 *   touch feedback a long press gives.
 */
internal class TerminalMouse(private val host: Host) {

    /** What the mouse needs of the surface showing it. */
    interface Host {
        /** The session to report to. Zero when there is none. */
        val session: Long

        /** The process to write to. Zero when the session is bridged. */
        val pty: Long

        /** The grid's width, in cells. */
        val gridWidth: Int

        /** The grid's height, in cells. */
        val gridHeight: Int

        /** The cell's width in pixels, for turning a position into a cell. */
        val cellWidth: Float

        /** The cell's height in pixels. */
        val cellHeight: Float

        /** A long press's feedback, so the grab is felt. */
        fun haptic()
    }

    /**
     * The user's master switch for mouse reporting, pushed in by the host.
     *
     * Off, gestures always drive the local scrollback and the keyboard,
     * whatever the running program asked for. On, gestures are reported to
     * programs that turned a tracking mode on, and stay local otherwise.
     */
    @Volatile
    var enabled: Boolean = true

    /** The wheel drag's axis lock: 0 none, 1 vertical, 2 horizontal. */
    private var axis: Int = AXIS_NONE

    /** The wheel's sub-cell remainder along the locked axis, in cells. */
    private var wheelRemainder: Float = 0f

    /**
     * True while a long-press has the left button held for a drag.
     *
     * The whole touch stream is consumed while it runs: its moves are motion
     * reports and must never reach the detectors, or a wheel scroll would fire
     * underneath the drag the program is trying to read.
     */
    var dragging: Boolean = false
        private set

    /** The last cell a motion report was sent for — the protocol is cell-grained. */
    private var dragCellX: Int = -1
    private var dragCellY: Int = -1

    /** True while a program's tracking mode is on and the host allows reporting. */
    val active: Boolean
        get() {
            if (!enabled) return false
            val session = host.session
            if (session == 0L) return false
            return NativeSession.modes(session) and MODE_MOUSE_TRACKING != 0
        }

    /**
     * A left-button click: press, then release, at the finger's position.
     *
     * Both travel even though the encoder may answer only one — a tracking
     * mode that reports releases itself (SGR 1006 does) wants the pair.
     */
    fun click(x: Float, y: Float) {
        send(NativeInput.MOUSE_ACTION_PRESS, NativeInput.MOUSE_BUTTON_LEFT, x, y, buttonPressed = false)
        send(NativeInput.MOUSE_ACTION_RELEASE, NativeInput.MOUSE_BUTTON_LEFT, x, y, buttonPressed = false)
    }

    /** Grabs the left button at the long press, and remembers the cell it landed on. */
    fun startDrag(x: Float, y: Float) {
        if (host.session == 0L) return
        dragging = true
        dragCellX = (x / host.cellWidth.coerceAtLeast(1f)).toInt()
        dragCellY = (y / host.cellHeight.coerceAtLeast(1f)).toInt()
        host.haptic()
        send(NativeInput.MOUSE_ACTION_PRESS, NativeInput.MOUSE_BUTTON_LEFT, x, y, buttonPressed = true)
    }

    /**
     * Carries an in-progress drag: each move emits one motion report with the
     * left button held, and only when the finger has entered a *new* cell —
     * the wire protocol is cell-grained, and re-reporting one cell would give
     * the program the same answer over and over. The lift emits the release.
     *
     * Consumes the whole stream, so the detectors never see the gesture.
     */
    fun dragTouch(event: MotionEvent): Boolean {
        if (host.session == 0L) {
            // The session went away under the finger; nothing to release to.
            forgetDrag()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val cellW = host.cellWidth.coerceAtLeast(1f)
                val cellH = host.cellHeight.coerceAtLeast(1f)
                val column = (event.x / cellW).toInt()
                val row = (event.y / cellH).toInt()
                if (column == dragCellX && row == dragCellY) return true
                dragCellX = column
                dragCellY = row
                send(
                    NativeInput.MOUSE_ACTION_MOTION,
                    NativeInput.MOUSE_BUTTON_LEFT,
                    event.x,
                    event.y,
                    buttonPressed = true,
                )
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                releaseDrag(event.x, event.y)
            }
        }
        return true
    }

    /** Releases a held drag and clears the drag state. */
    fun releaseDrag(x: Float, y: Float) {
        send(NativeInput.MOUSE_ACTION_RELEASE, NativeInput.MOUSE_BUTTON_LEFT, x, y, buttonPressed = false)
        forgetDrag()
    }

    /**
     * Drops the drag's state without telling the program.
     *
     * For when the session the press belonged to is gone: a release sent to the
     * next one would be a click the new program never asked for.
     */
    fun forgetDrag() {
        dragging = false
        dragCellX = -1
        dragCellY = -1
    }

    /** The next gesture re-decides the wheel's axis and its remainder. */
    fun resetWheel() {
        axis = AXIS_NONE
        wheelRemainder = 0f
    }

    /**
     * Encodes a swipe as wheel scroll along the gesture's dominant axis,
     * locked on the first move so a slightly diagonal drag does not emit both
     * vertical and horizontal wheel reports.
     *
     * The detector's `distanceY` is already the right sign for the wire:
     * dragging the finger *up* gives a positive value, and up is the swipe
     * that reveals lower content — wheel down. Whole cells crossed emit one
     * wheel press each, the remainder carried over to the next move.
     */
    fun wheel(distanceX: Float, distanceY: Float, x: Float, y: Float) {
        if (axis == AXIS_NONE) {
            axis = if (kotlin.math.abs(distanceY) >= kotlin.math.abs(distanceX)) {
                AXIS_VERTICAL
            } else {
                AXIS_HORIZONTAL
            }
            wheelRemainder = 0f
        }
        if (axis == AXIS_VERTICAL) {
            wheelRemainder += distanceY / host.cellHeight.coerceAtLeast(1f)
            val steps = wheelRemainder.toInt()
            if (steps == 0) return
            wheelRemainder -= steps
            val button =
                if (steps > 0) NativeInput.MOUSE_BUTTON_WHEEL_DOWN
                else NativeInput.MOUSE_BUTTON_WHEEL_UP
            emitWheel(button, kotlin.math.abs(steps), x, y)
        } else {
            wheelRemainder += distanceX / host.cellWidth.coerceAtLeast(1f)
            val steps = wheelRemainder.toInt()
            if (steps == 0) return
            wheelRemainder -= steps
            val button =
                if (steps > 0) NativeInput.MOUSE_BUTTON_WHEEL_RIGHT
                else NativeInput.MOUSE_BUTTON_WHEEL_LEFT
            emitWheel(button, kotlin.math.abs(steps), x, y)
        }
    }

    /** Sends `count` wheel presses — encoded once, written once per step. */
    private fun emitWheel(button: Int, count: Int, x: Float, y: Float) {
        // The engine may answer nothing — the program left mouse mode between
        // two moves — and an empty write is not a report.
        repeat(count) {
            if (!send(NativeInput.MOUSE_ACTION_PRESS, button, x, y, buttonPressed = false)) {
                return
            }
        }
    }

    private fun send(
        action: Int,
        button: Int,
        x: Float,
        y: Float,
        buttonPressed: Boolean,
    ): Boolean {
        // One crossing: the sequence is encoded and written to the PTY
        // together, so a tap or a drag does not build a byte array on the way.
        val session = host.session
        val pty = host.pty
        if (session == 0L || pty == 0L) return false
        val started = System.nanoTime()
        val written = NativeInput.encodeWriteMouse(
            session = session,
            pty = pty,
            action = action,
            button = button,
            x = x,
            y = y,
            width = host.gridWidth.coerceAtLeast(1),
            height = host.gridHeight.coerceAtLeast(1),
            cellWidth = host.cellWidth.toInt().coerceAtLeast(1),
            cellHeight = host.cellHeight.toInt().coerceAtLeast(1),
            buttonPressed = buttonPressed,
        )
        InputStats.mouse(action, written, System.nanoTime() - started)
        return written > 0
    }

    private companion object {
        /** Bit 0 of the engine's mode bits is mouse tracking. */
        const val MODE_MOUSE_TRACKING = 1

        /** The wheel drag's axis lock: unlocked, then the two axes. */
        const val AXIS_NONE = 0
        const val AXIS_VERTICAL = 1
        const val AXIS_HORIZONTAL = 2
    }
}
