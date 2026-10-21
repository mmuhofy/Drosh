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

    private lateinit var surface: GLSurfaceView
    private lateinit var renderer: TerminalRenderer

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
        renderer = TerminalRenderer(
            session,
            GlyphAtlas(),
            FontSize(fontPx, metrics),
            selectionOverlay.selection,
            stats,
        )
        renderer.onGridChanged = { columns, rows, size ->
            // The renderer reports from the GL thread; the session is only ever
            // touched from the UI thread. The size travels with the grid: the
            // session is resized with the cell the grid was laid out for.
            post {
                session.resize(columns, rows, size.metrics.width.toInt(), size.metrics.height.toInt())
                surface.requestRender()
                applyAmbientTint()
            }
        }

        surface = GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            setOnTouchListener(gestures.listener())
            setOnGenericMotionListener { _, event -> gestures.onGenericMotion(event) }
        }
        addView(
            surface,
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
        program: String = "/system/bin/sh",
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        cwd: String? = null,
    ): Boolean {
        session.configure(program, args, env, cwd)
        return restart()
    }

    /** (Re)start the session with the current configuration. */
    fun restart(): Boolean {
        check(fontLoaded) { "the bundled font failed to load; the terminal cannot start" }
        exited = false
        selectionOverlay.clear()
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
        applyAmbientTint()
        return true
    }

    private fun gridColumns(): Int =
        if (width == 0 || metrics.width <= 0f) 80 else (width / metrics.width).toInt().coerceAtLeast(2)

    private fun gridRows(): Int =
        if (height == 0 || metrics.height <= 0f) 24 else (height / metrics.height).toInt().coerceAtLeast(1)

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
        val tint = ambientTint()
        if (tint.size == 3) {
            renderer.ambientTint = (tint[0] and 0xff shl 16) or (tint[1] and 0xff shl 8) or (tint[2] and 0xff)
        }
    }

    /** One log line of frame timings, for a debug surface the host may show. */
    fun frameStatsSummary(): String = stats.summary()

    /** Notify the host and the renderer that the selection state changed. */
    internal fun notifySelectionChanged() {
        listener?.onSelectionChanged(!selectionOverlay.isEmpty)
        surface.requestRender()
    }

    /** Request a redraw of the GL surface. */
    internal fun requestRender() {
        surface.requestRender()
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
        surface.queueEvent { renderer.ligatures = config.ligatures }

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

        surface.queueEvent { renderer.setFontSize(measured) }
    }

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
        private set

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
        surface.queueEvent {
            renderer.invalidateAtlas()
            renderer.setFontSize(FontSize(fontPx, metrics))
        }
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
    }
}
