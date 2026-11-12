package dev.drosh.terminal.engine.surface

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.drosh.domain.terminal.TerminalZoom
import dev.drosh.terminal.engine.input.PinchMachine
import dev.drosh.terminal.engine.input.ScrollMachine
import dev.drosh.terminal.engine.native.NativeInput
import dev.drosh.terminal.engine.native.NativePty
import dev.drosh.terminal.engine.native.NativeSelection
import dev.drosh.terminal.engine.native.NativeSession
import dev.drosh.terminal.engine.protocol.Frame
import dev.drosh.terminal.engine.render.RenderThread

/**
 * The terminal on screen.
 *
 * ## What it is
 *
 * A `SurfaceView` — not a `GLSurfaceView` — because the render loop is ours. See
 * [RenderThread] for why: `GLSurfaceView` swaps buffers whether or not a frame
 * was drawn (the one-frame wipe), loses its context on pause (the black
 * rectangle when a keyboard opens), and orders its resize callback against its
 * draw callback badly enough that a frame can be drawn at one size with a grid
 * from another.
 *
 * ## The thread it owns
 *
 * - **The render loop**, from [RenderThread]: GL, the atlas, the frame.
 *
 * The child's output is read by the session, not by this view — a session
 * outlives the pane that shows it, and the grid's thumbnail is made of output
 * that keeps arriving while another session is on screen. A blocking read on
 * the render thread would freeze the picture whenever the child was quiet; a
 * draw on the reader thread would put GL on a thread that did not create the
 * context.
 *
 * ## What it does not do
 *
 * It does not decide what a colour means, how wide a cell is, or when the grid
 * reflows. The engine answers the first, the font the second, and
 * [dev.drosh.terminal.engine.layout.LayoutMachine] the third.
 */
class TerminalSurface(context: Context) : SurfaceView(context), SurfaceHolder.Callback {


    /** Encode one mouse event and write it to the child; true when bytes went out. */
    private fun sendMouse(
        action: Int,
        button: Int,
        x: Float,
        y: Float,
        buttonPressed: Boolean,
    ): Boolean {
        // One crossing: the sequence is encoded and written to the PTY
        // together, so a tap or a drag does not build a byte array on the way.
        val session = sessionHandle
        val pty = ptyHandle
        if (session == 0L || pty == 0L) return false
        val written = NativeInput.encodeWriteMouse(
            session = session,
            pty = pty,
            action = action,
            button = button,
            x = x,
            y = y,
            width = width.coerceAtLeast(1),
            height = height.coerceAtLeast(1),
            cellWidth = cellWidth.toInt().coerceAtLeast(1),
            cellHeight = cellHeight.toInt().coerceAtLeast(1),
            buttonPressed = buttonPressed,
        )
        return written > 0
    }

    private fun startMouseDrag(x: Float, y: Float) {
        val session = sessionHandle
        if (session == 0L) return
        mouseDragging = true
        mouseDragCellX = (x / cellWidth.coerceAtLeast(1f)).toInt()
        mouseDragCellY = (y / cellHeight.coerceAtLeast(1f)).toInt()
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        sendMouse(
            NativeInput.MOUSE_ACTION_PRESS,
            NativeInput.MOUSE_BUTTON_LEFT,
            x,
            y,
            buttonPressed = true,
        )
    }

    /**
     * Carries an in-progress drag: each move emits one motion report with the
     * left button held, and only when the finger has entered a *new* cell —
     * the wire protocol is cell-grained, and re-reporting one cell would give
     * the program the same answer over and over. The lift emits the release.
     *
     * Consumes the whole stream, so the detectors never see the gesture.
     */
    private fun mouseDragTouch(event: MotionEvent): Boolean {
        if (sessionHandle == 0L) {
            // The session went away under the finger; nothing to release to.
            mouseDragging = false
            mouseDragCellX = -1
            mouseDragCellY = -1
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val cellW = cellWidth.coerceAtLeast(1f)
                val cellH = cellHeight.coerceAtLeast(1f)
                val column = (event.x / cellW).toInt()
                val row = (event.y / cellH).toInt()
                if (column == mouseDragCellX && row == mouseDragCellY) return true
                mouseDragCellX = column
                mouseDragCellY = row
                sendMouse(
                    NativeInput.MOUSE_ACTION_MOTION,
                    NativeInput.MOUSE_BUTTON_LEFT,
                    event.x,
                    event.y,
                    buttonPressed = true,
                )
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                releaseMouseDrag(event.x, event.y)
            }
        }
        return true
    }

    /** Releases a held drag and clears the drag state. */
    private fun releaseMouseDrag(x: Float, y: Float) {
        sendMouse(
            NativeInput.MOUSE_ACTION_RELEASE,
            NativeInput.MOUSE_BUTTON_LEFT,
            x,
            y,
            buttonPressed = false,
        )
        mouseDragging = false
        mouseDragCellX = -1
        mouseDragCellY = -1
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
    private fun handleMouseWheel(distanceX: Float, distanceY: Float, event: MotionEvent) {
        if (mouseAxis == AXIS_NONE) {
            mouseAxis = if (kotlin.math.abs(distanceY) >= kotlin.math.abs(distanceX)) {
                AXIS_VERTICAL
            } else {
                AXIS_HORIZONTAL
            }
            mouseWheelRemainder = 0f
        }
        if (mouseAxis == AXIS_VERTICAL) {
            mouseWheelRemainder += distanceY / cellHeight.coerceAtLeast(1f)
            val steps = mouseWheelRemainder.toInt()
            if (steps == 0) return
            mouseWheelRemainder -= steps
            val button =
                if (steps > 0) NativeInput.MOUSE_BUTTON_WHEEL_DOWN
                else NativeInput.MOUSE_BUTTON_WHEEL_UP
            emitWheel(button, kotlin.math.abs(steps), event.x, event.y)
        } else {
            mouseWheelRemainder += distanceX / cellWidth.coerceAtLeast(1f)
            val steps = mouseWheelRemainder.toInt()
            if (steps == 0) return
            mouseWheelRemainder -= steps
            val button =
                if (steps > 0) NativeInput.MOUSE_BUTTON_WHEEL_RIGHT
                else NativeInput.MOUSE_BUTTON_WHEEL_LEFT
            emitWheel(button, kotlin.math.abs(steps), event.x, event.y)
        }
    }

    /** Sends `count` wheel presses — encoded once, written once per step. */
    private fun emitWheel(button: Int, count: Int, x: Float, y: Float) {
        // The engine may answer nothing — the program left mouse mode between
        // two moves — and an empty write is not a report.
        repeat(count) {
            if (!sendMouse(
                    NativeInput.MOUSE_ACTION_PRESS,
                    button,
                    x,
                    y,
                    buttonPressed = false,
                )
            ) {
                return
            }
        }
    }

    /**
     * Encode one mouse event through the engine, and return the bytes.
     *
     * Empty when the engine answered nothing — an action the program's
     * tracking mode does not report — or the session is gone. The engine
     * decides the wire format (SGR 1006, X10, ...) from its own state; nothing
     * here does.
     */
    /** The pinch's arithmetic: dead zone, step clamp, measured from the origin. */
    private val pinchMachine = PinchMachine(
        minSp = TerminalZoom.MIN_SP,
        maxSp = TerminalZoom.MAX_SP,
        deadZone = TerminalZoom.DEAD_ZONE,
        maxStepFactor = TerminalZoom.MAX_STEP_FACTOR,
    )

    /** The scroll's arithmetic: whole rows plus the sub-line remainder. */
    private val scrollMachine = ScrollMachine()

    /** The size a pinch has reached, live, for the chip. Zero when none runs. */
    @Volatile
    var zoomSizeSp: Float = 0f
        private set

    /**
     * Told the live size while a pinch moves.
     *
     * The size is not reset to zero when the gesture ends: the size the
     * fingers left is the size the terminal is at until the setting catches
     * up, and resetting it would show the old size for a frame.
     */
    var zoomListener: (sp: Float) -> Unit = {}

    /** Told that a pinch has ended, whatever it settled on. */
    var zoomEndListener: () -> Unit = {}

    /**
     * Told the size a pinch settled on, once, when the fingers lift.
     *
     * The surface does not apply it: the size is a setting with one owner, and
     * the cell it implies comes back through [setCell] on the next layout.
     */
    var zoomCommitListener: (sp: Float) -> Unit = {}

    /**
     * The font's size in sp, as the host configured it — the pinch's origin.
     *
     * A pinch measures from this rather than from the last frame's rounded
     * value, so shrinking the fingers back to where they started returns to
     * exactly the size the gesture began at.
     */
    @Volatile
    var textSizeSp: Float = TerminalZoom.DEFAULT_SP

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinchMachine.begin(textSizeSp)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val frame = pinchMachine.next(detector.scaleFactor) ?: return true
                // The font grows with the fingers, and the font's size is the
                // cell's size, so the grid is laid out again as the gesture
                // runs. Nothing is scaled in the projection: a magnified
                // picture is not a terminal with bigger text.
                val size = pinchMachine.quantise(frame.targetSp, LIVE_ZOOM_STEP_SP)
                zoomSizeSp = size
                zoomListener(size)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                val settled = pinchMachine.end()
                if (settled != null) {
                    zoomCommitListener(pinchMachine.quantise(settled, TerminalZoom.STEP_SP))
                }
                zoomSizeSp = 0f
                zoomEndListener()
            }
        },
    )

    // The gesture detector's own double-tap detection is switched off: with it
    // on, the second tap of a pair is routed to the double-tap callback and
    // never reaches the single-tap one — and a terminal has to raise the
    // keyboard the instant the finger lands, not after a double-tap timeout.
    // The taps are counted here instead.
    /** The wheel drag's axis lock: 0 none, 1 vertical, 2 horizontal. */
    private var mouseAxis: Int = AXIS_NONE

    /** The wheel's sub-cell remainder along the locked axis, in cells. */
    private var mouseWheelRemainder: Float = 0f

    /**
     * True while a long-press has the left button held for a drag.
     *
     * The whole touch stream is consumed while it runs: its moves are motion
     * reports and must never reach the detectors, or a wheel scroll would fire
     * underneath the drag the program is trying to read.
     */
    private var mouseDragging: Boolean = false

    /** The last cell a motion report was sent for — the protocol is cell-grained. */
    private var mouseDragCellX: Int = -1
    private var mouseDragCellY: Int = -1

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean {
                // The wheel's axis lock belongs to one gesture; the next
                // gesture re-decides it.
                mouseAxis = AXIS_NONE
                mouseWheelRemainder = 0f
                return true
            }

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                // A program that tracks the mouse gets the tap as a left
                // click, and neither raises the keyboard nor drives selection
                // — so it stays outside the tap counter: a "double click"
                // there is just two clicks, which is what such programs expect.
                if (mouseReporting()) {
                    tapCount = 0
                    if (!hasFocus()) requestFocus()
                    sendMouseClick(event.x, event.y)
                    return true
                }
                handleTap(event)
                return true
            }

            override fun onLongPress(event: MotionEvent) {
                // With mouse reporting active, a long-press grabs the left
                // button so the following drag is reported to the program —
                // a region select, a slider, a divider — instead of starting
                // the local selection.
                if (mouseReporting()) {
                    startMouseDrag(event.x, event.y)
                    return
                }

                // Otherwise it is a selection: the gesture is the engine's, so
                // it selects the word under the finger and installs the
                // selection — which is what puts the selected cells in the
                // frame, and what the drag that follows grows.
                val session = sessionHandle
                if (session == 0L) return
                val cell = cellAt(event.x, event.y) ?: return
                if (NativeSelection.press(
                        session,
                        cell.first,
                        cell.second,
                        NativeSelection.BEHAVIOR_WORD,
                    ) != null
                ) {
                    longPressSelecting = true
                    selectionAnchor = cell
                    setSelectedCells(cell.first, cell.second, 1)
                    performHapticFeedback(
                        android.view.HapticFeedbackConstants.LONG_PRESS,
                    )
                }
            }

            override fun onScroll(
                first: MotionEvent?,
                current: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                // Mouse reporting takes precedence over the scrollback — but
                // only for one finger. Two fingers are the user's own scroll
                // and zoom: they fall through to the local path below, which
                // already gives a tracking program whole wheel rows.
                if (mouseReporting() && current.pointerCount == 1) {
                    handleMouseWheel(distanceX, distanceY, current)
                    return true
                }
                // A drag that started as a long press is the selection
                // growing, not the scrollback moving.
                val session = sessionHandle
                if (longPressSelecting && session != 0L) {
                    val cell = cellAt(current.x, current.y)
                    if (cell != null) {
                        val cells = NativeSelection.drag(
                            session,
                            cell.first,
                            cell.second,
                            (width / cellWidth.coerceAtLeast(1f)).toInt().coerceAtLeast(1),
                            cellWidth.toInt().coerceAtLeast(1),
                            height.coerceAtLeast(1),
                        )
                        if (cells != null && cells.size >= 4) {
                            setSelectedCells(cells[0], cells[1], selectionLength(cells))
                            setSelectionEnd(cells[2], cells[3])
                        }
                        // The selection grows from the anchor to the cell the
                        // finger is on.
                        val anchor = selectionAnchor
                        if (anchor != null) {
                            val fromRow = minOf(anchor.first, cell.first)
                            val toRow = maxOf(anchor.first, cell.first)
                            val fromColumn =
                                if (anchor.first == cell.first) {
                                    minOf(anchor.second, cell.second)
                                } else {
                                    0
                                }
                            val toColumn =
                                if (anchor.first == cell.first) {
                                    maxOf(anchor.second, cell.second)
                                } else {
                                    (width / cellWidth.coerceAtLeast(1f))
                                        .toInt().coerceAtLeast(1)
                                }
                            val length = (toRow - fromRow + 1) *
                                (toColumn - fromColumn + 1)
                            setSelectedCells(fromRow, fromColumn, length)
                        }
                        renderThread.invalidate()
                        return true
                    }
                }

                // GestureDetector reports `lastY - currentY`: dragging the
                // finger down gives a negative value, and down is the direction
                // that reveals older output. Flipping it once, here, keeps the
                // machine's own sign honest.
                dragByPixels(-distanceY)
                return true
            }

            override fun onFling(
                first: MotionEvent?,
                current: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                // Mouse reporting already emitted discrete wheel reports during
                // the drag; coasting the local viewport on top of them would
                // scroll the program twice for one flick.
                if (mouseReporting()) return false
                startFling(velocityY)
                return true
            }
        },
    )

    private var fling: android.widget.Scroller? = null

    /**
     * Whether a long press is growing a selection.
     *
     * The drag that follows belongs to the selection, not to the scrollback,
     * and it ends when the finger lifts.
     */
    private var longPressSelecting: Boolean = false

    /** Where a selection gesture started, in cells. */
    private var selectionAnchor: Pair<Int, Int>? = null

    /** The tap run: one tap, two taps, three taps. */
    private var tapCount: Int = 0
    private var lastTapAt: Long = 0L
    private var lastTapX: Float = 0f
    private var lastTapY: Float = 0f

    /** How long a second tap may follow the first and still be a double tap. */
    private val tapTimeoutMillis: Long =
        android.view.ViewConfiguration.getDoubleTapTimeout().toLong()

    /** How far the second tap may be and still be a double tap. */
    private val tapSlop: Float =
        android.view.ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat()

    /**
     * Whether the last scroll was refused because the viewport is against an
     * edge.
     *
     * The fling coasts until the engine says no; a coast that keeps asking
     * after the answer is a scroll that pushes against a wall.
     */
    private var lastScrollPinned: Boolean = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A second finger ends the mouse story: two fingers are the user's own
        // scroll and zoom, not the program's mouse. A drag whose button is
        // still held releases it first, so the program is not left holding a
        // button over a gesture that is no longer about it. The release goes
        // out at the *first* finger's position — on a pointer-down event, the
        // event's own x/y are the newest pointer's.
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && mouseDragging) {
            releaseMouseDrag(event.getX(0), event.getY(0))
        }

        // A drag in flight owns the whole gesture: its moves are motion
        // reports and must never reach the detectors, which would read the
        // same finger as a wheel scroll underneath the drag.
        if (mouseDragging && mouseDragTouch(event)) return true

        // The end of a selection gesture.
        //
        // The release is what tells the engine the gesture is over: until it
        // comes, the engine still thinks a finger is down and the selection is
        // still growing.
        if (longPressSelecting &&
            (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL)
        ) {
            val session = sessionHandle
            val cell = cellAt(event.x, event.y)
            if (session != 0L && cell != null) {
                val cells = NativeSelection.release(
                    session,
                    cell.first,
                    cell.second,
                    NativeSelection.BEHAVIOR_WORD,
                )
                if (cells != null && cells.size >= 4) {
                    setSelectedCells(cells[0], cells[1], selectionLength(cells))
                    setSelectionEnd(cells[2], cells[3])
                }
            }
            longPressSelecting = false
            renderThread.invalidate()
        }

        // A new gesture starts where the engine actually is.
        //
        // This is the one moment the two can be reconciled: the moves the last
        // gesture asked for have landed by now, so the engine's offset is the
        // picture's, and output that arrived while the user was not touching the
        // screen has already carried the view. Reconciling mid-gesture is what
        // made the scroll stutter — the engine is always one event behind.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // A new gesture starts from a row boundary: the picture is where
            // the last gesture left it, and that is a fraction of a row the
            // finger is about to move anyway.
            scrollMachine.reset()
        }

        // The scale detector sees every event; the gesture detector is told only
        // what the pinch did not claim, so two fingers are never a scroll.
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) {
            gestureDetector.onTouchEvent(event)
        }
        return true
    }

    /**
     * A tap, and what it counts as.
     *
     * One tap raises the keyboard, two select the word under the finger and
     * three select the line. The taps are counted here rather than by the
     * gesture detector, because its own double-tap detection hides the second
     * tap from the single-tap callback — and a terminal that has to raise the
     * keyboard the instant the finger lands cannot wait for a double-tap
     * timeout to find out.
     */
    private fun handleTap(event: MotionEvent) {
        val now = event.eventTime
        val continues = now - lastTapAt <= tapTimeoutMillis &&
            kotlin.math.abs(event.x - lastTapX) <= tapSlop &&
            kotlin.math.abs(event.y - lastTapY) <= tapSlop
        tapCount = if (continues) tapCount + 1 else 1
        lastTapAt = now
        lastTapX = event.x
        lastTapY = event.y

        when (tapCount) {
            1 -> {
                if (!hasFocus()) {
                    requestFocus()
                    requestKeyboard()
                }
            }
            // The gesture is the engine's, and it is told what this click
            // means: a double tap is a word, a triple tap is the line. The
            // engine installs the selection, so the cells it covers come back
            // marked in the frame — a selection that is only remembered as text
            // is a selection the user cannot see.
            2 -> selectByGesture(event.x, event.y, NativeSelection.BEHAVIOR_WORD)
            else -> selectByGesture(event.x, event.y, NativeSelection.BEHAVIOR_LINE)
        }
    }

    /**
     * Selects with the engine's gesture.
     *
     * A press and a release, with the behaviour the caller means. The engine
     * answers with the cells the selection covers, and those are the cells the
     * chrome's menu hangs on.
     */
    private fun selectByGesture(x: Float, y: Float, behavior: Int): Boolean {
        val session = sessionHandle
        if (session == 0L) return false
        val cell = cellAt(x, y) ?: return false
        val pressed = NativeSelection.press(session, cell.first, cell.second, behavior)
        val released = NativeSelection.release(session, cell.first, cell.second, behavior)
        val cells = released ?: pressed
        if (cells == null || cells.size < 4) return false

        selected = NativeSelection.text(session)
        selectionRect = null
        setSelectedCells(cells[0], cells[1], selectionLength(cells))
        setSelectionEnd(cells[2], cells[3])
        renderThread.invalidate()
        return selected.isNotEmpty()
    }

    /**
     * How many cells the selection covers.
     *
     * The engine answers with the start and the end of the range; the width is
     * the columns between them, and a selection that wraps to the next line
     * covers that line's cells too.
     */
    private fun selectionLength(cells: IntArray): Int {
        val startRow = cells[0]
        val startColumn = cells[1]
        val endRow = cells[2]
        val endColumn = cells[3]
        val columns = (width / cellWidth.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        if (startRow == endRow) return (endColumn - startColumn + 1).coerceAtLeast(1)
        return (endRow - startRow + 1) * columns
    }

    /**
     * The cell under a point, and the selection it asks for.
     *
     * The picture may be shifted inside the viewport — the user dragged between
     * two rows — and the tap is on the picture, not on the grid, so the shift is
     * taken off before the row is counted.
     */
    /**
     * The cell under a point, as a row and a column.
     *
     * The picture may be shifted inside the viewport — the user dragged
     * between two rows — and the point is on the picture, not on the grid, so
     * the shift is taken off before the row is counted.
     */
    private fun cellAt(x: Float, y: Float): Pair<Int, Int>? {
        val cellW = cellWidth.coerceAtLeast(1f)
        val cellH = cellHeight.coerceAtLeast(1f)
        val column = (x / cellW).toInt()
        val row = ((y - renderThread.scrollOffsetPx) / cellH).toInt()
        if (row < 0 || column < 0) return null
        return row to column
    }

    private fun selectAt(x: Float, y: Float, word: Boolean): Boolean {
        val cellW = cellWidth.coerceAtLeast(1f)
        val cellH = cellHeight.coerceAtLeast(1f)
        val column = (x / cellW).toInt()
        val row = ((y - renderThread.scrollOffsetPx) / cellH).toInt()
        if (row < 0) return false
        return applySelection(row, column, word)
    }

    /**
     * Put the picture back on a row boundary.
     *
     * The sub-row shift belongs to a finger that is on the screen. When the
     * viewport moves for another reason — the engine jumped to a prompt — the
     * shift has to go with it, or the picture stays a fraction of a row off
     * the row it belongs to.
     */
    fun resetScrollOffset() {
        scrollMachine.reset()
        renderThread.post(RenderThread.Command.ScrollOffset(0f))
        renderThread.invalidate()
    }

    /** Move the viewport by a finger's travel, in pixels. */
    private fun dragByPixels(distancePx: Float) {
        val session = sessionHandle
        val spacing = cellHeight.coerceAtLeast(1f)

        // A full-screen program and a mouse-tracking program own their scroll:
        // they get whole rows, and there is no sub-line position to keep.
        // `modes` bit 0 is mouse tracking, and the engine's own doc says the
        // alternate screen owns its scroll. A program in either case wants whole
        // wheel rows, not a sub-line offset it did not ask for.
        val bits = if (session != 0L) NativeSession.modes(session) else 0
        val mouseTracking = bits and MODE_MOUSE_TRACKING != 0
        if (session != 0L && (NativeSession.alternateScreen(session) || mouseTracking)) {
            val rows = (distancePx / spacing).toInt()
            if (rows != 0) {
                NativeSession.scroll(session, NativeSession.SCROLL_DELTA, -rows)
                renderThread.invalidate()
            }
            return
        }

        if (session == 0L) return

        // The finger's travel goes into the machine, which turns it into whole
        // rows for the engine and a shift for the picture.
        // The wall, when the viewport is already at the oldest row: there is
        // nothing above it to reveal.
        val atTop = (NativeSession.scrollbar(session)?.offset ?: 0) <= 0
        val target = scrollMachine.drag(
            distancePx = distancePx,
            spacingPx = spacing,
            atTop = atTop,
        )

        // The engine moves by a delta, never to an absolute row: a delta
        // cannot be refused half-way, and the engine's answer is the truth
        // about where the viewport is.
        var pinned = false
        if (target.delta != 0) {
            val before = NativeSession.scrollbar(session)?.offset
            NativeSession.scroll(session, NativeSession.SCROLL_DELTA, target.delta)
            val after = NativeSession.scrollbar(session)?.offset
            val moved = before != null && after != null && after - before == target.delta
            if (!moved) {
                // The viewport is against an edge. The shift that was left over
                // cannot be shown there, so it goes.
                scrollMachine.pinned()
                pinned = true
            } else if (after == 0) {
                // The oldest row: there is nothing above it.
                scrollMachine.atOldestRow()
            }
        }

        // The picture's shift goes to the render thread, which draws the grid
        // moved down by it — the row above the viewport filling the gap.
        renderThread.post(RenderThread.Command.ScrollOffset(scrollMachine.shift()))
        renderThread.invalidate()
        lastScrollPinned = pinned
    }

    /** Momentum after a flick, through the same pixel path as a drag. */
    private fun startFling(velocityY: Float) {
        val scroller = fling ?: android.widget.Scroller(context).also { fling = it }
        if (!scroller.isFinished) return
        // The detector's velocities are tuned for lists; a terminal that moved
        // at that speed scrolled a screenful per flick. The vendored view used
        // a quarter of it, and so does this.
        // The flick continues the drag.
        //
        // The detector's velocity is positive when the finger moved up, and up
        // means the content keeps moving up — a negative distance for the drag
        // path. The scroller's position therefore has to shrink, which is a
        // negative velocity.
        scroller.fling(
            0, 0,
            0, -(velocityY * FLING_SCALE).toInt(),
            0, 0,
            Int.MIN_VALUE, Int.MAX_VALUE,
        )
        var lastY = 0
        val step = object : Runnable {
            override fun run() {
                if (scroller.isFinished) return
                scroller.computeScrollOffset()
                val y = scroller.currY
                dragByPixels((y - lastY).toFloat())
                lastY = y
                // The coast ends at the edge: the engine refuses the move and
                // the picture cannot be pulled further.
                if (lastScrollPinned) return
                postOnAnimation(this)
            }
        }
        postOnAnimation(step)
    }

    /** Ask the IME to show, for a view that already has the focus. */
    fun requestKeyboard() {
        val manager = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager ?: return
        manager.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection {
        // TYPE_NULL: the IME offers no suggestions and no autocorrect. A
        // terminal has no use for a spell-checker over shell commands.
        outAttrs.inputType = android.text.InputType.TYPE_NULL
        outAttrs.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN

        return object : android.view.inputmethod.BaseInputConnection(this@TerminalSurface, false) {

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) {
                    // AOSP keyboards send Enter as text, not as a key event. A
                    // terminal wants CR: the tty's ICRNL turns it into a
                    // newline for the shell.
                    send(text.toString().replace('\n', '\r').toByteArray(Charsets.UTF_8))
                }
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // Held, not sent: the keyboard may still replace it, and a shell
                // cannot take a character back.
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // The same one-byte array every time: a DEL per deletion was
                // an allocation the child cannot tell from a reused one.
                repeat(beforeLength) { send(DEL_BYTES) }
                return true
            }

            override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    sendKey(event)
                    return true
                }
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        sendKey(event)
        return true
    }

    /**
     * Encode one key and write it to the child, in one native call.
     *
     * The engine owns the rules: `DECCKM`'s `ESC O A` versus `ESC [ A`, the
     * kitty protocol's form, whether a modifier is a parameter or a prefix.
     * The Android key code is the only thing translated here — and even that
     * less than before: the character crosses as the codepoint
     * `KeyEvent.unicodeChar` already is, rather than as a `String` made from
     * it.
     *
     * A session with no PTY has nowhere to put the bytes; the old path still
     * encoded and then dropped them, so skipping the encode is the same
     * behaviour without the work.
     */
    private fun sendKey(event: android.view.KeyEvent) {
        val session = sessionHandle
        if (session == 0L) return
        val pty = ptyHandle
        if (pty == 0L) return

        NativeInput.encodeWriteKey(
            session = session,
            pty = pty,
            keyCode = event.keyCode,
            metaState = event.metaState,
            action = when (event.action) {
                android.view.KeyEvent.ACTION_DOWN -> NativeInput.ACTION_PRESS
                android.view.KeyEvent.ACTION_UP -> NativeInput.ACTION_RELEASE
                else -> NativeInput.ACTION_PRESS
            },
            // Zero when the key produced no character; the engine reads the
            // character's own bytes from it, so no String is made per key.
            unicode = event.unicodeChar,
        )
    }

    /**
     * Whether the viewport is at the live edge.
     *
     * The app knows: the manager follows the scroll and publishes it. Asking
     * the engine instead would be a crossing and a lock for every character
     * the keyboard sends, and the keyboard sends a lot of characters.
     */
    @Volatile
    var atLiveEdge: () -> Boolean = { true }

    /** Send bytes from the IME straight to the child. */
    private fun send(bytes: ByteArray) {
        // Typing means the user is looking at the prompt, not at the scrollback.
        // A terminal that leaves the view where it was sends the keystrokes to a
        // shell whose output the user cannot see.
        val session = sessionHandle
        if (session != 0L && !atLiveEdge()) {
            NativeSession.scroll(session, NativeSession.SCROLL_BOTTOM, 0)
            scrollMachine.reset()
            renderThread.post(RenderThread.Command.ScrollOffset(0f))
        }

        write(bytes)
        renderThread.invalidate()
    }

    /** Write bytes to the child, e.g. a key sequence. */
    fun write(bytes: ByteArray) {
        val pty = ptyHandle
        if (pty == 0L) return
        NativePty.write(pty, bytes)
    }

    // --- selection ---------------------------------------------------------
    //
    // Selection lives in the engine: the word boundaries are its semantic
    // rules and the cell widths are its own. What is kept here is the *text*
    // and the bounds, because the clipboard and the handles are the platform's.

    @Volatile
    private var selected: String = ""

    @Volatile
    private var selectionRect: android.graphics.Rect? = null

    /**
     * The selected cells, as a row, a column and a length.
     *
     * The rectangle the chrome hangs the selection menu on is these cells'
     * rectangle: the menu has to know where the selection is on the screen, and
     * the cells are where it is.
     */
    private var selectedCells: Triple<Int, Int, Int>? = null

    /**
     * Told that the selection changed.
     *
     * The chrome's selection menu hangs off this: without it the menu never
     * comes up, and a selection that cannot be copied is a selection that does
     * not exist.
     */
    var onSelectionChanged: ((Boolean) -> Unit)? = null

    /** Where the selection ends, in cells: the other handle's anchor. */
    private var selectionEnd: Pair<Int, Int>? = null

    /** The selected text, or "". */
    fun selectedText(): String {
        // The gesture's selection lives in the engine, and the engine is the
        // only one that knows what it covers. The tap's selection is the
        // snapshot this view took, because that one the engine does not keep.
        val session = sessionHandle
        if (session != 0L) {
            val engineText = NativeSelection.text(session)
            if (engineText.isNotEmpty()) return engineText
        }
        return selected
    }

    /** True when something is selected. */
    fun hasSelection(): Boolean = selectedText().isNotEmpty()

    /** The selection's bounds in pixels, or null. */
    fun selectionBounds(): android.graphics.Rect? {
        val cells = selectedCells ?: return selectionRect
        val cellW = cellWidth.coerceAtLeast(1f)
        val cellH = cellHeight.coerceAtLeast(1f)
        // The picture may be shifted inside the viewport, and the cells are
        // where the *grid* is, so the shift comes off.
        val top = cells.first * cellH - renderThread.scrollOffsetPx
        return android.graphics.Rect(
            (cells.second * cellW).toInt(),
            top.toInt(),
            ((cells.second + cells.third) * cellW).toInt(),
            (top + cellH).toInt(),
        )
    }

    /**
     * Records the selected cells and tells the chrome.
     *
     * The row and the column are the grid's; the length is how many cells the
     * selection covers.
     */
    private fun setSelectedCells(row: Int, column: Int, length: Int) {
        selectedCells = Triple(row, column, length.coerceAtLeast(1))
        onSelectionChanged?.invoke(true)
    }

    /** Records the selection's end, from the engine's own answer. */
    private fun setSelectionEnd(row: Int, column: Int) {
        selectionEnd = row to column
    }

    /** Select the word under a cell; returns true when something was selected. */
    fun selectWord(row: Int, column: Int): Boolean = applySelection(row, column, word = true)

    /** Select the line under a cell. */
    fun selectLine(row: Int, column: Int): Boolean = applySelection(row, column, word = false)

    /** Select everything the engine holds. */
    fun selectAll(): Boolean {
        val session = sessionHandle
        if (session == 0L) return false
        selected = NativeSelection.selectAll(session)
        selectionRect = null
        renderThread.invalidate()
        return selected.isNotEmpty()
    }

    /** Clear the selection. */
    fun clearSelection() {
        selected = ""
        selectionRect = null
        selectedCells = null
        onSelectionChanged?.invoke(false)
        renderThread.invalidate()
    }

    /** Copy the selection to the clipboard; true when there was something. */
    fun copySelection(): Boolean {
        if (selected.isEmpty()) return false
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            ?: return false
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("terminal", selected),
        )
        return true
    }

    private fun applySelection(row: Int, column: Int, word: Boolean): Boolean {
        val session = sessionHandle
        if (session == 0L) return false
        selected = if (word) {
            NativeSelection.selectWord(session, row, column)
        } else {
            NativeSelection.selectLine(session, row, column)
        }
        selectionRect = null
        // The cells the selection covers: the text's own length is the width of
        // a word, and a line is as long as the text it holds.
        setSelectedCells(row, column, selected.length.coerceAtLeast(1))
        renderThread.invalidate()
        return selected.isNotEmpty()
    }

    /**
     * Ask for a redraw.
     *
     * `View.invalidate()` already means "redraw me" — it schedules the
     * platform's own draw pass, which a `SurfaceView` does not use. Overriding
     * it keeps the meaning and points it at the loop that does the drawing, so
     * a caller that invalidates the view gets a frame rather than nothing.
     */
    override fun invalidate() {
        super.invalidate()
        renderThread.invalidate()
    }

    // --- the frame ----------------------------------------------------------

    /**
     * Ask the engine for the next frame.
     *
     * Called on the render thread, once per drawn frame. A null answer means
     * the engine had nothing coherent to say, and the loop leaves the previous
     * picture alone rather than presenting an empty one.
     */
    private fun frame(): Frame? {
        val session = sessionHandle
        if (session == 0L) return null
        return NativeSession.frame(session)
    }

    // --- reading ------------------------------------------------------------
    //
    // The child's output is read by the *session*, not by this view: a session
    // outlives the pane that shows it, and the grid's thumbnail is made of
    // output that keeps arriving while another session is on screen. A reader
    // here would stop the moment the pane showed something else.

    private companion object {
        /** DEL (0x7f), what a terminal expects for backspace. */
        const val DEL: Byte = 0x7f

        /** DEL as the wire carries it, made once and written many times. */
        val DEL_BYTES: ByteArray = byteArrayOf(DEL)

        /** Bit 0 of the engine's mode bits is mouse tracking. */
        const val MODE_MOUSE_TRACKING = 1

        /** The wheel drag's axis lock: unlocked, then the two axes. */
        const val AXIS_NONE = 0
        const val AXIS_VERTICAL = 1
        const val AXIS_HORIZONTAL = 2

        /** How much of the detector's fling velocity a terminal uses. */
        const val FLING_SCALE = 0.25f

        /**
         * The step the live size moves in while the fingers are moving.
         *
         * Every step is a new cell, and a new cell is a new grid shape, and a
         * new grid shape is a resize the shell hears about. Half a point is
         * smooth to the eye and a fifth of the work the tenth of a point
         * would be; the size the fingers settle on is committed at the
         * setting's own step.
         */
        const val LIVE_ZOOM_STEP_SP = 0.5f

    }
}
