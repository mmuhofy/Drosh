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

    /** Where keyboard bytes go. Set by the host when a session starts. */
    @Volatile
    private var ptyHandle: Long = 0

    /** The session handle, for frames and keys. */
    @Volatile
    private var sessionHandle: Long = 0

    /** The cell size the font measured, in pixels. */
    @Volatile
    private var cellWidth = 1f

    @Volatile
    private var cellHeight = 1f

    /** The key encoder's output, reused. */
    private val keyBuffer = ByteArray(256)

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
        renderThread.onFrameShape = { columns: Int, rows: Int, cells: Int, stepX: Float, stepY: Float ->
            lastFrameShape = "${columns}x$rows cells=$cells " +
                "step=${"%.1f".format(stepX)}x${"%.1f".format(stepY)}"
        }
        holder.addCallback(this)
        // The surface draws its own pixels; the platform must not clear it to
        // the window background between frames.
        holder.setFormat(android.graphics.PixelFormat.OPAQUE)
        setZOrderOnTop(false)

        renderThread.frameSource = { frame() }
        renderThread.onGridChange = { columns, rows ->
            // Three things have to agree about the shape: the grid drawn, the
            // engine's own terminal, and the process. The engine's terminal is
            // the one that decides where a line wraps, so a resize that missed
            // it would lay the shell's output out for a shape the screen does
            // not have.
            val session = sessionHandle
            if (session != 0L) {
                NativeSession.resize(session, columns, rows, cellWidth.toInt(), cellHeight.toInt())
            }
            val pty = ptyHandle
            if (pty != 0L) {
                NativePty.resize(pty, columns, rows, cellWidth.toInt(), cellHeight.toInt())
            }
            onGridChanged?.invoke(columns, rows)
        }
    }

    // --- lifecycle ----------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
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
        // The cell first, the surface second: a surface laid out against the
        // default one-pixel cell is a grid of a thousand columns.
        renderThread.post(RenderThread.Command.Cell(cellWidthPx, cellHeightPx, 0f))
        renderThread.invalidate()
    }

    /** Detach both, without freeing them: the host owns their lifetime. */
    fun detach() {
        ptyHandle = 0
        sessionHandle = 0
    }

    /** Tell the loop the font's cell changed. */
    fun setCell(width: Float, height: Float, fontPx: Float = 0f) {
        cellWidth = width
        cellHeight = height
        renderThread.post(RenderThread.Command.Cell(width, height, fontPx))
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

    /** Told the live size while a pinch moves, and 0 when it ends. */
    var zoomListener: (sp: Float) -> Unit = {}

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
                // The picture follows the fingers; the grid does not move.
                renderThread.post(
                    RenderThread.Command.Pinch(
                        frame.renderScale,
                        detector.focusX,
                        detector.focusY,
                    ),
                )
                zoomSizeSp = frame.targetSp
                zoomListener(frame.targetSp)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                val settled = pinchMachine.end()
                // The live picture is always returned to scale 1 here. The new
                // size arrives as a cell through `setCell`, which is the real
                // zoom; leaving the projection scaled would keep the whole grid
                // magnified on top of it, and it would never come back.
                renderThread.post(RenderThread.Command.Pinch(1f, 0f, 0f))
                if (settled != null) {
                    zoomCommitListener(pinchMachine.quantise(settled, TerminalZoom.STEP_SP))
                }
                zoomSizeSp = 0f
                zoomListener(0f)
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true

            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (!hasFocus()) {
                    requestFocus()
                    requestKeyboard()
                }
                return true
            }

            override fun onScroll(
                first: MotionEvent?,
                current: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
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
                startFling(velocityY)
                return true
            }
        },
    )

    private var fling: android.widget.Scroller? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // The scale detector sees every event; the gesture detector is told only
        // what the pinch did not claim, so two fingers are never a scroll.
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) {
            gestureDetector.onTouchEvent(event)
        }
        return true
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

        // The engine's history is both the limit the machine clamps against and
        // the row space its own scroll works in: row 0 is the top of the
        // scrollback, and the live edge is `historyRows`.
        val scrollbar = NativeSession.scrollbar(session) ?: return
        scrollMachine.setHistory(scrollbar.historyRows)

        if (scrollMachine.drag(distancePx, spacing)) {
            // Whole rows go to the engine as an absolute row; the remainder
            // shifts the projection between rows.
            NativeSession.scroll(
                session,
                NativeSession.SCROLL_ROW,
                scrollbar.historyRows + scrollMachine.topRow,
            )
            postScrollOffset(scrollMachine.offsetPx)
            renderThread.invalidate()
        }
    }

    /** Momentum after a flick, through the same pixel path as a drag. */
    private fun startFling(velocityY: Float) {
        val scroller = fling ?: android.widget.Scroller(context).also { fling = it }
        if (!scroller.isFinished) return
        // The detector's velocities are tuned for lists; a terminal that moved
        // at that speed scrolled a screenful per flick. The vendored view used
        // a quarter of it, and so does this.
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
                repeat(beforeLength) { send(byteArrayOf(DEL)) }
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
     * Encode one key through the engine.
     *
     * The engine owns the rules: `DECCKM`'s `ESC O A` versus `ESC [ A`, the
     * kitty protocol's form, whether a modifier is a parameter or a prefix. The
     * Android key code is the only thing translated here.
     */
    private fun sendKey(event: android.view.KeyEvent) {
        val session = sessionHandle
        if (session == 0L) return

        val text = event.unicodeChar
            .takeIf { it != 0 }
            ?.let { String(Character.toChars(it)) }
            .orEmpty()

        val written = NativeInput.encode(
            handle = session,
            keyCode = event.keyCode,
            metaState = event.metaState,
            action = when (event.action) {
                android.view.KeyEvent.ACTION_DOWN -> NativeInput.ACTION_PRESS
                android.view.KeyEvent.ACTION_UP -> NativeInput.ACTION_RELEASE
                else -> NativeInput.ACTION_PRESS
            },
            utf8 = text,
            out = keyBuffer,
        )
        if (written > 0) {
            write(keyBuffer.copyOf(written))
        }
    }

    /** Send bytes from the IME straight to the child. */
    private fun send(bytes: ByteArray) {
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

    /** The selected text, or "". */
    fun selectedText(): String = selected

    /** True when something is selected. */
    fun hasSelection(): Boolean = selected.isNotEmpty()

    /** The selection's bounds in pixels, or null. */
    fun selectionBounds(): android.graphics.Rect? = selectionRect

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

        /** Bit 0 of the engine's mode bits is mouse tracking. */
        const val MODE_MOUSE_TRACKING = 1

        /** How much of the detector's fling velocity a terminal uses. */
        const val FLING_SCALE = 0.25f

    }
}
