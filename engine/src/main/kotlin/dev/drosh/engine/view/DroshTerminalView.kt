package dev.drosh.engine.view

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Base64
import android.util.Log
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import dev.drosh.engine.bench.FrameStats
import dev.drosh.engine.core.EngineSession
import dev.drosh.engine.core.NativeCore
import dev.drosh.engine.input.AndroidKeys
import dev.drosh.engine.input.Encoder
import dev.drosh.engine.input.Mouse
import dev.drosh.engine.input.Selection
import dev.drosh.engine.input.SelectionText
import dev.drosh.engine.input.TerminalInputView
import dev.drosh.engine.render.CellMetrics
import dev.drosh.engine.render.FrameLoop
import dev.drosh.engine.render.GlyphAtlas
import dev.drosh.engine.render.Palette
import dev.drosh.engine.render.TerminalRenderer
import kotlin.math.abs

/**
 * The terminal view: one [EngineSession] rendered through the GL renderer, with
 * the input vocabulary (touch gestures, IME, hardware keys, real pointers,
 * selection, clipboard) attached.
 *
 * It is a [FrameLayout] rather than the GL surface itself, because the IME
 * needs a focused text editor in the hierarchy and a `SurfaceView` cannot host
 * children — a 1×1 [TerminalInputView] rides along for that.
 *
 * The host owns the lifecycle: call [onHostResume] / [onHostPause] from the
 * activity or the Compose lifecycle, and [release] when done. Everything the
 * view does not handle itself (title, bell, exit, shell marks, …) surfaces as
 * event records through [Listener.onSessionEvent].
 */
class DroshTerminalView(context: Context) : FrameLayout(context) {

    /** What the host learns from the session. All callbacks arrive on the UI thread. */
    interface Listener {
        /** `OSC 0/2`: the shell changed the window title. */
        fun onTitle(title: String) {}

        /** `BEL`. */
        fun onBell() {}

        /** The child process exited; `128 + signal` when it was signalled. */
        fun onSessionExited(code: Int) {}

        /**
         * Every event record the view does not handle itself — today the shell
         * marks (`shell_mark:*`), tomorrow whatever the block engine needs.
         */
        fun onSessionEvent(record: String) {}

        /** The selection appeared or disappeared (the host may show Copy/Paste). */
        fun onSelectionChanged(hasSelection: Boolean) {}
    }

    var listener: Listener? = null

    /** Where user input goes for a bridged session (the host's transport). */
    var inputSink: ((ByteArray) -> Unit)?
        get() = session.inputSink
        set(value) {
            session.inputSink = value
        }

    /** The session behind the view; the host may [EngineSession.configure] it. */
    val session = EngineSession()

    private lateinit var surface: GLSurfaceView
    private lateinit var input: TerminalInputView
    private lateinit var renderer: TerminalRenderer

    /** Cell geometry from the bundled font, filled in by [loadFont]. */
    private lateinit var metrics: CellMetrics

    /** Pixel size the glyphs are rasterised at. */
    private var fontPx = 0f

    private var scrollAccumulator = 0f
    private var exited = false

    /** The current selection; long-press selects a word, dragging extends it. */
    private val selection = Selection()
    private var draggingSelection = false

    /** Last cell a real pointer reported, so motion is sent per cell, not per pixel. */
    private var lastMouseColumn = -1
    private var lastMouseLine = -1

    /** The armed Ctrl of an external extra-keys row, honoured by the IME path. */
    @Volatile
    var stickyCtrl: Boolean = false

    /** Frame timings; [frameStatsSummary] exposes them for a debug surface. */
    private val stats = FrameStats()

    /**
     * The frame pump: the engine's wakeup flag is polled once per vsync instead
     * of on a timer, so output reaches the screen on the next refresh.
     */
    private val frameLoop = FrameLoop(
        onWakeup = {
            reportGlErrors()
            surface.requestRender()
            drainEvents()
        },
        onBlink = {
            reportGlErrors()
            renderer.cursorVisible = !renderer.cursorVisible
            surface.requestRender()
            drainEvents()
        },
    )

    /** The IME's focus target; the host must keep it in the hierarchy (1×1 px). */
    val imeTarget: View get() = input

    init {
        if (!loadFont()) {
            Log.e(TAG, "the bundled font failed to load; the terminal cannot start")
            return
        }

        renderer = TerminalRenderer(session, GlyphAtlas(), metrics, fontPx, selection, stats)
        renderer.onGridChanged = { columns, rows ->
            // The renderer reports from the GL thread; the session is only ever
            // touched from the UI thread.
            post {
                session.resize(columns, rows, metrics.width.toInt(), metrics.height.toInt())
                surface.requestRender()
            }
        }

        surface = GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            setOnTouchListener(gestureListener())
            setOnGenericMotionListener { _, event -> handleScrollWheel(event) }
        }
        addView(
            surface,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        input = TerminalInputView(context)
        input.onText = { text ->
            // A soft keyboard commits text; the row's armed Ctrl turns the first
            // character into a control code, which is how a phone sends Ctrl+C.
            session.write(Encoder.text(text, Encoder.Mods(ctrl = stickyCtrl)))
            stickyCtrl = false
        }
        input.onBackspace = { session.write(byteArrayOf(0x7f)) }
        input.onKey = { keyCode, event -> handleHardwareKey(keyCode, event) }
        addView(
            input,
            // One pixel: it only exists so the soft keyboard has a focused text
            // editor to talk to, and touches stay on the surface.
            LayoutParams(1, 1),
        )

        isFocusable = true
        isFocusableInTouchMode = true
    }

    // --- lifecycle ---------------------------------------------------------

    fun onHostResume() {
        surface.onResume()
        frameLoop.poll = { session.pollWakeup() }
        frameLoop.start()
        // Numbers from this session only: a resume is a fresh start.
        stats.reset()
    }

    fun onHostPause() {
        frameLoop.stop()
        surface.onPause()
    }

    override fun onDetachedFromWindow() {
        frameLoop.stop()
        super.onDetachedFromWindow()
    }

    /** Stop the session and release the view. The view is dead after this. */
    fun release() {
        frameLoop.stop()
        session.close()
    }

    /**
     * Focus reporting (mode `?1004`): the application is told when the window
     * gains or loses focus, exactly as alacritty does in `on_focus_change`.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!session.isAlive || modes() and NativeCore.MODE_FOCUS_IN_OUT == 0) {
            return
        }
        session.write(if (hasFocus) FOCUS_IN else FOCUS_OUT)
    }

    // --- session control ---------------------------------------------------

    /**
     * Start a PTY session sized to the view. The renderer's [TerminalRenderer.onGridChanged]
     * keeps the grid in step with the actual surface size afterwards.
     */
    fun start(
        program: String,
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        cwd: String? = null,
    ): Boolean {
        session.configure(program, args, env, cwd)
        return restart()
    }

    /** (Re)start the session with the current configuration. */
    fun restart(): Boolean {
        exited = false
        selection.clear()
        listener?.onSelectionChanged(false)

        val columns = gridColumns()
        val rows = gridRows()
        val result = try {
            session.start(columns, rows, metrics.width.toInt(), metrics.height.toInt())
        } catch (error: Throwable) {
            Log.e(TAG, "native setup failed", error)
            -1
        }

        if (result != NativeCore.OK) {
            Log.e(TAG, "nativeCreateSession returned $result")
            return false
        }

        Log.i(
            TAG,
            "core ${session.coreVersion} session ${session.handle}: " +
                "${session.columns}x${session.rows} cells of " +
                "${metrics.width.toInt()}x${metrics.height.toInt()}px",
        )
        surface.requestRender()
        return true
    }

    private fun gridColumns(): Int =
        if (width == 0 || metrics.width <= 0f) 80 else (width / metrics.width).toInt().coerceAtLeast(2)

    private fun gridRows(): Int =
        if (height == 0 || metrics.height <= 0f) 24 else (height / metrics.height).toInt().coerceAtLeast(1)

    // --- host-facing helpers ----------------------------------------------

    /** The engine's mode bits (0 when there is no live session). */
    fun modes(): Int = if (session.isAlive) session.stateArray[NativeCore.STATE_MODE_BITS] else 0

    /** Is the application tracking the mouse (`?1000`/`?1002`/`?1003`)? */
    fun mouseMode(): Boolean = Mouse.active(modes())

    /** Send a named key from an external extra-keys row. */
    fun sendNamedKey(named: String, ctrl: Boolean) {
        Encoder.sequence(named, Encoder.Mods(ctrl = ctrl), appCursor())?.let(session::write)
    }

    /** Copy the selection to the platform clipboard; false when nothing is selected. */
    fun copySelection(): Boolean {
        val cells = session.cells ?: return false
        if (selection.isEmpty) {
            return false
        }

        val text = SelectionText.extract(cells, session.columns, selection)
        if (text.isEmpty()) {
            return false
        }

        clipboard().setPrimaryClip(ClipData.newPlainText(CLIPBOARD_LABEL, text))
        return true
    }

    /** Paste from the platform clipboard, bracketed when mode 2004 is on. */
    fun pasteClipboard() {
        val clip = clipboard().primaryClip
        if (clip == null || clip.itemCount == 0) {
            return
        }

        val text = clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        if (text.isEmpty()) {
            return
        }

        if (text.length > PASTE_LIMIT) {
            Log.w(TAG, "paste truncated to $PASTE_LIMIT characters")
        }
        val capped = text.take(PASTE_LIMIT)

        // Mode 2004 lets the shell tell a paste from typing, which is what keeps
        // a multi-line paste from running line by line.
        val bracketed =
            session.stateArray[NativeCore.STATE_MODE_BITS] and NativeCore.MODE_BRACKETED_PASTE != 0
        if (bracketed) {
            session.writeUtf8("\u001b[200~$capped\u001b[201~")
        } else {
            session.writeUtf8(capped)
        }
    }

    /** Whether the shell asked for the `ESC O` cursor keys (DECCKM). */
    fun appCursor(): Boolean = modes() and NativeCore.MODE_APP_CURSOR != 0

    /** One log line of frame timings, for a debug surface the host may show. */
    fun frameStatsSummary(): String = stats.summary()

    // --- font --------------------------------------------------------------

    /**
     * Load the bundled faces and ask the Rust stack for the cell metrics.
     *
     * The fonts travel inside the APK (`assets/fonts/`) so a cell is the same
     * size on every device; a failure here is a packaging bug and is reported as
     * one instead of starting a session that cannot be laid out.
     */
    private fun loadFont(): Boolean {
        val density = resources.displayMetrics.density
        fontPx = FONT_SIZE_DP * density

        return try {
            val regular = context.assets.open(REGULAR_FONT).use { it.readBytes() }
            val bold = context.assets.open(BOLD_FONT).use { it.readBytes() }

            val init = NativeCore.nativeInitFont(regular, bold)
            if (init != NativeCore.OK) {
                Log.e(TAG, "font: nativeInitFont failed ($init)")
                return false
            }

            val loaded = CellMetrics.fromCore(fontPx)
            if (loaded == null) {
                Log.e(TAG, "font: the faces produced no cell metrics")
                return false
            }
            metrics = loaded

            Log.i(
                TAG,
                "font: cell ${metrics.width}x${metrics.height}px at ${fontPx}px " +
                    "(density $density, ascent ${metrics.ascent})",
            )
            true
        } catch (error: Throwable) {
            Log.e(TAG, "font assets failed to load", error)
            false
        }
    }

    // --- input -------------------------------------------------------------

    /**
     * Send a key that arrived as a key event: a hardware keyboard, or an IME
     * that reports keys instead of committing text.
     *
     * Returning `false` lets the IME handle it, which is what must happen for a
     * plain printable key — the text path already carries those.
     */
    private fun handleHardwareKey(keyCode: Int, event: KeyEvent): Boolean {
        val named = AndroidKeys.named(keyCode)
        if (named != null) {
            val mods = AndroidKeys.mods(event, stickyCtrl)
            Encoder.sequence(named, mods, appCursor())?.let(session::write)
            stickyCtrl = false
            return true
        }

        // A printable key while Ctrl (or Alt) is held: Ctrl+C has to become 0x03
        // even though the character would normally travel as text.
        val armed = stickyCtrl || event.isAltPressed
        val codepoint = event.unicodeChar
        if (armed && codepoint != 0) {
            val character = String(Character.toChars(codepoint))
            session.write(Encoder.text(character, AndroidKeys.mods(event, stickyCtrl)))
            stickyCtrl = false
            return true
        }

        return false
    }

    private fun gestureListener(): View.OnTouchListener {
        val detector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(event: MotionEvent): Boolean = true

                override fun onSingleTapUp(event: MotionEvent): Boolean {
                    // With mouse tracking on, a tap is a left click — a finger
                    // has no buttons, so this is the mobile mapping Termux uses.
                    if (mouseMode()) {
                        clickAt(event.x, event.y)
                        if (!selection.isEmpty) {
                            selection.clear()
                            listener?.onSelectionChanged(false)
                            surface.requestRender()
                        }
                        return true
                    }

                    // A tap ends the selection instead of clearing the screen.
                    if (!selection.isEmpty) {
                        selection.clear()
                        listener?.onSelectionChanged(false)
                        surface.requestRender()
                        return true
                    }
                    focusInput()
                    return true
                }

                override fun onLongPress(event: MotionEvent) {
                    // The mobile convention: a long press picks a word, and
                    // dragging from there extends the selection.
                    selectWordAt(event.x, event.y)
                    draggingSelection = true
                    listener?.onSelectionChanged(!selection.isEmpty)
                }

                override fun onDoubleTap(event: MotionEvent): Boolean {
                    selectWordAt(event.x, event.y)
                    listener?.onSelectionChanged(!selection.isEmpty)
                    return true
                }

                override fun onScroll(
                    first: MotionEvent?,
                    current: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    if (draggingSelection) {
                        selection.extend(cellRow(current.y), cellColumn(current.x))
                        surface.requestRender()
                        return true
                    }

                    // While the application tracks the mouse, a drag is a wheel:
                    // xterm's 64/65 buttons. Scrolling the local history instead
                    // would hide the program's own scrolling from it.
                    if (mouseMode()) {
                        wheelBy(distanceY, current.x, current.y)
                        return true
                    }

                    // Finger down (positive dy) scrolls back into the history.
                    scrollAccumulator += distanceY
                    val rowsMoved = (scrollAccumulator / metrics.height).toInt()
                    if (rowsMoved != 0) {
                        scrollAccumulator -= rowsMoved * metrics.height
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

    /** A left click at a surface position: press and release, as xterm expects. */
    private fun clickAt(x: Float, y: Float) {
        val bits = modes()
        val column = cellColumn(x)
        val line = cellRow(y)
        Mouse.report(Mouse.BUTTON_LEFT, true, column, line, bits)?.let(session::write)
        Mouse.report(Mouse.BUTTON_LEFT, false, column, line, bits)?.let(session::write)
    }

    /**
     * A finger drag becomes wheel reports while the application tracks the
     * mouse — the mobile mapping Termux uses (alacritty has no touch screen to
     * map, and its wheel path reports 64/65 to the program in mouse mode).
     */
    private fun wheelBy(distanceY: Float, x: Float, y: Float) {
        scrollAccumulator += distanceY
        val rows = (scrollAccumulator / metrics.height).toInt()
        if (rows == 0) {
            return
        }
        scrollAccumulator -= rows * metrics.height

        // Positive is the same direction as the local scroll: back into the
        // history, i.e. wheel up.
        val code = if (rows > 0) Mouse.WHEEL_UP else Mouse.WHEEL_DOWN
        val bits = modes()
        val column = cellColumn(x)
        val line = cellRow(y)
        repeat(abs(rows)) {
            Mouse.report(code, true, column, line, bits)?.let(session::write)
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

        val bits = modes()
        val alternate = allowAlternate &&
            bits and NativeCore.MODE_ALT_SCREEN != 0 &&
            bits and NativeCore.MODE_ALTERNATE_SCROLL != 0
        if (alternate) {
            val sequence = if (rows > 0) "\u001bOA" else "\u001bOB"
            repeat(abs(rows)) { session.writeUtf8(sequence) }
            return
        }

        session.scroll(rows)
        surface.requestRender()
    }

    /**
     * Buttons and motion from a real pointer, when the application asked for
     * mouse reports (alacritty's `mouse_report` path).
     */
    private fun handleMouseEvent(event: MotionEvent): Boolean {
        val bits = modes()
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
                    ?.let(session::write)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> {
                lastMouseColumn = -1
                lastMouseLine = -1
                Mouse.report(mouseButton(event.actionButton), false, column, line, bits, mods)
                    ?.let(session::write)
            }
            MotionEvent.ACTION_MOVE -> {
                // One report per cell, not per pixel (alacritty does the same).
                if (column == lastMouseColumn && line == lastMouseLine) {
                    return true
                }
                lastMouseColumn = column
                lastMouseLine = line
                motionCode(bits, event.buttonState)?.let { code ->
                    Mouse.report(code, true, column, line, bits, mods)?.let(session::write)
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
            if (mouseMode()) {
                val code = if (vertical > 0f) Mouse.WHEEL_UP else Mouse.WHEEL_DOWN
                Mouse.report(code, true, cellColumn(event.x), cellRow(event.y), modes())
                    ?.let(session::write)
            } else {
                // Shift bypasses `?1007`, as it does in alacritty.
                val shift = event.metaState and KeyEvent.META_SHIFT_ON != 0
                scrollByRows(if (vertical > 0f) 1 else -1, allowAlternate = !shift)
            }
        }

        val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        if (horizontal != 0f && mouseMode()) {
            val code = if (horizontal > 0f) Mouse.WHEEL_LEFT else Mouse.WHEEL_RIGHT
            Mouse.report(code, true, cellColumn(event.x), cellRow(event.y), modes())
                ?.let(session::write)
        }

        return true
    }

    /** The grid row under a vertical surface coordinate. */
    private fun cellRow(y: Float): Int {
        val rows = session.rows
        if (rows <= 0) {
            return 0
        }
        return (y / metrics.height).toInt().coerceIn(0, rows - 1)
    }

    /** The grid column under a horizontal surface coordinate. */
    private fun cellColumn(x: Float): Int {
        val columns = session.columns
        if (columns <= 0) {
            return 0
        }
        return (x / metrics.width).toInt().coerceIn(0, columns - 1)
    }

    private fun selectWordAt(x: Float, y: Float) {
        val cells = session.cells ?: return
        val columns = session.columns
        if (columns <= 0 || session.rows <= 0) {
            return
        }

        val row = cellRow(y)
        val column = cellColumn(x)
        val range = Selection.wordRange(row, column, columns) { r, c ->
            val at = (r * columns + c) * NativeCore.CELL_BYTES
            cells.getInt(at + NativeCore.CELL_OFFSET_CODEPOINT).toChar()
        }

        selection.select(range[0], range[1], range[2], range[3])
        surface.requestRender()
    }

    private fun focusInput() {
        input.requestFocus()
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    // --- engine events -----------------------------------------------------

    private fun reportGlErrors() {
        renderer.lastGlError?.let { message ->
            renderer.lastGlError = null
            Log.w(TAG, "renderer: $message")
        }
    }

    private fun drainEvents() {
        for (event in session.drainEvents()) {
            when {
                event == "exit" || event.startsWith("child_exit") -> {
                    val code = event.removePrefix("child_exit:").toIntOrNull() ?: 0
                    if (!exited) {
                        exited = true
                        listener?.onSessionExited(code)
                    }
                }
                event.startsWith("title:") -> listener?.onTitle(event.removePrefix("title:"))
                event == "bell" -> listener?.onBell()
                event.startsWith("color_query:") -> answerColorQuery(event)
                event.startsWith("clipboard_store:") -> storeClipboard(event)
                event.startsWith("clipboard_load:") -> loadClipboard(event)
                else -> listener?.onSessionEvent(event)
            }
        }
    }

    /**
     * `OSC 4/10/11/12` query about a colour the engine has no override for, so
     * the answer comes from the default palette. The reply is alacritty's own
     * format (`dynamic_color_sequence`): every component printed twice, e.g.
     * `\x1b]11;rgb:1818/1818/1818\x07`.
     */
    private fun answerColorQuery(record: String) {
        val parts = record.split(':', limit = 4)
        if (parts.size != 4) {
            return
        }
        val index = parts[1].toIntOrNull() ?: return
        // A cursor query is only answered when the application set it; the
        // engine would have answered that case itself.
        val color = Palette.colorFor(index) ?: return

        val terminator = if (parts[3] == "st") "\u001b\\" else "\u0007"
        val red = hex(color shr 16 and 0xff)
        val green = hex(color shr 8 and 0xff)
        val blue = hex(color and 0xff)
        session.writeUtf8("\u001b]${parts[2]};rgb:$red$red/$green$green/$blue$blue$terminator")
    }

    private fun hex(value: Int): String = value.toString(16).padStart(2, '0')

    /** `OSC 52` store: the application put text on the clipboard. */
    private fun storeClipboard(record: String) {
        val parts = record.split(':', limit = 3)
        if (parts.size != 3) {
            return
        }

        val text = try {
            String(Base64.decode(parts[2], Base64.DEFAULT), Charsets.UTF_8)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "clipboard store: payload is not base64")
            return
        }

        // Android has one clipboard, so `c` and the selection `p` are the same
        // store here; the distinction only exists on X11.
        clipboard().setPrimaryClip(ClipData.newPlainText(CLIPBOARD_LABEL, text))
    }

    /** `OSC 52` load: the application asked for the clipboard. */
    private fun loadClipboard(record: String) {
        val parts = record.split(':', limit = 3)
        if (parts.size != 3) {
            return
        }

        val clip = clipboard().primaryClip
        val text = if (clip == null || clip.itemCount == 0) {
            ""
        } else {
            clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
        }

        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val terminator = if (parts[2] == "st") "\u001b\\" else "\u0007"
        session.writeUtf8("\u001b]52;${parts[1]};$encoded$terminator")
    }

    private fun clipboard(): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private companion object {
        const val TAG = "DroshEngine"

        /** Font size in dp; the cell size and the whole grid follow from it. */
        const val FONT_SIZE_DP = 12f

        /** The faces bundled in the engine module (one font, everywhere identical). */
        const val REGULAR_FONT = "fonts/JetBrainsMonoNL-Regular.ttf"
        const val BOLD_FONT = "fonts/JetBrainsMonoNL-Bold.ttf"

        /** Label on the clipboard entry, so a paste is recognisable. */
        const val CLIPBOARD_LABEL = "dev.drosh"

        /** Pastes are capped: a phone clipboard is not a file transfer. */
        const val PASTE_LIMIT = 65_536

        /** Focus reporting (`?1004`), as alacritty's `on_focus_change` sends it. */
        val FOCUS_IN = "\u001b[I".toByteArray(Charsets.US_ASCII)
        val FOCUS_OUT = "\u001b[O".toByteArray(Charsets.US_ASCII)
    }
}
