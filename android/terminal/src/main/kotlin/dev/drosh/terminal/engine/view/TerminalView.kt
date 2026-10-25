package dev.drosh.terminal.engine.view

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Base64
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import dev.drosh.terminal.engine.bench.FrameStats
import dev.drosh.terminal.engine.core.EngineSession
import dev.drosh.terminal.engine.core.NativeCore
import dev.drosh.terminal.engine.input.Encoder
import dev.drosh.terminal.engine.input.SelectionText
import dev.drosh.terminal.engine.render.CellMetrics
import dev.drosh.terminal.engine.render.FontSize
import dev.drosh.terminal.engine.render.FrameLoop
import dev.drosh.terminal.engine.render.GlyphAtlas
import dev.drosh.terminal.engine.render.TerminalRenderer
import dev.drosh.terminal.engine.core.Palette

/**
 * The terminal view: one [EngineSession] rendered through the GL renderer, with
 * the input vocabulary (touch gestures, IME, hardware keys, real pointers,
 * selection, clipboard) attached through the [TerminalGestures], [TerminalIme]
 * and [SelectionOverlay] helpers.
 *
 * It is a [FrameLayout] rather than the GL surface itself, because the IME
 * needs a focused text editor in the hierarchy and a `SurfaceView` cannot host
 * children — a 1×1 [TerminalIme.input] rides along for that.
 *
 * The host owns the lifecycle: call [onHostResume] / [onHostPause] from the
 * activity or the Compose lifecycle, and [release] when done. Everything the
 * view does not handle itself (title, bell, exit, shell marks, …) surfaces as
 * event records through [Listener.onSessionEvent].
 *
 * Hosts should construct a [TerminalController] rather than this class directly;
 * [TerminalController] is the stable public surface and owns this view.
 */
class TerminalView(context: Context) : FrameLayout(context) {

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

    // Null until [buildContent] runs, and never assigned when the font failed
    // to load. A host that calls onHostResume on such a view must not crash:
    // the engine is not there, and the view says so through [startError].
    private var surface: GLSurfaceView? = null
    private var renderer: TerminalRenderer? = null

    /** Cell geometry from the bundled font, filled in by [loadFont]. */
    internal lateinit var metrics: CellMetrics

    /** Pixel size the glyphs are rasterised at. */
    private var fontPx = 0f

    /**
     * The size the host asked for, in sp; 0 until it asks for one.
     *
     * Held apart from [textSizeSp] so that the first request is never taken
     * for a repeat of the size the terminal opened at.
     */
    private var requestedTextSizeSp = 0f

    /** False when the bundled font failed to load; the view is dead without it. */
    private var fontLoaded = false


    private var exited = false

    /** The armed Ctrl of an external extra-keys row, honoured by the IME path. */
    @Volatile
    internal var stickyCtrl: Boolean = false

    /** Frame timings; [frameStatsSummary] exposes them for a debug surface. */
    private val stats = FrameStats()

    /**
     * How many frames the renderer has drawn.
     *
     * Zero after a surface change means the surface never rendered at all —
     * a blank terminal with nothing to report, which is the hardest kind of
     * blank terminal to read. Read from the UI thread; the renderer writes it
     * from the GL thread and a stale number is still a true one.
     */
    @Volatile
    var displayedFrameCount: Int = 0
        internal set

    /** Frames the renderer refused to draw because its grid and the session disagreed. */
    @Volatile
    var refusedFrameCount: Int = 0
        internal set

    /**
     * The cell geometry the engine is drawing cells at, in pixels.
     *
     * Zero before the font has been measured: [metrics] is late-initialised,
     * and a host that asked for it too early would take the view down with an
     * uninitialised-property error for the sake of a log line.
     */
    val cellWidthPx: Int get() = if (::metrics.isInitialized) metrics.width.toInt() else 0
    val cellHeightPx: Int get() = if (::metrics.isInitialized) metrics.height.toInt() else 0

    /** The GL version the surface reported, or "none" before it has one. */
    val glVersion: String get() = renderer?.glVersion ?: "none"

    /**
     * The frame pump: the engine's wakeup flag is polled once per vsync instead
     * of on a timer, so output reaches the screen on the next refresh.
     */
    private val frameLoop = FrameLoop(
        onWakeup = {
            reportGlErrors()
            surface?.requestRender()
            drainEvents()
        },
        onBlink = {
            reportGlErrors()
            renderer?.let { it.cursorVisible = !it.cursorVisible }
            surface?.requestRender()
            drainEvents()
        },
    )

    /** Selection state, owned by [SelectionOverlay]; the renderer reads its [SelectionOverlay.selection]. */
    internal val selectionOverlay = SelectionOverlay(this)

    /** Touch gestures, mouse and scroll-wheel handling. */
    private val gestures = TerminalGestures(this)

    /** IME wiring (soft keyboard + hardware keys). */
    private val ime = TerminalIme(this)

    /** The IME's focus target; the host must keep it in the hierarchy (1×1 px). */
    val imeTarget: android.view.View get() = ime.input

    init {
        if (loadFont()) {
            buildContent()
        } else {
            Log.e(TAG, "the bundled font failed to load; the terminal cannot start")
        }
    }

    /** Everything that needs the font's metrics; skipped when [fontLoaded] is false. */
    private fun buildContent() {
        val created = TerminalRenderer(
            session,
            GlyphAtlas(),
            FontSize(fontPx, metrics),
            selectionOverlay.selection,
            stats,
        ) { drawn ->
            // Written from the GL thread and read from the UI thread: both
            // counters only ever grow, so a reader sees a true number either
            // way.
            if (drawn) displayedFrameCount += 1 else refusedFrameCount += 1
        }
        created.onGridChanged = { columns, rows, size ->
            // The renderer reports from the GL thread; the session is only ever
            // touched from the UI thread. The size travels with the grid: the
            // session is resized with the cell the grid was laid out for.
            //
            // Posted, not run inline, and that is deliberate: a resize from the
            // GL thread would race the parser. What it must not do is wait for
            // the *next* frame to be requested — the frame that reported this
            // is already drawing, so the request has to be made here or the
            // surface sits on the refused frame until something else asks for
            // a redraw.
            post {
                session.resize(columns, rows, size.metrics.width.toInt(), size.metrics.height.toInt())
                surface?.requestRender()
                applyAmbientTint()
            }
        }
        renderer = created

        val gl = GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            // Keep the GL context alive across a pause.
            //
            // Without this, GLSurfaceView throws the context away on every
            // `onPause` and rebuilds the programs, the glyph atlas and the
            // vertex buffers on the next `onResume` — and the surface in
            // between has nothing on it. The keyboard, the sidebar and a split
            // all pause the host for a moment while they animate, so the
            // terminal blinked to black every time one of them moved.
            preserveEGLContextOnPause = true
            setRenderer(created)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            setOnTouchListener(gestures.listener())
            setOnGenericMotionListener { _, event -> gestures.onGenericMotion(event) }
        }
        surface = gl
        addView(
            gl,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        addView(
            ime.input,
            // One pixel: it only exists so the soft keyboard has a focused text
            // editor to talk to, and touches stay on the surface.
            LayoutParams(1, 1),
        )

        isFocusable = true
        isFocusableInTouchMode = true
    }

    // --- lifecycle ---------------------------------------------------------

    fun onHostResume() {
        // No surface means the font failed to load; there is nothing to
        // resume, and [startError] says why.
        surface?.onResume() ?: return
        frameLoop.poll = { session.pollWakeup() }
        frameLoop.start()
        // Numbers from this session only: a resume is a fresh start.
        stats.reset()
        // Draw at once rather than waiting for output: a resume with a quiet
        // session otherwise shows whatever the surface happened to keep.
        surface?.requestRender()
    }

    fun onHostPause() {
        frameLoop.stop()
        surface?.onPause()
        // The last frame stays on screen while the host is paused. With the
        // context preserved the surface keeps its contents, but a redraw is
        // still what puts them back after the window comes forward again.
        surface?.requestRender()
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
        program: String = "/system/bin/sh",
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        cwd: String? = null,
    ): Boolean {
        session.configure(program, args, env, cwd)
        startError = null
        return restart()
    }

    /** (Re)start the session with the current configuration. */
    fun restart(): Boolean {
        if (!fontLoaded) {
            Log.e(TAG, "restart refused: ${startError ?: "the font failed to load"}")
            // Said out loud rather than left in the log: [startError] is what
            // the host shows, and a refusal that leaves it null is a blank
            // terminal with no reason on it.
            if (startError == null) {
                startError = "the font stack could not be started"
            }
            return false
        }
        exited = false
        selectionOverlay.clear()
        listener?.onSelectionChanged(false)

        val columns = gridColumns()
        val rows = gridRows()
        // The start, in full, before it is attempted: the size the grid was
        // laid out for, the size the view actually has, and when the call was
        // made. A session that never appears is then a timestamp that has no
        // matching "started" line, rather than a blank terminal with nothing
        // to read.
        Log.i(
            TAG,
            "start: grid ${columns}x$rows, view ${width}x$height, " +
                "cell ${metrics.width.toInt()}x${metrics.height.toInt()}px",
        )
        val startedAt = System.nanoTime()
        val result = try {
            session.start(columns, rows, metrics.width.toInt(), metrics.height.toInt())
        } catch (error: Throwable) {
            Log.e(TAG, "native setup failed", error)
            -1
        }
        Log.i(
            TAG,
            "start returned $result after ${(System.nanoTime() - startedAt) / 1_000_000} ms",
        )

        if (result != NativeCore.OK) {
            Log.e(TAG, "nativeCreateSession returned $result")
            // The code is the engine's own: a missing program, no more
            // descriptors, too many sessions. It is the only thing that says
            // which, and the host has nowhere else to learn it.
            startError = "the session could not be started (engine error $result)"
            return false
        }

        Log.i(
            TAG,
            "core ${session.coreVersion} session ${session.handle}: " +
                "${session.columns}x${session.rows} cells of " +
                "${metrics.width.toInt()}x${metrics.height.toInt()}px",
        )
        surface?.requestRender()
        applyAmbientTint()
        return true
    }

    private fun gridColumns(): Int =
        if (width == 0 || metrics.width <= 0f) fallbackColumns() else (width / metrics.width).toInt().coerceAtLeast(2)

    private fun gridRows(): Int =
        if (height == 0 || metrics.height <= 0f) fallbackRows() else (height / metrics.height).toInt().coerceAtLeast(1)

    /**
     * The grid when the view has not been laid out yet.
     *
     * The engine's window size is fixed at the moment a session is created —
     * the terminal is told how many columns and rows it has before the child
     * program runs — so a session started before the first measure would be
     * locked to a grid that has nothing to do with the phone. The screen's own
     * metrics are the honest answer at that point: they are what the view is
     * about to be laid out into.
     *
     * This is the same rule the engine's own lab uses, and the difference it
     * makes is the difference between a session with a size and one that is
     * waiting for a view that may never be measured.
     */
    private fun fallbackColumns(): Int {
        val screen = resources.displayMetrics.widthPixels
        if (screen <= 0 || metrics.width <= 0f) return FALLBACK_COLUMNS
        return (screen / metrics.width).toInt().coerceAtLeast(2)
    }

    private fun fallbackRows(): Int {
        val screen = resources.displayMetrics.heightPixels
        if (screen <= 0 || metrics.height <= 0f) return FALLBACK_ROWS
        return (screen / metrics.height).toInt().coerceAtLeast(1)
    }

    // --- host-facing helpers ----------------------------------------------

    /** The engine's mode bits (0 when there is no live session). */
    internal fun modes(): Int = if (session.isAlive) session.stateArray[NativeCore.STATE_MODE_BITS] else 0

    /** Is the application tracking the mouse (`?1000`/`?1002`/`?1003`)? */
    internal fun mouseMode(): Boolean = dev.drosh.terminal.engine.input.Mouse.active(modes())

    /** Whether the shell asked for the `ESC O` cursor keys (DECCKM). */
    fun appCursor(): Boolean = modes() and NativeCore.MODE_APP_CURSOR != 0

    /** Ask the engine for the ambient tint (average background) of the grid. */
    internal fun ambientTint(): IntArray {
        if (!session.isAlive) {
            return IntArray(0)
        }
        val out = IntArray(3)
        return if (NativeCore.nativeAmbientTint(session.handle, out) == NativeCore.OK) out else IntArray(0)
    }

    /** Push the ambient tint into the renderer so the clear colour matches the program. */
    internal fun applyAmbientTint() {
        val target = renderer ?: return
        val tint = ambientTint()
        if (tint.size == 3) {
            target.ambientTint = (tint[0] and 0xff shl 16) or (tint[1] and 0xff shl 8) or (tint[2] and 0xff)
        }
    }

    /** One log line of frame timings, for a debug surface the host may show. */
    fun frameStatsSummary(): String = stats.summary()

    /** Notify the host and the renderer that the selection state changed. */
    internal fun notifySelectionChanged() {
        listener?.onSelectionChanged(!selectionOverlay.isEmpty)
        surface?.requestRender()
    }

    /** Request a redraw of the GL surface. */
    internal fun requestRender() {
        surface?.requestRender()
    }


    /** Focus the IME target and open the soft keyboard. */
    internal fun focusInput() {
        ime.input.requestFocus()
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        manager.showSoftInput(ime.input, InputMethodManager.SHOW_IMPLICIT)
    }

    /** Copy the selection to the platform clipboard; false when nothing is selected. */
    fun copySelection(): Boolean {
        val cells = session.cells ?: return false
        if (selectionOverlay.isEmpty) {
            return false
        }

        val text = SelectionText.extract(cells, session.columns, selectionOverlay.selection)
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

    /** Send a named key from an external extra-keys row. */
    fun sendNamedKey(named: Encoder.Named, ctrl: Boolean) {
        Encoder.sequence(named, Encoder.Mods(ctrl = ctrl), appCursor())?.let(session::write)
    }

    // --- font --------------------------------------------------------------

    /**
     * The size the terminal is drawing at, in sp.
     *
     * The size the terminal opened at until the host sets another one, and
     * the last size the host asked for after that.
     */
    val textSizeSp: Float
        get() = if (requestedTextSizeSp > 0f) requestedTextSizeSp else DEFAULT_TEXT_SIZE_SP

    /**
     * Set the size of the terminal's text, in sp.
     *
     * `sp` is the app's unit — the one the font-size setting stores — and the
     * conversion to pixels happens here, once: the font stack measures in
     * pixels, and the engine draws in pixels.
     *
     * The cell is re-measured at the new size and the grid is laid out again
     * — a bigger cell means fewer columns and fewer rows — and the session
     * is resized to match, through the same path a surface change takes.
     *
     * The size is clamped to what a grid can hold; a host that asks for the
     * impossible gets the nearest size that is possible, and a warning says
     * so.
     *
     * The renderer owns the cell geometry while it draws, so the change is
     * handed over to the GL thread; the renderer reports the grid it laid
     * out, and the session follows on the UI thread.
     */
    /**
     * The application's terminal configuration.
     *
     * alacritty's split, the same way: the *application* holds the
     * configuration and the *display* holds what it resolved. The terminal's own
     * answers still win — the colours an `OSC 4/10/11/12` set, the cursor a
     * `DECSCUSR` asked for — because a program that asked has a reason.
     */
    fun applyAppearance(config: dev.drosh.domain.settings.TerminalConfig) {
        // The palette is the engine's wire format, so it goes in whole.
        dev.drosh.domain.settings.TerminalPaletteTable.of(config.palette)
            .let { dev.drosh.terminal.engine.core.Palette.setApplicationTable(it) }

        // The cursor's shape is the terminal's to decide; this is what it falls
        // back on (alacritty: `TermConfig::default_cursor_style`). The term's
        // configuration is fixed when the session starts, so the shape goes
        // into the session rather than into the renderer.
        val shape = when (config.cursorStyle) {
            dev.drosh.domain.settings.CursorStyle.Block ->
                dev.drosh.terminal.engine.core.NativeCore.CURSOR_BLOCK

            dev.drosh.domain.settings.CursorStyle.Beam ->
                dev.drosh.terminal.engine.core.NativeCore.CURSOR_BEAM

            dev.drosh.domain.settings.CursorStyle.Underline ->
                dev.drosh.terminal.engine.core.NativeCore.CURSOR_UNDERLINE
        }
        session.cursorShape = shape
        // And the running session, when there is one: the term's
        // configuration can be changed while it runs, so the setting does not
        // wait for the next session.
        session.applyCursorShape(shape)

        // Whether bold text is drawn in the bright colours.
        dev.drosh.terminal.engine.core.Palette.boldBrightens = config.boldBrightens

        // The ligatures: the font decides what a ligature is, and this is the
        // setting that asks it. The renderer reads it on the GL thread.
        surface?.queueEvent { renderer?.let { it.ligatures = config.ligatures } }

        // The blink is the frame loop's business, and it asks the view.
        frameLoop.blinkIntervalMillis = config.cursorBlinkMs.toLong()

        setTextSize(config.textSizeSp)
        requestRender()
    }

    fun setTextSize(sp: Float) {
        if (!fontLoaded) {
            Log.w(TAG, "the bundled font failed to load; the text size cannot change")
            return
        }
        if (sp <= 0f) {
            Log.w(TAG, "$sp sp is not a size; keeping ${textSizeSp} sp")
            return
        }

        // The grid is as big as the surface divided by the cell, so a cell of
        // a pixel would ask for a grid of millions of cells. The size is
        // clamped rather than refused: a host that asks for the impossible
        // gets the nearest size that is possible.
        val size = sp.coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)
        if (size != sp) {
            Log.w(TAG, "$sp sp is outside $MIN_TEXT_SIZE_SP..$MAX_TEXT_SIZE_SP sp; drawing at $size sp")
        }
        if (size == requestedTextSizeSp) {
            // The same size again: nothing to measure and nothing to lay out.
            return
        }

        val measured = FontSize.measure(spToPx(size))
        if (measured == null) {
            Log.e(TAG, "the font stack produced no metrics at $size sp; keeping ${textSizeSp} sp")
            return
        }

        requestedTextSizeSp = size
        metrics = measured.metrics
        fontPx = measured.px

        surface?.queueEvent { renderer?.setFontSize(measured) }
    }

    /**
     * Zoom to [sp], keeping the line under `focusY` where it is.
     *
     * The vendored terminal's `zoomTo`, unchanged in behaviour: the row under
     * the fingers is remembered as an *absolute* history row before the size
     * changes — a viewport-relative row does not survive the reflow, an
     * absolute one does — and put back under them afterwards. Without it a
     * pinch drags the output out from under the finger, and the further into
     * the scrollback the user is, the further it goes.
     *
     * The row is clamped to what the history still has: zooming out makes the
     * grid hold more rows, so the row the fingers were on can be one that no
     * longer exists, and the bottom of the screen is then empty rather than
     * wrong.
     */
    internal fun zoomTo(sp: Float, focusY: Float) {
        val spacingBefore = metrics.height
        val offsetBefore = session.stateArray[NativeCore.STATE_DISPLAY_OFFSET]
        val anchoredRow = offsetBefore + if (spacingBefore > 0f) (focusY / spacingBefore).toInt() else 0

        setTextSize(sp)

        val spacingAfter = metrics.height
        if (spacingAfter <= 0f || !fontLoaded) {
            return
        }
        val rowUnderFocus = (focusY / spacingAfter).toInt()
        val target = (anchoredRow - rowUnderFocus)
        // The engine owns the clamp; asking for more than it has settles at its
        // own end, which is where a pinch that ran out of history belongs.
        session.scrollToOffset(target)
        requestRender()
    }

    /**
     * The size a pinch settled on.
     *
     * Reported once per gesture rather than per frame: the value is worth
     * persisting once, at the end, and every frame of a pinch would write the
     * settings file a few dozen times. The host decides whether it keeps it.
     */
    internal var zoomEndListener: (sp: Float) -> Unit = {}

    /** Forwarded from the gesture recogniser, which owns the pinch's lifetime. */
    internal fun onZoomEnd(sp: Float) = zoomEndListener.invoke(sp)

    /**
     * The scale from sp to pixels.
     *
     * `scaledDensity` is the display density with the user's font-size
     * preference applied — the factor `sp` is defined by — so a size in sp
     * means here what it means everywhere else in the app.
     */
    private fun spToPx(sp: Float): Float = sp * resources.displayMetrics.scaledDensity

    /**
     * Load the bundled faces and ask the Rust stack for the cell metrics.
     *
     * The fonts travel inside the APK (`assets/fonts/`) so a cell is the same
     * size on every device; a failure here is a packaging bug and is reported as
     * one instead of starting a session that cannot be laid out.
     */
    /**
     * Why the engine could not start, when it could not.
     *
     * The usual reason is a build without the Rust core: `libdrosh_core.so` is
     * cross-compiled by CI and dropped into the app's native library folder, so
     * a build that skipped that step has no engine at all. The symptom without
     * this is a white rectangle, because the surface never draws a frame — so
     * the host is told, in words, what is missing.
     */
    var startError: String? = null
        internal set

    /**
     * Use a font of the host's choosing, instead of the bundled one.
     *
     * The bytes are the font file itself: the host reads it — from the
     * device, from anywhere else — and hands it over. The engine does not
     * know where a font came from, and the bundled face is only the default.
     *
     * A custom face has no bold companion, so bold text is drawn from the
     * same face and emboldened by the renderer. The cell is measured from
     * the new face, so the grid is laid out around it.
     *
     * `null` goes back to the bundled font.
     */
    fun setCustomFont(bytes: ByteArray?): Boolean {
        if (!fontLoaded) {
            return false
        }

        val init = try {
            if (bytes == null) {
                val regular = context.assets.open(REGULAR_FONT).use { it.readBytes() }
                val bold = context.assets.open(BOLD_FONT).use { it.readBytes() }
                NativeCore.nativeInitFont(regular, bold)
            } else {
                NativeCore.nativeInitFont(bytes, null)
            }
        } catch (error: Throwable) {
            Log.e(TAG, "the custom font could not be read", error)
            return false
        }
        if (init != NativeCore.OK) {
            Log.e(TAG, "font: the custom font was refused ($init)")
            return false
        }

        val loaded = CellMetrics.fromCore(fontPx)
        if (loaded == null) {
            Log.e(TAG, "font: the custom font produced no cell metrics")
            return false
        }
        metrics = loaded

        // The atlas holds the masks of the font that was there before; every
        // one of them is wrong now.
        surface?.queueEvent { renderer?.setFontSize(FontSize(fontPx, metrics)) }
        Log.i(
            TAG,
            "font: the custom face, cell ${metrics.width}x${metrics.height}px at ${fontPx}px",
        )
        return true
    }

    private fun loadFont(): Boolean {
        val scale = resources.displayMetrics.scaledDensity
        fontPx = spToPx(DEFAULT_TEXT_SIZE_SP)

        return try {
            val regular = context.assets.open(REGULAR_FONT).use { it.readBytes() }
            val bold = context.assets.open(BOLD_FONT).use { it.readBytes() }

            val init = NativeCore.nativeInitFont(regular, bold)
            if (init != NativeCore.OK) {
                Log.e(TAG, "font: nativeInitFont failed ($init)")
                startError = "the font stack could not be started ($init)"
                return false
            }

            val loaded = CellMetrics.fromCore(fontPx)
            if (loaded == null) {
                Log.e(TAG, "font: the faces produced no cell metrics")
                startError = "the bundled font produced no cell metrics"
                return false
            }
            metrics = loaded
            fontLoaded = true

            Log.i(
                TAG,
                "font: cell ${metrics.width}x${metrics.height}px at ${fontPx}px " +
                    "(scale $scale, ascent ${metrics.ascent})",
            )
            true
        } catch (error: Throwable) {
            Log.e(TAG, "font assets failed to load", error)
            // A missing native library is the usual cause: the core is
            // cross-compiled by CI and dropped into the app's native library
            // folder, and a build that skipped that step has no engine.
            startError = if (error is UnsatisfiedLinkError) {
                "the terminal engine (libdrosh_core.so) is not in this build"
            } else {
                "the bundled font could not be loaded"
            }
            false
        }
    }

    // --- engine events -----------------------------------------------------

    private fun reportGlErrors() {
        val target = renderer ?: return
        target.lastGlError?.let { message ->
            target.lastGlError = null
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

        /** The size the terminal opens at, in sp, until the host sets another one. */
        const val DEFAULT_TEXT_SIZE_SP = 12f

        /**
         * The smallest text the engine draws, in sp.
         *
         * The grid is the surface divided by the cell, so a cell of a pixel
         * would ask for millions of cells and their buffers.
         */
        const val MIN_TEXT_SIZE_SP = 8f

        /** The largest text the engine draws, in sp: a cell wider than a screen. */
        const val MAX_TEXT_SIZE_SP = 96f

        /** The faces bundled in the engine module (one font, everywhere identical). */
        const val REGULAR_FONT = "fonts/JetBrainsMono-Regular.ttf"
        const val BOLD_FONT = "fonts/JetBrainsMono-Bold.ttf"

        /** Label on the clipboard entry, so a paste is recognisable. */
        const val CLIPBOARD_LABEL = "dev.drosh"

        /** Pastes are capped: a phone clipboard is not a file transfer. */
        const val PASTE_LIMIT = 65_536

        /** Focus reporting (`?1004`), as alacritty's `on_focus_change` sends it. */
        val FOCUS_IN = "\u001b[I".toByteArray(Charsets.US_ASCII)
        val FOCUS_OUT = "\u001b[O".toByteArray(Charsets.US_ASCII)

        /**
         * The grid a session opens at when the view has not been measured.
         *
         * Only reached on a device whose display metrics are unusable, which
         * is not a real screen: 80x24 is the classic terminal, and it is a
         * size the engine can draw rather than a size of zero.
         */
        const val FALLBACK_COLUMNS = 80
        const val FALLBACK_ROWS = 24
    }
}
