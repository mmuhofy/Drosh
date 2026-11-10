package dev.drosh.terminal.engine.surface

import android.content.Context
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.drosh.terminal.engine.native.NativeInput
import dev.drosh.terminal.engine.native.NativePty
import dev.drosh.terminal.engine.native.NativeSelection
import dev.drosh.terminal.engine.native.NativeSession
import dev.drosh.terminal.engine.protocol.Frame
import dev.drosh.terminal.engine.render.RenderThread
import java.util.concurrent.atomic.AtomicBoolean

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
 * ## The two threads it owns
 *
 * - **The render loop**, from [RenderThread]: GL, the atlas, the frame.
 * - **The PTY reader**, a plain thread that reads the child's output, feeds it
 *   to the parser and asks for a frame.
 *
 * They are separate on purpose. A blocking read on the render thread would
 * freeze the picture whenever the child was quiet; a draw on the reader thread
 * would put GL on a thread that did not create the context.
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

    /** The font's own size, in pixels — what the glyphs are rasterised at. */
    @Volatile
    private var fontPx = 0f

    /** The reader thread, while a child is attached. */
    private var reader: Thread? = null

    private val reading = AtomicBoolean(false)

    /** The read buffer, reused. */
    private val readBuffer = ByteArray(64 * 1024)

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

    /** Bytes the child has produced since the session started. */
    @Volatile
    var bytesRead: Long = 0
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
        stopReading()
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
        startReading()
    }

    /** Detach both, without freeing them: the host owns their lifetime. */
    fun detach() {
        stopReading()
        ptyHandle = 0
        sessionHandle = 0
    }

    /**
     * Tell the loop the font's cell changed.
     *
     * `fontPx` is the size glyphs are rasterised at. When the caller does not
     * know it — a cell that only moved — the last known one is reused rather
     * than zeroed, which would rasterise every glyph at nothing.
     */
    fun setCell(width: Float, height: Float, fontPx: Float = 0f) {
        cellWidth = width
        cellHeight = height
        if (fontPx > 0f) {
            this.fontPx = fontPx
        }
        renderThread.post(RenderThread.Command.Cell(width, height, this.fontPx))
    }

    /** A live pinch. The grid does not move; the glyphs do. */
    fun pinch(scale: Float, focusX: Float, focusY: Float) {
        renderThread.post(RenderThread.Command.Pinch(scale, focusX, focusY))
    }

    /** The sub-line scroll remainder, in pixels. */
    fun scrollOffset(pixels: Float) {
        renderThread.post(RenderThread.Command.ScrollOffset(pixels))
    }

    /**
     * Pixel-by-pixel scroll, the model the old engine settled on: the
     * sub-line remainder lives in this one place instead of two — the row
     * the engine holds is the row whose fractional part is visible.
     * Whole rows go to the engine; the remainder shifts the picture.
     *
     * ## The ends
     *
     * The engine clamps the viewport at the ends of the history. If the
     * remainder kept accumulating there, the picture would drift away from a
     * grid that has stopped moving — which reads as the content sliding under
     * the finger and never coming back. So the remainder is cleared when the
     * engine refused the whole of the movement it was asked for.
     */
    private fun scrollPixels(dy: Float) {
        val handle = sessionHandle
        val spacing = cellHeight.coerceAtLeast(1f)

        accumulatorPx += dy
        val whole = (accumulatorPx / spacing).toInt()

        if (whole != 0) {
            val moved = if (handle != 0L) {
                // Finger down → content down → the viewport moves back through
                // the history, which the engine takes as a negative row delta.
                // `0` means the viewport really moved; `1` means it was clamped
                // at an end of the history.
                NativeSession.scroll(handle, NativeSession.SCROLL_DELTA, -whole) == 0
            } else {
                false
            }
            if (moved) {
                accumulatorPx -= whole * spacing
            } else {
                // The viewport did not move: it is at an end of the history.
                // Nothing may be left over, or the picture keeps drifting.
                accumulatorPx = 0f
                scrollOffset(0f)
                return
            }
        }

        // A full-screen program owns the scroll; the fraction is not its
        // business and the engine would not understand it.
        if (handle != 0L && NativeSession.alternateScreen(handle)) {
            return
        }

        scrollOffset(accumulatorPx)
    }

    /** Scroll by rows; negative is up. */
    fun scroll(rows: Int) {
        val session = sessionHandle
        if (session == 0L) return
        NativeSession.scroll(session, NativeSession.SCROLL_DELTA, rows)
        renderThread.invalidate()
    }

    /** The text the IME is composing, as it was last sent — the diff basis. */
    private var composing = ""

    /** Where the sub-line scroll remainder lives — whole rows go to the engine. */
    private var accumulatorPx = 0f

    // The pinch, ported from the vendored engine: a dead zone in log-scale
    // before any size changes, a clamped step per event, one reflow when the
    // fingers lift — never during the gesture.
    private var zoomBaseSp = 0f
    private var zoomScaleFactor = 1f
    private var zoomPassedDeadZone = false

    /** The size the pinch has reached, live. 0 when no pinch is in progress. */
    @Volatile
    var zoomSizeSp = 0f
        private set

    /** Told the live size while a pinch moves, and 0 when it ends. */
    var zoomListener: (sp: Float) -> Unit = {}

    /**
     * Told the size the pinch settled on, once, when the fingers lift.
     *
     * The surface does not apply it. The size is the app's — one setting, one
     * owner — and the cell it implies comes back through [setCell] on the next
     * recomposition. Applying it here as well was the bug that made a pinch
     * last exactly one frame: the host's remembered cell overwrote it.
     */
    var zoomCommitListener: (sp: Float) -> Unit = {}

    private val recognizer = dev.drosh.terminal.engine.view.GestureAndScaleRecognizer(
        context,
        object : dev.drosh.terminal.engine.view.GestureAndScaleRecognizer.Listener {
            override fun onSingleTapUp(event: MotionEvent): Boolean {
                if (!hasFocus()) {
                    requestFocus()
                    requestKeyboard()
                }
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean = false
            override fun onLongPress(event: MotionEvent) {}

            override fun onScroll(event: MotionEvent, dx: Float, dy: Float): Boolean {
                // finger down → content down → the history; the engine takes
                // negative rows as backwards through the scrollback.
                scrollPixels(-dy)
                return true
            }

            override fun onFling(
                event: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean = false

            override fun onScaleBegin() {
                val density = resources.displayMetrics.scaledDensity.coerceAtLeast(1f)
                zoomBaseSp = fontPx / density
                if (zoomBaseSp <= 0f) zoomBaseSp = 14f
                zoomScaleFactor = 1f
                zoomPassedDeadZone = false
            }

            override fun onScale(focusX: Float, focusY: Float, scale: Float): Boolean {
                val step = scale.coerceIn(
                    1f / dev.drosh.domain.terminal.TerminalZoom.MAX_STEP_FACTOR,
                    dev.drosh.domain.terminal.TerminalZoom.MAX_STEP_FACTOR,
                )
                zoomScaleFactor *= step

                if (!zoomPassedDeadZone) {
                    if (Math.abs(Math.log(zoomScaleFactor.toDouble())) < dev.drosh.domain.terminal.TerminalZoom.DEAD_ZONE) {
                        return true
                    }
                    zoomPassedDeadZone = true
                }

                val target = (zoomBaseSp * zoomScaleFactor)
                    .coerceIn(
                        dev.drosh.domain.terminal.TerminalZoom.MIN_SP,
                        dev.drosh.domain.terminal.TerminalZoom.MAX_SP,
                    )
                val pinch = target / zoomBaseSp
                renderThread.post(RenderThread.Command.Pinch(pinch, focusX, focusY))
                zoomSizeSp = target
                zoomListener(target)
                return true
            }

            override fun onScaleEnd() {
                if (zoomPassedDeadZone) {
                    val target = (zoomBaseSp * zoomScaleFactor)
                        .coerceIn(
                            dev.drosh.domain.terminal.TerminalZoom.MIN_SP,
                            dev.drosh.domain.terminal.TerminalZoom.MAX_SP,
                        )
                    // Hand the size to the app and let it come back as a cell.
                    // The surface does not measure: the host's font stack does,
                    // and it measures the size the setting now holds.
                    zoomCommitListener(target)
                } else {
                    // Back at the origin: the picture eases back too.
                    renderThread.post(RenderThread.Command.Pinch(1f, 0f, 0f))
                }
                zoomScaleFactor = 1f
                zoomPassedDeadZone = false
                zoomSizeSp = 0f
                zoomListener(0f)
            }
        },
    )

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

    /**
     * A tap takes the keyboard.
     *
     * A terminal is read far more than it is typed into, but a tap on it is how
     * the user says "I am working here", and that is the moment the keyboard
     * belongs on screen. Not consumed: the terminal's own touch handling —
     * scroll, selection — still sees it.
     */
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP && !hasFocus()) {
            requestFocus()
            requestKeyboard()
        }
        return super.onTouchEvent(event)
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
                val raw = text?.toString().orEmpty()
                // The composing text this commit carries already went to the shell
                // as it was typed; sending it again would double every character.
                if (composing.isNotEmpty() && raw.startsWith(composing)) {
                    val rest = raw.removePrefix(composing).replace('\n', '\r')
                    if (rest.isNotEmpty()) {
                        send(rest.toByteArray(Charsets.UTF_8))
                    }
                } else {
                    // No prefix in common with what the shell holds — either the
                    // IME discarded the composition, or it never committed one:
                    // send everything, newline as CR like a terminal expects.
                    send(raw.replace('\n', '\r').toByteArray(Charsets.UTF_8))
                }
                composing = ""
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val next = text?.toString().orEmpty()
                val common = composing.commonPrefixWith(next).length
                // Backward deletion first — the shell cannot take a character back,
                // so the tail that the IME erased is erased there too.
                val removed = composing.substring(common)
                repeat(removed.length) { send(byteArrayOf(DEL)) }
                val added = next.substring(common)
                if (added.isNotEmpty()) {
                    send(added.toByteArray(Charsets.UTF_8))
                }
                composing = next
                return true
            }

            override fun finishComposingText(): Boolean {
                // Every byte for the composition has already been sent; this is
                // only the IME telling us the underline is gone.
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { send(byteArrayOf(DEL)) }
                // The composing buffer loses the same tail the shell lost.
                composing = composing.dropLast(beforeLength)
                return true
            }

            override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    // The IME routes the key here; the view's own onKeyDown is
                    // the other path, and both firing would type everything
                    // twice. A key event that came from the IME is marked, and
                    // onKeyDown skips it.
                    imeKeyInFlight = true
                    try {
                        sendKey(event)
                    } finally {
                        imeKeyInFlight = false
                    }
                    return true
                }
                return true
            }
        }
    }

    /**
     * True while a key is being delivered through the input connection.
     *
     * A key event reaches this view twice when the IME sends it: once through
     * `InputConnection.sendKeyEvent` and once through the view's own dispatch.
     * Only one of them may encode it.
     */
    private var imeKeyInFlight = false

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (imeKeyInFlight) {
            return true
        }
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

    /**
     * Read the child's output until it goes away.
     *
     * A blocking read on the render thread would freeze the picture whenever
     * the child was quiet, so it is its own thread — and it does nothing but
     * move bytes: the parser is the engine's, and the frame is the renderer's.
     */
    private fun startReading() {
        if (!reading.compareAndSet(false, true)) return
        val pty = ptyHandle
        if (pty == 0L) {
            reading.set(false)
            return
        }

        reader = Thread({ readLoop(pty) }, "drosh-pty-reader").apply { isDaemon = true }.also { it.start() }
    }

    private fun readLoop(pty: Long) {
        while (reading.get()) {
                val read = NativePty.read(pty, readBuffer)
                when {
                    read > 0 -> {
                        bytesRead += read
                        // Straight into the parser: the engine owns the grid,
                        // and a copy on this side would be a second opinion.
                        val session = sessionHandle
                        if (session != 0L) {
                            NativeSession.write(session, readBuffer, read)
                        }
                        renderThread.invalidate()
                    }

                    read == 0 -> {
                        // Nothing right now. The child's exit is the signal that
                        // this loop is done, not an empty read.
                        val status = NativePty.tryWait(pty)
                        if (status >= 0) {
                            reading.set(false)
                            post { onExit?.invoke(status) }
                            return
                        }
                        Thread.sleep(IDLE_SLEEP_MS)
                    }

                    else -> {
                        reading.set(false)
                        return
                    }
                }
            }
    }

    private fun stopReading() {
        reading.set(false)
        reader?.let { runCatching { it.join(STOP_TIMEOUT_MS) } }
        reader = null
    }

    private companion object {
        /** DEL (0x7f), what a terminal expects for backspace. */
        const val DEL: Byte = 0x7f

        /** How long the reader waits when the child has nothing to say. */
        const val IDLE_SLEEP_MS = 8L

        /** How long [stopReading] waits for the reader to notice. */
        const val STOP_TIMEOUT_MS = 500L
    }
}
