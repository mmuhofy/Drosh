package dev.drosh.terminal.engine.surface

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.view.SurfaceView
import dev.drosh.domain.terminal.TerminalZoom
import dev.drosh.terminal.engine.input.PinchMachine
import dev.drosh.terminal.engine.input.ScrollMachine
import dev.drosh.terminal.engine.input.SelectionCell
import dev.drosh.terminal.engine.input.SelectionEdges
import dev.drosh.terminal.engine.input.SelectionSnapshot
import dev.drosh.terminal.engine.input.SelectionText
import dev.drosh.terminal.engine.native.NativeInput
import dev.drosh.terminal.engine.native.NativePty
import dev.drosh.terminal.engine.native.NativeSelection
import dev.drosh.terminal.engine.native.NativeSession
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

    private var ptyHandle: Long = 0

    /** The session handle, for frames and keys. */
    @Volatile
    private var sessionHandle: Long = 0

    /** The font's own size, in pixels: what the glyphs are rasterised at. */
    @Volatile
    private var fontPx: Float = 0f

    /** The cell size the font measured, in pixels. */
    @Volatile
    private var cellWidth = 1f

    @Volatile
    private var cellHeight = 1f

    /**
     * The render loop.
     *
     * Created here so the surface and the loop have the same lifetime: a loop
     * without a surface draws into nothing, and a surface without a loop is a
     * black rectangle.
     */
    private val renderThread = RenderThread { timing ->
        if (timing.swapped) {
            framesDrawn += 1
            lastCellsDrawn = timing.cellsDrawn
        }
    }

    /** The last frame's shape, as the engine reported it. */
    @Volatile
    var lastFrameShape: String = "none"

    /** Why the renderer is not drawing, when it is not. */
    val rendererFailure: String? get() = renderThread.rendererFailure

    /** Cells the last drawn frame carried. */
    @Volatile
    var lastCellsDrawn: Int = 0
        private set

    /** Frames the loop drew. */
    @Volatile
    var framesDrawn: Int = 0
        private set

    /** Told when the child exits, with its status. */
    var onExit: ((Int) -> Unit)? = null

    /** Told when the grid's shape changed, so the PTY can be resized too. */
    var onGridChanged: ((columns: Int, rows: Int) -> Unit)? = null

    init {
        renderThread.onFrameShape = {
                columns: Int,
                rows: Int,
                cells: Int,
                stepX: Float,
                stepY: Float,
                cursorColumn: Int,
                cursorRow: Int,
                cursorStyle: Int,
                cursorVisible: Boolean,
                cursorColor: Int,
                cellColours: String,
            ->
            lastFrameShape = "${columns}x$rows cells=$cells " +
                "step=${"%.1f".format(stepX)}x${"%.1f".format(stepY)} " +
                // The cursor, as the engine reported it: where it is, what it
                // looks like, and the colour it was given. A cursor in the
                // wrong place is a question the numbers answer and the code
                // cannot.
                "cursor=$cursorColumn,$cursorRow style=$cursorStyle " +
                "vis=$cursorVisible #${"%06x".format(cursorColor and 0xffffff)}" +
                cellColours
        }
        holder.addCallback(this)
        // The surface draws its own pixels; the platform must not clear it to
        // the window background between frames.
        holder.setFormat(android.graphics.PixelFormat.OPAQUE)
        setZOrderOnTop(false)

        // The face, before the loop starts: the atlas rasterises with it and
        // the host measures its cell with it.
        renderThread.typeface = dev.drosh.terminal.engine.font.TerminalFont.of(context)
        renderThread.frameSource = { buffer -> frameInto(buffer) }
        // The pictures a program drew.
        renderThread.imageSource = {
            val session = sessionHandle
            if (session == 0L) null else NativeSession.images(session)
        }
        renderThread.onGridChange = { columns, rows ->
            // Three things have to agree about the shape: the grid drawn, the
            // engine's own terminal, and the process. The engine's terminal is
            // the one that decides where a line wraps, so a resize that missed
            // it would lay the shell's output out for a shape the screen does
            // not have.
            val session = sessionHandle
            if (session != 0L) {
                // Where the viewport was, so it can be put back.
                //
                // A keyboard opening changes the height, and a resize that
                // loses the position throws the user back to the live edge —
                // the screen jumps while the keyboard is still moving. The
                // rows of the scrollback do not move when only the height
                // changes, so the same offset is the same place.
                val before = NativeSession.scrollbar(session)
                NativeSession.resize(session, columns, rows, cellWidth.toInt(), cellHeight.toInt())
                val after = NativeSession.scrollbar(session)
                if (before != null && after != null && before.offset != after.offset) {
                    // Put the viewport back where it was: the same absolute
                    // row, asked for by row. A resize that loses the position
                    // throws the user back to the live edge while the keyboard
                    // is still moving.
                    NativeSession.scroll(session, NativeSession.SCROLL_ROW, after.offset)
                }
                // Reflow changes every cell's row and column. The selection
                // is kept by the engine in grid references, so it survives the
                // change; only its viewport shape has to be read again.
                syncSelectionAfterViewportChange()
                // The picture's shift is measured in the old cell's pixels, and
                // the cell is what changed: it starts again from a row.
                scrollMachine.reset()
                renderThread.post(RenderThread.Command.ScrollOffset(0f))
            }
            val pty = ptyHandle
            if (pty != 0L) {
                NativePty.resize(pty, columns, rows, cellWidth.toInt(), cellHeight.toInt())
            }
            onGridChanged?.invoke(columns, rows)
        }
    }

    // --- lifecycle ----------------------------------------------------------

    /**
     * The window's focus decides the cursor's shape: a terminal that is not
     * where the user is typing draws a hollow cursor, so the cursor says where
     * it is without pretending to be the place the keystrokes go.
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        renderThread.focused = hasWindowFocus
        renderThread.invalidate()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // The gesture detector's own double-tap detection is off: with it on,
        // the second tap of a pair never reaches the single-tap callback, and
        // a terminal has to raise the keyboard the instant the finger lands.
        // The taps are counted in `handleTap` instead.
        gestureDetector.setOnDoubleTapListener(null)

        renderThread.start()
        renderThread.post(
            RenderThread.Command.Surface(holder.surface, width, height),
        )
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderThread.post(RenderThread.Command.Surface(holder.surface, width, height))
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // The surface is going away, not the context: detach the window and
        // keep the GL context alive, so a keyboard opening does not have to
        // rebuild the atlas.
        renderThread.post(RenderThread.Command.Surface(null, 0, 0))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderThread.stop()
    }

    // --- session ------------------------------------------------------------

    /**
     * Attach a terminal and its process.
     *
     * Both handles come from the native library; this view does not create
     * either. A session without a PTY is legitimate — a bridged session reads
     * from elsewhere — and a PTY without a session is not.
     */
    fun attach(session: Long, pty: Long, cellWidthPx: Float, cellHeightPx: Float) {
        sessionHandle = session
        ptyHandle = pty
        cellWidth = cellWidthPx
        cellHeight = cellHeightPx
        // A new session, a new viewport: the fraction the last one was left at
        // belongs to rows that are not there any more.
        scrollMachine.reset()
        // The cell first, the surface second: a surface laid out against the
        // default one-pixel cell is a grid of a thousand columns.
        renderThread.post(RenderThread.Command.Cell(cellWidthPx, cellHeightPx, 0f))
        // And the screen is wiped: the session behind this pane is a different
        // one now, and until its shell prints there is nothing to draw — the
        // renderer keeps the previous picture when a frame draws nothing, so
        // without this the old session stays on screen.
        renderThread.post(RenderThread.Command.Clear)
        renderThread.invalidate()
    }

    /**
     * A program rang the bell.
     *
     * The terminal flashes, and the app is told so it can buzz and, when the
     * app is not on screen, say it in a notification.
     */
    /** The colour the bell's wash is in, from the theme. */
    fun setBellColour(argb: Int) {
        renderThread.post(RenderThread.Command.BellColour(argb))
    }

    fun bell() {
        renderThread.post(RenderThread.Command.Bell)
        renderThread.invalidate()
        // The buzz is the phone's half of the bell: the flash is the screen's.
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /**
     * The picture behind the terminal.
     *
     * The view's own background is the picture and the surface is wiped
     * transparent, so the picture shows through the parts of the grid that
     * have no colour of their own. Without a picture the terminal paints its
     * own background, as it always did.
     */
    fun setBackgroundImage(bitmap: android.graphics.Bitmap?, opacity: Int) {
        if (bitmap == null) {
            background = null
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            renderThread.post(RenderThread.Command.TransparentBackground(false))
            renderThread.invalidate()
            return
        }

        val drawable = android.graphics.drawable.BitmapDrawable(resources, bitmap).apply {
            alpha = opacity.coerceIn(0, 255)
        }
        background = drawable
        setBackground(drawable)
        renderThread.post(RenderThread.Command.TransparentBackground(true))
        renderThread.invalidate()
    }

    /**
     * Moves the selection's end to a point.
     *
     * The handles at the ends of a selection are the way a user changes a
     * selection they did not get right: the anchor is the *other* end, so the
     * drag grows the selection towards the finger.
     */
    fun extendSelectionTo(x: Float, y: Float, topLeft: Boolean): Boolean {
        val session = sessionHandle
        if (session == 0L) return false
        val cell = cellAt(x, y)
        val rows = (height / cellHeight.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        val row = (cell?.first ?: 0).coerceIn(0, rows - 1)
        val column = (cell?.second ?: lastSelectionDragColumn)
            .coerceIn(0, selectionColumns() - 1)

        // The grip moves one endpoint of the selection that already exists.
        // Starting a new gesture here would count a click, and a click after a
        // word selection means "select a word again" — the grip would change
        // the selection's behaviour instead of its size.
        lastSelectionDragTopLeft = topLeft
        lastSelectionDragColumn = column
        lastSelectionDragCell = SelectionCell(row, column)
        selectionGestureActive = true
        val result = NativeSelection.moveBoundary(
            session,
            topLeft,
            row,
            column,
        )
        if (!applySelectionSnapshot(result, chrome = true)) return false
        updateSelectionEdge(y)
        renderThread.invalidateFull()
        return true
    }

    /** The grip's finger lifted; edge auto-scroll has nothing left to follow. */
    fun endSelectionHandleDrag() {
        selectionGestureActive = false
        stopSelectionAutoScroll()
        longPressSelecting = false
    }

    /** The colour a search match is drawn in, from the theme. */
    fun setHighlightColour(argb: Int) {
        renderThread.post(RenderThread.Command.HighlightColour(argb))
        renderThread.invalidate()
    }

    /** The colour the search's other matches are drawn in, from the theme. */
    fun setHighlightAllColour(argb: Int) {
        renderThread.post(RenderThread.Command.HighlightAllColour(argb))
        renderThread.invalidate()
    }

    /** The theme's terminal background, for the surface the grid is drawn on. */
    fun setBackground(argb: Int) {
        renderThread.post(RenderThread.Command.Background(argb))
        renderThread.invalidate()
    }

    /**
     * Enable or disable programming ligatures.
     *
     * The renderer shapes an operator run as one mask when this is on. It is a
     * settings change, not a cell change: the grid stays the same and only the
     * glyphs that cover more than one cell are rebuilt.
     */
    fun setLigatures(enabled: Boolean) {
        renderThread.post(RenderThread.Command.Ligatures(enabled))
        renderThread.invalidateFull()
    }

    /**
     * Ask the render loop to draw the session's next frame.
     *
     * Not [invalidate]: this view draws on its own EGL surface from a thread,
     * so the view system's invalidation repaints nothing. The terminal's output
     * is produced by the session's reader thread — a keystroke's echo, a
     * prompt, a program's flood — and nothing else tells the loop about it.
     * Without this the loop waits for the cursor's blink, which is up to half
     * a second, and the screen trails the child by that much.
     */
    fun invalidateFrame() {
        renderThread.invalidate()
    }

    /** Detach both, without freeing them: the host owns their lifetime. */
    fun detach() {
        ptyHandle = 0
        sessionHandle = 0
        // An in-flight drag belonged to the old session: its press was never
        // released, and a release sent to the next one would be a click the
        // new program never asked for.
        mouseDragging = false
        mouseDragCellX = -1
        mouseDragCellY = -1
        longPressSelecting = false
        selectionGestureActive = false
        stopSelectionAutoScroll()
        selectionSnapshot = null
        engineHasSelection = false
        _selectionBounds.value = null
    }

    /** Tell the loop the font's cell changed, and the grid is laid out again. */
    fun setCell(width: Float, height: Float, fontPx: Float = 0f) {
        cellWidth = width
        cellHeight = height
        this.fontPx = fontPx
        renderThread.post(RenderThread.Command.Cell(width, height, fontPx))
        // A cell is the unit the selection box is measured in. A new cell with
        // the old rows would leave the menu and grips a cell away from the
        // text they belong to.
        if (selectionSnapshot != null) refreshSelectionFromEngine(chromeSelection)
    }

    /**
     * Tell the loop the font's cell changed, mid-pinch.
     *
     * The cell is bigger and the grid keeps its shape: the text grows past
     * the edge of the screen and the engine is not resized. Reflowing on every
     * step of the gesture is what makes a zoom stutter.
     */
    fun setZoomCell(width: Float, height: Float, fontPx: Float = 0f) {
        cellWidth = width
        cellHeight = height
        this.fontPx = fontPx
        renderThread.post(RenderThread.Command.Cell(width, height, fontPx, reflow = false))
        if (selectionSnapshot != null) refreshSelectionFromEngine(chromeSelection)
    }

    /** The sub-line scroll remainder, in pixels — drawing only. */
    private fun postScrollOffset(pixels: Float) {
        renderThread.post(RenderThread.Command.ScrollOffset(pixels))
    }

    // --- keyboard ----------------------------------------------------------
    //
    // A terminal is not a text field, and an IME is built for one: it composes a
    // word, shows candidates, and rewrites what it already sent. A shell has no
    // such thing — once a byte reaches the PTY it is typed. So the connection
    // below sends what was committed and nothing else.

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // --- gestures ------------------------------------------------------------

    /**
     * The user's master switch for mouse reporting, pushed in by the host.
     *
     * Off, gestures always drive the local scrollback and the keyboard,
     * whatever the running program asked for. On, gestures are reported to
     * programs that turned a tracking mode on, and stay local otherwise.
     */
    @Volatile
    var mouseReportingEnabled: Boolean = true

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

    /** The mouse encoder's output, reused. SGR sequences are short. */


    /** Encode one mouse event and write it to the child; true when bytes went out. */
    private fun mouseReporting(): Boolean {
        if (!mouseReportingEnabled) return false
        val session = sessionHandle
        if (session == 0L) return false
        return NativeSession.modes(session) and MODE_MOUSE_TRACKING != 0
    }

    /** The state [mouseReporting] reads, as the host's link tap also asks it. */
    val mouseReportingActive: Boolean
        get() = mouseReporting()

    /**
     * A left-button click: press, then release, at the finger's position.
     *
     * Both travel even though the encoder may answer only one — a tracking
     * mode that reports releases itself (SGR 1006 does) wants the pair.
     */
    private fun sendMouseClick(x: Float, y: Float) {
        sendMouse(
            NativeInput.MOUSE_ACTION_PRESS,
            NativeInput.MOUSE_BUTTON_LEFT,
            x,
            y,
            buttonPressed = false,
        )
        sendMouse(
            NativeInput.MOUSE_ACTION_RELEASE,
            NativeInput.MOUSE_BUTTON_LEFT,
            x,
            y,
            buttonPressed = false,
        )
    }

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
        val started = System.nanoTime()
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
        dev.drosh.terminal.engine.input.InputStats.mouse(
            action, written, System.nanoTime() - started,
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
                val pressed = NativeSelection.press(
                    session,
                    cell.first,
                    cell.second,
                    NativeSelection.BEHAVIOR_WORD,
                )
                if (pressed != null) {
                    longPressSelecting = true
                    selectionGestureActive = true
                    lastSelectionDragCell = SelectionCell(cell.first, cell.second)
                    lastSelectionDragColumn = cell.second
                    // The word the engine selected, and the cells it covers:
                    // the menu hangs on them, and the drag that follows grows
                    // the selection from the anchor.
                    applySelectionSnapshot(pressed, chrome = true)
                    performHapticFeedback(
                        android.view.HapticFeedbackConstants.LONG_PRESS,
                    )
                    // A selection change does not make the engine mark any row
                    // dirty. Without a full rebuild the swapped colours are
                    // painted on no frame at all, and a long press highlights
                    // nothing even though the engine says it selected something.
                    renderThread.invalidateFull()
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
                        lastSelectionDragCell = SelectionCell(cell.first, cell.second)
                        lastSelectionDragColumn = cell.second
                        // The engine's own drag: it extends the selection that
                        // the long press began and answers with the cells it
                        // now covers. A snapshot would only return text and
                        // leave the rendered highlight where it was.
                        val result = NativeSelection.drag(
                            session,
                            cell.first,
                            cell.second,
                            selectionColumns(),
                            cellWidth.toInt().coerceAtLeast(1),
                            height.coerceAtLeast(1),
                        )
                        applySelectionSnapshot(result, chrome = true)
                        updateSelectionEdge(current.y)
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
                flingStarted = true
                startFling(velocityY)
                return true
            }
        },
    )

    private var fling: android.widget.Scroller? = null

    /** The coast's speed factor, exposed through the diagnostics. */
    var flingGear: Float = 0f
        private set

    /**
     * Whether a long press is growing a selection.
     *
     * The drag that follows belongs to the selection, not to the scrollback,
     * and it ends when the finger lifts.
     */
    private var longPressSelecting: Boolean = false

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

    /**
     * Whether the gesture that just ended was a flick.
     *
     * A flick is followed by a coast, and the coast is still the user's
     * finger moving: the picture settles when the coast ends, not when the
     * finger lifts. Settling at the lift would snap the picture back half a
     * row and then move it again.
     */
    private var flingStarted: Boolean = false

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
            val row = cell?.first ?: lastSelectionDragCell?.row ?: 0
            val column = cell?.second
                ?: lastSelectionDragCell?.column
                ?: lastSelectionDragColumn
            if (session != 0L) {
                val cells = NativeSelection.release(
                    session,
                    row.coerceAtLeast(0),
                    column.coerceAtLeast(0),
                    NativeSelection.BEHAVIOR_WORD,
                )
                applySelectionSnapshot(cells, chrome = true)
            }
            longPressSelecting = false
            selectionGestureActive = false
            stopSelectionAutoScroll()
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
            // The flick of the last gesture is over: this one gets its own.
            flingStarted = false
        }

        // The scale detector sees every event; the gesture detector is told only
        // what the pinch did not claim, so two fingers are never a scroll.
        //
        // The detectors come first: the lift is what tells them the gesture is
        // over, and a flick is only known once they have seen it.
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) {
            gestureDetector.onTouchEvent(event)
        }

        // The finger is off the screen: the picture goes back onto a row
        // boundary.
        //
        // While the finger is down the shift follows it exactly, so the
        // scroll is smooth. The moment it lifts, the fraction of a row the
        // picture was left at would cut the last line of the output in half,
        // so it is settled here — and only here.
        //
        // A flick is the exception: the coast continues the drag, and the
        // picture settles when the coast ends. Settling now would snap the
        // picture back half a row before it starts moving again.
        if (!flingStarted &&
            (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL)
        ) {
            // The old Drosh view did not snap here, and it was right: the
            // picture is where the finger left it, and rounding to a row at
            // the lift is a visible jump at the end of every drag.
            finishScroll()
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
                // A tap away from a selection is the platform's way of
                // dismissing it: the user has moved on to the prompt, and
                // leaving two grips and a menu on the screen would keep an
                // old selection alive under the next command.
                if (selectionSnapshot != null) clearSelection()
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
        if (!applySelectionSnapshot(cells, chrome = false)) return false

        selected = NativeSelection.text(session)
        renderThread.invalidateFull()
        return selected.isNotEmpty()
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
                syncSelectionAfterViewportChange()
                renderThread.invalidate()
            }
            return
        }

        if (session == 0L) return

        // The finger's travel goes into the machine, which turns it into whole
        // rows for the engine and a shift for the picture.
        //
        // The engine's row space runs from the oldest row (0) to the live
        // edge, and the picture's top edge sits somewhere inside it — at a
        // whole row, or a fraction of one above it. The machine clamps that
        // position to the ends, which is what keeps the first and the last
        // line of the output exactly where they belong: a shift that grew
        // past the end and was then dropped is a picture that snaps back, and
        // that is what a scroll that pulls itself back looks like.
        val bar = NativeSession.scrollbar(session)
        if (bar == null) return
        val maxRow = (bar.total - bar.len).coerceAtLeast(0)
        val target = scrollMachine.drag(
            distancePx = distancePx,
            rowHeight = spacing,
            engineRow = bar.offset,
            maxRow = maxRow,
        )

        // At the live edge the picture is clamped by the machine already:
        // forcing SCROLL_BOTTOM and resetting the machine here is what made
        // the picture spring back ("yay gibi firlama"). Keep the machine in
        // sync with the engine instead, so the picture stays where the finger
        // left it.
        if (target.pinned && distancePx < 0f) {
            scrollMachine.snapToEngine()
            renderThread.post(RenderThread.Command.ScrollOffset(scrollMachine.shift()))
            renderThread.invalidate()
            lastScrollPinned = true
            return
        }

        // The engine moves by a delta, never to an absolute row: a delta
        // cannot be refused half-way, and the engine's answer is the truth
        // about where the viewport is.
        if (target.delta != 0) {
            NativeSession.scroll(session, NativeSession.SCROLL_DELTA, target.delta)
            // The engine may have refused the move — it clamps at the ends of
            // its scrollable area. The picture goes where the engine is, so
            // the two keep describing the same thing.
            val after = NativeSession.scrollbar(session)
            if (after != null && after.offset < bar.offset + target.delta) {
                scrollMachine.snapToEngine()
            }
        }

        // The picture's shift goes to the render thread, which draws the grid
        // moved down by it — the row above the viewport filling the gap.
        renderThread.post(RenderThread.Command.ScrollOffset(scrollMachine.shift()))
        syncSelectionAfterViewportChange()
        renderThread.invalidate()
        lastScrollPinned = target.pinned
    }

    /**
     * Put the picture back on a row boundary.
     *
     * The shift belongs to a finger that is on the screen. When the finger
     * lifts — or the coast ends — the picture is left a fraction of a row off,
     * and the last line of the output is cut in half. This is the moment to
     * settle: the picture goes onto the row it is nearest.
     */
    /** End a gesture without moving the picture: publish the shift as it is. */
    private fun finishScroll() {
        renderThread.post(RenderThread.Command.ScrollOffset(scrollMachine.shift()))
        renderThread.invalidate()
    }

    private fun settleScroll() {
        val session = sessionHandle
        if (session == 0L) return
        val bar = NativeSession.scrollbar(session) ?: return
        val spacing = cellHeight.coerceAtLeast(1f)
        val maxRow = (bar.total - bar.len).coerceAtLeast(0)
        val target = scrollMachine.settle(spacing, bar.offset, maxRow)
        if (target.delta != 0) {
            NativeSession.scroll(session, NativeSession.SCROLL_DELTA, target.delta)
            // The engine may have refused the move: the picture goes where the
            // engine is, and the shift is zero either way.
            val after = NativeSession.scrollbar(session)
            if (after != null && after.offset < bar.offset + target.delta) {
                scrollMachine.snapToEngine()
            }
        }
        renderThread.post(RenderThread.Command.ScrollOffset(scrollMachine.shift()))
        syncSelectionAfterViewportChange()
        renderThread.invalidate()
    }

    /** Momentum after a flick, through the same pixel path as a drag. */
    private fun startFling(velocityY: Float) {
        val scroller = fling ?: android.widget.Scroller(context).also { fling = it }
        if (!scroller.isFinished) return
        // The flick is a coast, and its length follows how hard the finger
        // moved. A slow flick is a line or two, a fast one crosses a screen —
        // the old view's "gears", without a setting anyone has to choose. The
        // tiers also cap a violent flick: a raw list velocity would throw the
        // output past a hundred rows and make the picture impossible to read.
        // The old Drosh view used a constant 0.25: the detector's velocity
        // is measured for a list, and a terminal that moved at that speed
        // scrolled a screenful per flick. The fling then goes through the same
        // pixel path as a drag, so it carries the sub-line remainder and the
        // ends ease instead of slamming.
        val gear = 0.25f
        flingGear = gear
        // The detector's velocity is positive when the finger moved up, and up
        // means the content keeps moving up — a negative distance for the drag
        // path. The scroller's position therefore has to shrink, which is a
        // negative velocity.
        scroller.fling(
            0, 0,
            0, -(velocityY * gear).toInt(),
            0, 0,
            Int.MIN_VALUE, Int.MAX_VALUE,
        )
        var lastY = 0
        val step = object : Runnable {
            override fun run() {
                if (scroller.isFinished) {
                    // No snap: the coast ends where the friction says it ends.
                    // The engine keeps the sub-line remainder, so the picture
                    // does not jump a row at the end of a flick.
                    finishScroll()
                    return
                }
                scroller.computeScrollOffset()
                val y = scroller.currY
                dragByPixels((y - lastY).toFloat())
                lastY = y
                // The coast ends at the edge: the engine refuses the move and
                // the picture cannot be pulled further.
                if (lastScrollPinned) {
                    finishScroll()
                    return
                }
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

        val started = System.nanoTime()
        val written = NativeInput.encodeWriteKey(
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
        dev.drosh.terminal.engine.input.InputStats.key(
            event.keyCode, written, System.nanoTime() - started,
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
            syncSelectionAfterViewportChange()
        }

        write(bytes)
        renderThread.invalidate()
    }

    /** Write bytes to the child, e.g. a key sequence. */
    fun write(bytes: ByteArray) {
        val pty = ptyHandle
        if (pty == 0L) return
        NativePty.write(pty, bytes)
        // Text from the IME and pastes both come through here, unlike keys and
        // mouse events which are written by the encoder in one crossing.
        dev.drosh.terminal.engine.input.InputStats.paste(bytes.size)
    }

    // --- selection ---------------------------------------------------------
    //
    // Selection lives in the engine: the word boundaries are its semantic
    // rules and the cell widths are its own. What is kept here is the *text*
    // and the bounds, because the clipboard and the handles are the platform's.

    @Volatile
    private var selected: String = ""

    /**
     * The selection's last resolved viewport shape, or null.
     *
     * Updated whenever the engine's answer is read — after a gesture, a grip
     * drag, a scroll, a resize or Select All. The endpoints it holds are the
     * only cells the grips draw on, and its box is the only rectangle the menu
     * hangs on.
     */
    @Volatile
    private var selectionSnapshot: SelectionSnapshot? = null

    /**
     * The selection's rectangle in this pane's own pixels, updated when the
     * engine's answer is read.
     */
    private val _selectionBounds = MutableStateFlow<android.graphics.Rect?>(null)
    val selectionBoundsFlow: StateFlow<android.graphics.Rect?> = _selectionBounds.asStateFlow()

    /**
     * Whether this selection owns the menu and the draggable grips.
     *
     * A click selection is ordinary terminal text selection: the engine
     * inverts the cells and nothing floats over them. A long press is the
     * other kind — the same text, plus a menu and two grips. Keeping the two
     * apart is why a double tap no longer litters the screen with handles.
     */
    private var chromeSelection: Boolean = false

    /**
     * The selection gesture is still alive.
     *
     * Edge auto-scroll may only keep feeding the engine while a finger is down.
     * A tick after the finger lifts would grow the selection on its own.
     */
    private var selectionGestureActive: Boolean = false

    /**
     * Whether the engine holds a selection, whether or not it is visible.
     *
     * A selection can scroll entirely off the screen and come back. Hiding the
     * chrome when it leaves must not forget that it exists, or scrolling back
     * would show the highlight with no way to copy or dismiss it.
     */
    private var engineHasSelection: Boolean = false

    /** The finger's last cell, for a release that lands outside the grid. */
    private var lastSelectionDragCell: SelectionCell? = null

    /** The finger's column, for an auto-scroll tick at a viewport edge. */
    private var lastSelectionDragColumn: Int = 0

    /** Which grip the current finger drag is moving. */
    private var lastSelectionDragTopLeft: Boolean = true

    /** -1 while dragging near the top edge, +1 near the bottom, 0 otherwise. */
    private var selectionAutoScrollEdge: Int = 0
    private var selectionAutoScrollRunning: Boolean = false

    /** The selected text, or "". */
    fun selectedText(): String {
        // The selection lives in the engine, and the engine is the only one
        // that knows what it covers. There is deliberately no cached fallback:
        // a stale string would let a copy put text on the clipboard that the
        // highlight on screen no longer agrees with.
        val session = sessionHandle
        if (session == 0L) return ""
        return SelectionText.normalize(NativeSelection.text(session))
    }

    /**
     * The cell the toolbar's word and line actions should grow from.
     *
     * The moving end is the user's last intent, and its visible cell is the
     * one the action should apply to. A selection made entirely by Select All
     * has no endpoint on screen; its first visible cell is the honest fallback.
     */
    fun selectionAnchorCell(): Pair<Int, Int>? {
        val snapshot = selectionSnapshot ?: return null
        val fallback = SelectionCell(snapshot.firstRow, snapshot.firstColumn)
        return snapshot.actionAnchor(fallback)?.let { it.row to it.column }
    }

    /** The top grip's viewport cell, or null when that endpoint is off screen. */
    fun selectionTopLeftCell(): Pair<Int, Int>? =
        selectionSnapshot?.topLeftHandle?.let { it.row to it.column }

    /** The bottom grip's viewport cell, or null when that endpoint is off screen. */
    fun selectionBottomRightCell(): Pair<Int, Int>? =
        selectionSnapshot?.bottomRightHandle?.let { it.row to it.column }

    /** The sub-row shift the picture is drawn at, in pixels. */
    fun selectionScrollOffsetPx(): Float = renderThread.scrollOffsetPx

    /** Select the word under a cell; returns true when something was selected. */
    fun selectWord(row: Int, column: Int, chrome: Boolean = false): Boolean =
        applySelection(row, column, word = true, chrome = chrome)

    /** Select the line under a cell. */
    fun selectLine(row: Int, column: Int, chrome: Boolean = false): Boolean =
        applySelection(row, column, word = false, chrome = chrome)

    /** Select everything the engine holds. */
    fun selectAll(): Boolean {
        val session = sessionHandle
        if (session == 0L) return false
        val text = NativeSelection.selectAll(session)
        selected = text
        val visible = refreshSelectionFromEngine(chrome = true)
        renderThread.invalidateFull()
        return visible && text.isNotEmpty()
    }

    /** Clear the selection, in the engine as well as in the chrome. */
    fun clearSelection() {
        val session = sessionHandle
        if (session != 0L) NativeSelection.clear(session)
        selected = ""
        selectionSnapshot = null
        engineHasSelection = false
        _selectionBounds.value = null
        longPressSelecting = false
        selectionGestureActive = false
        stopSelectionAutoScroll()
        chromeSelection = false
        renderThread.invalidateFull()
    }

    /** Copy the selection to the clipboard; true when there was something. */
    fun copySelection(): Boolean {
        val text = selectedText()
        if (text.isEmpty()) return false
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            ?: return false
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("terminal", text),
        )
        return true
    }

    /**
     * Read the engine's selection back and publish it.
     *
     * Returns false when the engine holds no selection, or none of it is on
     * screen. The stored snapshot is left alone in that case when a gesture is
     * still running: a drag that briefly maps outside the grid has not
     * cancelled the selection the user is making.
     */
    private fun refreshSelectionFromEngine(chrome: Boolean): Boolean {
        val session = sessionHandle
        if (session == 0L) return false
        return applySelectionSnapshot(NativeSelection.viewport(session), chrome)
    }

    /**
     * Publish the engine's eight-value selection answer.
     *
     * A null or empty answer is not treated as "clear": the caller decides
     * when a selection ends. A drag can be refused for a frame while the finger
     * is on the screen, and clearing the chrome then would make the menu blink
     * under the user's hand.
     */
    private fun applySelectionSnapshot(values: IntArray?, chrome: Boolean): Boolean {
        val snapshot = SelectionSnapshot.from(values) ?: return false
        selectionSnapshot = snapshot
        engineHasSelection = true
        _selectionBounds.value = snapshot.bounds(
            cellWidth,
            cellHeight,
            renderThread.scrollOffsetPx,
        )
        if (chrome) {
            chromeSelection = true
        } else if (chromeSelection) {
            chromeSelection = false
        }
        return true
    }

    private fun applySelection(
        row: Int,
        column: Int,
        word: Boolean,
        chrome: Boolean = true,
    ): Boolean {
        val session = sessionHandle
        if (session == 0L) return false
        selected = if (word) {
            NativeSelection.selectWord(session, row, column)
        } else {
            NativeSelection.selectLine(session, row, column)
        }
        if (selected.isEmpty()) return false
        refreshSelectionFromEngine(chrome)
        renderThread.invalidateFull()
        return true
    }

    /** The number of columns the gesture geometry describes. */
    private fun selectionColumns(): Int =
        (width / cellWidth.coerceAtLeast(1f)).toInt().coerceAtLeast(1)

    /**
     * Track whether the finger is close enough to an edge to scroll.
     *
     * The band is a finger's height, not a few pixels: a selection that needs
     * to grow past the screen should not require the finger to be exactly on
     * the last row while the terminal scrolls under it.
     */
    private fun updateSelectionEdge(y: Float) {
        val band = SELECTION_EDGE_BAND_DP * resources.displayMetrics.density
        val edge = SelectionEdges.at(y, height.toFloat(), band)
        if (edge == SelectionEdges.NONE) {
            stopSelectionAutoScroll()
        } else {
            startSelectionAutoScroll(edge)
        }
    }

    private fun startSelectionAutoScroll(edge: Int) {
        selectionAutoScrollEdge = edge
        if (selectionAutoScrollRunning) return
        selectionAutoScrollRunning = true
        postOnAnimationDelayed(selectionAutoScrollTicker, SELECTION_AUTOSCROLL_FIRST_MS)
    }

    private fun stopSelectionAutoScroll() {
        selectionAutoScrollEdge = 0
        if (!selectionAutoScrollRunning) return
        selectionAutoScrollRunning = false
        removeCallbacks(selectionAutoScrollTicker)
    }

    /**
     * One edge tick: scroll a row, then move the selection's end with it.
     *
     * The scroll has to happen first. The engine resolves the tick's cell
     * against the viewport it can see now, so feeding the selection before the
     * viewport moved would extend it into the row that is about to leave.
     */
    private val selectionAutoScrollTicker = object : Runnable {
        override fun run() {
            val edge = selectionAutoScrollEdge
            if (edge == 0 || !selectionGestureActive) {
                selectionAutoScrollRunning = false
                return
            }
            if (!scrollSelectionRow(edge)) {
                selectionAutoScrollEdge = 0
                selectionAutoScrollRunning = false
                return
            }
            applySelectionAtEdge(edge)
            postOnAnimationDelayed(this, SELECTION_AUTOSCROLL_INTERVAL_MS)
        }
    }

    /** Move the viewport one row into the history (negative) or live edge. */
    private fun scrollSelectionRow(edge: Int): Boolean {
        val session = sessionHandle
        if (session == 0L) return false
        val before = NativeSession.scrollbar(session) ?: return false
        val lastRow = (before.total - before.len).coerceAtLeast(0)
        if (edge < 0 && before.offset <= 0) return false
        if (edge > 0 && before.offset >= lastRow) return false

        NativeSession.scroll(session, NativeSession.SCROLL_DELTA, edge)
        val after = NativeSession.scrollbar(session) ?: return false
        // The engine clamps at both ends. A tick it refused is the edge the
        // user has reached, and the loop has nothing left to scroll.
        if (after.offset == before.offset) return false

        scrollMachine.reset()
        renderThread.post(RenderThread.Command.ScrollOffset(0f))
        renderThread.invalidate()
        return true
    }

    /** Extend the selection to the viewport row the finger is now over. */
    private fun applySelectionAtEdge(edge: Int) {
        val session = sessionHandle
        if (session == 0L) return
        val rows = (height / cellHeight.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        val row = if (edge < 0) 0 else rows - 1
        val column = lastSelectionDragColumn.coerceIn(0, selectionColumns() - 1)
        lastSelectionDragCell = SelectionCell(row, column)

        val values = if (longPressSelecting) {
            NativeSelection.drag(
                session,
                row,
                column,
                selectionColumns(),
                cellWidth.toInt().coerceAtLeast(1),
                height.coerceAtLeast(1),
            )
        } else {
            NativeSelection.moveBoundary(session, lastSelectionDragTopLeft, row, column)
        }
        applySelectionSnapshot(values, chrome = true)
        renderThread.invalidateFull()
    }

    /**
     * Keep the chrome's selection in step with a moved viewport.
     *
     * Scrolling changes which rows the selection's grid references resolve to;
     * the highlight moves with the text because the engine draws it, but the
     * menu and the grips are placed from viewport rows and would stay behind.
     * Re-reading the engine is what makes them follow.
     */
    private fun syncSelectionAfterViewportChange() {
        if (!engineHasSelection) return
        // A drag is mid-flight, and a refusal for one frame is not a cleared
        // selection. The drag's own answer is the next word.
        if (selectionGestureActive) return
        if (refreshSelectionFromEngine(chromeSelection)) return
        if (selectionSnapshot == null) return
        // The whole selection has scrolled out of view. The engine keeps it,
        // so the chrome is hidden rather than forgotten; the next scroll that
        // brings it back publishes it again with its grips and menu.
        selectionSnapshot = null
        _selectionBounds.value = null
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
    /**
     * The next frame, written into the buffer the render loop lends.
     *
     * The engine writes the frame straight into the buffer, so a frame does
     * not become a Java array on the way. The buffer is the loop's own and is
     * reused from frame to frame.
     *
     * @return the bytes written, 0 when there was no frame, and -1 when the
     *   buffer was too small.
     */
    private fun frameInto(buffer: java.nio.ByteBuffer): Int {
        val session = sessionHandle
        if (session == 0L) return 0
        return NativeSession.frameInto(session, buffer)
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

        /**
         * How close to an edge a selection drag must be to auto-scroll.
         *
         * A finger is wider than a row, and the user is looking at the text
         * under it, not at the screen's edge. A 56 dp band is roughly the last
         * two rows on a phone — enough to be easy to reach, small enough that
         * ordinary dragging does not scroll by accident.
         */
        const val SELECTION_EDGE_BAND_DP = 56f

        /** Delay before the first auto-scroll row, so a tap near an edge does not scroll. */
        const val SELECTION_AUTOSCROLL_FIRST_MS = 80L

        /** Time between auto-scroll rows: about 16 rows a second. */
        const val SELECTION_AUTOSCROLL_INTERVAL_MS = 60L

    }
}
