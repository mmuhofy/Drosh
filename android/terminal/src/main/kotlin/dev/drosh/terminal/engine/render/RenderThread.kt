package dev.drosh.terminal.engine.render

import android.util.Log
import dev.drosh.terminal.engine.bench.BenchmarkLog
import dev.drosh.terminal.engine.bench.RenderBench
import dev.drosh.terminal.engine.layout.SizeInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The thread that draws.
 *
 * ## What it owns
 *
 * Everything visual: the EGL context, the glyph atlas, the shaders, the vertex
 * buffers, and the decision of when a frame is produced. Nothing outside this
 * thread touches any of it.
 *
 * That ownership is the whole design. The previous engine spread the same state
 * across four writers — the surface callback, the font setter, the pinch
 * gesture, the Compose inset — each correct at its own instant and none correct
 * together. A frame could be built from a surface of one size and a cell of
 * another, which is the text drawn inside itself while a keyboard animates.
 *
 * Here, everything that wants to change something posts a [Command] and the
 * loop applies it between frames. There is no window in which two threads
 * disagree, because there is only one thread.
 *
 * ## The loop
 *
 * ```
 * while (running) {
 *     drainCommands()          // layout, font, pinch, scroll
 *     change = layout.poll(now)
 *     if (change != null) resize(change)     // the one reflow
 *     if (dirty) { draw(); swap() }          // swap only when drawn
 *     else waitForWork(timeout)
 * }
 * ```
 *
 * `waitForWork` is a condition variable, not a sleep: the loop wakes the
 * instant something posts a command, and otherwise waits for the next blink.
 *
 * ## Why it does not use `GLSurfaceView`
 *
 * See [EglCore]. The short version: `GLSurfaceView` swaps whether or not a
 * frame was drawn, which is the one-frame wipe on every state change.
 */
class RenderThread(
    private val onFrame: (FrameTiming) -> Unit = {},
) {

    /** Something the loop has to apply before the next frame. */
    sealed interface Command {
        /** The window surface changed. Null when it went away. */
        data class Surface(
            val surface: android.view.Surface?,
            val width: Int,
            val height: Int,
        ) : Command

        /**
         * The font's cell size changed.
         *
         * `fontPx` is the size the glyphs are rasterised at, which is the font's
         * own size — not the cell's height, which is the line box and is about a
         * fifth taller. Rasterising at the cell height draws every glyph too
         * big for its cell.
         */
        data class Cell(
            val width: Float,
            val height: Float,
            val fontPx: Float,
            /**
             * True when the grid is laid out again at this cell.
             *
             * False for a live pinch: the cell changed and the grid did not,
             * so the text grows past the edge of the screen and the engine is
             * not resized on every step of the gesture.
             */
            val reflow: Boolean = true,
        ) : Command

        /** The sub-line scroll remainder, in pixels. */
        data class ScrollOffset(val pixels: Float) : Command

        /**
         * Move the viewport, and the pixel remainder that goes with it.
         *
         * The two belong together: the engine moves by whole rows and the
         * picture is shifted by the fraction between them. Applied in
         * separate frames, the row lands first and the remainder a frame
         * later — the content jumps a whole row and then shifts back, which
         * is what a scroll that pulls itself like a spring looks like.
         *
         * The engine may refuse the move (at the ends of the history it
         * clamps), so the remainder is computed against where the viewport
         * *actually* is once the move has been made.
         */
        data class Scroll(
            val session: Long,
            /** The absolute row to show, from the top of the scrollback. */
            val row: Int,
            /** The exact row the content's top is at. */
            val position: Float,
            /** True when the drag reached the live edge. */
            val toBottom: Boolean,
        ) : Command

        /** Redraw without changing anything — new output arrived. */
        data object Invalidate : Command

        /**
         * The colour a search match is drawn in, from the theme.
         */
        data class HighlightColour(val argb: Int) : Command

        /**
         * The colour the search's other matches are drawn in, from the theme.
         */
        data class HighlightAllColour(val argb: Int) : Command

        /**
         * A program rang the bell.
         *
         * The terminal flashes for a moment, and the loop keeps drawing while
         * it does: the flash is a moment long and the loop's idle wait is
         * half a second, so without this the flash would be a frame that
         * arrives when the flash is over.
         */
        data object Bell : Command

        /**
         * The colour the bell's wash is in, from the theme.
         */
        data class BellColour(val argb: Int) : Command

        /**
         * Whether the surface is wiped to transparent, for a background image.
         */
        data class TransparentBackground(val transparent: Boolean) : Command

        /**
         * The theme's terminal background changed.
         *
         * The surface is wiped to it before every frame, so it is the colour
         * of everything the grid does not cover.
         */
        data class Background(val argb: Int) : Command

        /**
         * Wipe the screen.
         *
         * The session behind the pane changed. The new session's grid is
         * empty until its shell prints, and a frame that draws nothing leaves
         * the previous picture alone — so the old session would stay on
         * screen while the title already says the new one.
         */
        data object Clear : Command

        /** The view is being released. */
        data object Quit : Command
    }

    /** One frame's timings, for the diagnostic surface. */
    data class FrameTiming(
        val totalNanos: Long,
        val drawNanos: Long,
        val cellsDrawn: Int,
        val swapped: Boolean,
    )

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    private val lock = ReentrantLock()
    private val work = lock.newCondition()
    private val pending = ArrayDeque<Command>()

    /** Set by the loop when the terminal produced a frame worth drawing. */
    @Volatile
    private var dirty = true

    private val egl = EglCore()
    private val layout = dev.drosh.terminal.engine.layout.LayoutMachine()

    /**
     * Whether the terminal has the focus.
     *
     * A terminal that does not have it draws a hollow cursor: the cursor says
     * where it is without pretending to be the place the user is typing.
     */
    @Volatile
    var focused: Boolean = true

    /**
     * The selection's rectangle changed.
     *
     * The bounds are measured while the frame is drawn, so the chrome cannot
     * ask for them when the finger goes down — at that moment they still
     * describe the previous frame. Publishing them here, once per change, is
     * what makes the menu and its handles appear on the selection that was
     * just drawn.
     */
    @Volatile
    var onSelectionBounds: ((android.graphics.Rect?) -> Unit)? = null

    private var publishedSelection: android.graphics.Rect? = null

    /**
     * Where the cells come from. Set before [start], cleared after [stop].
     *
     * A function rather than an object so the loop does not need to know what a
     * session is; it asks for bytes and draws them.
     *
     * The buffer is the loop's own and is lent to the caller: the engine
     * writes the frame into it, so a frame does not become a Java array on the
     * way and nothing is allocated per frame. The caller must not keep the
     * frame past the next call — the renderer reads and draws it before then,
     * which is what makes the reuse safe.
     *
     * @return the bytes written, 0 when there was no frame, and -1 when the
     *   buffer was too small.
     */
    @Volatile
    var frameSource: ((java.nio.ByteBuffer) -> Int)? = null

    /**
     * The buffer the frames are written into.
     *
     * Owned by this thread: the frame is read and drawn here, and nothing
     * else touches it. It starts at a size that fits a phone's grid and grows
     * when the engine says it does not fit — which happens once, when the grid
     * is bigger than the guess.
     */
    private var frameBuffer: java.nio.ByteBuffer? = null

    /**
     * Where the selected cells are, in view pixels.
     *
     * The renderer measures them — it is the one that sees the engine's
     * selected cells — and the chrome's menu hangs on that rectangle.
     */
    @Volatile
    var selectionBounds: android.graphics.Rect? = null
        private set

    /**
     * The pictures a program drew, and where they are.
     *
     * The pixels come once per picture generation, so a frame that shows the
     * same picture again carries only where it is.
     */
    var imageSource: (() -> ByteArray?)? = null

    /** The renderer's own GL state, created on the loop thread. */
    private var renderer: FrameRenderer? = null

    /**
     * The face the terminal is drawn in.
     *
     * The surface sets it before the loop starts, because the atlas rasterises
     * its masks with it and the host measures its cell with it: one face, so
     * the two cannot disagree about how wide a character is.
     */
    @Volatile
    var typeface: android.graphics.Typeface? = null

    /** The face the current renderer was last told to draw with. */
    private var appliedTypeface: android.graphics.Typeface? = null

    /**
     * The theme's values, kept until a renderer exists to hear them.
     *
     * The renderer is created on the first frame, and a surface can be told
     * its background, its highlight colour and its transparency before that
     * — a pane that is built and then drawn a moment later. Dropped, those
     * commands left the renderer on its own defaults: a new session drew on
     * the library's near-black instead of the theme, which is the plain black
     * a fresh terminal showed.
     */
    private var background: Int? = null
    private var highlightColour: Int? = null
    private var highlightAllColour: Int? = null
    private var bellColour: Int? = null
    private var transparentBackground: Boolean? = null

    /**
     * When the visual bell's flash is over.
     *
     * The loop draws every frame until then, so a flash that is a moment long
     * is actually seen.
     */
    @Volatile
    private var bellFlashUntil: Long = 0L

    /**
     * How far the picture is shifted inside the viewport, in pixels.
     *
     * The picture moves by this much when the user drags between two rows, and
     * a tap has to be read against it: a tap's pixel is on the picture, and the
     * picture is not where the grid is.
     */
    @Volatile
    var scrollOffsetPx: Float = 0f

    /**
     * The font's own pixel size, from the newest cell command that carried one.
     *
     * It is kept here rather than only on the renderer because the renderer is
     * created lazily, on the first frame — the surface arrives after the view is
     * measured, so a cell command can be older than the renderer that draws it.
     * Applied only to the renderer, that command was dropped on the floor and
     * every glyph was rasterised at the cell's height, the line box, about a
     * sixth larger than the font: text too big for its own block.
     *
     * Zero is not a size. `TerminalSurface.attach` has a cell and no font, and
     * posts zero for this; treating that as a size would overwrite a good one
     * with a bad one, so a zero command means "this caller did not know".
     */
    private var fontPx: Float = 0f

    /**
     * Why the renderer came up empty, or null when it drew.
     *
     * No `@Volatile` of its own: it has no backing field, and `renderer` — the
     * field it reads — is already volatile.
     */
    val rendererFailure: String? get() = renderer?.failure

    /**
     * Told the shape of every frame the loop is about to draw.
     *
     * Set from the surface thread, called on the loop's. The numbers are the
     * only thing that separates a screen drawing something invisible from one
     * drawing nothing.
     */
    @Volatile
    var onFrameShape: ((
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
    ) -> Unit)? = null

    /** True while the loop is running. */
    val isRunning: Boolean
        get() = running.get()

    /** Start the loop. Safe to call twice; the second call is a no-op. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val created = Thread({ loop() }, "drosh-render")
        thread = created
        created.start()
    }

    /**
     * Ask for a redraw.
     *
     * Cheap and thread-safe: a flag and a signal. The loop coalesces them, so
     * a hundred invalidations between frames cost one frame.
     */
    fun invalidate() {
        dirty = true
        signal()
    }

    /**
     * Ask for a frame in which every row is rebuilt.
     *
     * A selection that changes does not make the engine's dirty flags say so:
     * the cells it covers are already on the screen, and the engine only marks
     * a row dirty when its *text* changed. Without this the swapped colours of
     * a selection are painted on no frame at all, and a long press highlights
     * nothing even though the engine says it selected something.
     */
    fun invalidateFull() {
        forceFullFrame = true
        dirty = true
        signal()
    }

    @Volatile
    private var forceFullFrame = false

    /** Post a command for the loop to apply. */
    fun post(command: Command) {
        lock.withLock { pending.addLast(command) }
        signal()
    }

    private fun signal() {
        lock.withLock { work.signalAll() }
    }

    /**
     * Stop the loop and release the context.
     *
     * Joins the thread, so a caller that returns from this knows nothing is
     * touching GL any more.
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        post(Command.Quit)
        thread?.let { runCatching { it.join(STOP_TIMEOUT_MS) } }
        thread = null
    }

    private fun loop() {
        if (!egl.initialize()) {
            Log.e(TAG, "EGL could not be initialised; the terminal will not draw")
            running.set(false)
            return
        }

        while (running.get()) {
            val commands = drainCommands()
            if (!running.get()) break

            var surfaceChanged = false
            var cellChanged = false
            var now = System.nanoTime() / 1_000_000

            for (command in commands) {
                when (command) {
                    is Command.Surface -> {
                        if (command.surface == null) {
                            egl.detach()
                        } else if (egl.attach(command.surface)) {
                            // The command's size is what the caller believed.
                            // A surface created before the view was laid out
                            // reports 0x0, and a grid of 0x0 is a grid of two
                            // columns and one row — which resizes the session
                            // and throws its contents away. EGL knows the real
                            // size; ask it.
                            val (width, height) = egl.surfaceSize()
                                ?: (command.width to command.height)
                            if (width > 0 && height > 0) {
                                layout.onSurface(width, height, now)
                                surfaceChanged = true
                            }
                        }
                    }

                    is Command.Cell -> {
                        if (command.reflow) {
                            layout.onCell(command.width, command.height, now)
                        } else {
                            layout.onZoomCell(command.width, command.height, now)
                        }
                        // A zero size is "the caller did not know", not "the
                        // font is zero pixels". Keeping the last real size is
                        // what stops `attach`'s cell-only command from making
                        // every glyph too big, and holding it on the loop is
                        // what carries it across a renderer that does not exist
                        // yet — see [fontPx].
                        if (command.fontPx > 0f) {
                            fontPx = command.fontPx
                            renderer?.fontPx = command.fontPx
                        }
                        cellChanged = true
                    }

                    is Command.ScrollOffset -> {
                        layout.onScrollOffset(command.pixels)
                        dirty = true
                    }

                    is Command.Scroll -> {
                        // The engine first, then the remainder against where
                        // the viewport ended up: the engine clamps at the ends
                        // of the history, and a remainder computed against a
                        // row it refused to reach is a phantom movement.
                        val tag = if (command.toBottom) {
                            dev.drosh.terminal.engine.native.NativeSession.SCROLL_BOTTOM
                        } else {
                            dev.drosh.terminal.engine.native.NativeSession.SCROLL_ROW
                        }
                        val amount = if (command.toBottom) 0 else command.row
                        dev.drosh.terminal.engine.native.NativeSession.scroll(
                            command.session,
                            tag,
                            amount,
                        )
                        val actual = dev.drosh.terminal.engine.native.NativeSession
                            .scrollbar(command.session)
                            ?.offset
                            ?: command.row
                        val spacing = layout.size.cellHeight.coerceAtLeast(1f)
                        layout.onScrollOffset((actual - command.position) * spacing)
                        dirty = true
                    }

                    is Command.Invalidate -> dirty = true

                    is Command.HighlightColour -> {
                        highlightColour = command.argb
                        renderer?.setHighlightColour(command.argb)
                        dirty = true
                    }

                    is Command.HighlightAllColour -> {
                        highlightAllColour = command.argb
                        renderer?.setHighlightAllColour(command.argb)
                        dirty = true
                    }

                    is Command.BellColour -> {
                        bellColour = command.argb
                        renderer?.setBellColour(command.argb)
                    }

                    is Command.Bell -> {
                        bellFlashUntil = System.currentTimeMillis() + BELL_FLASH_WINDOW_MS
                        renderer?.flashBell()
                        dirty = true
                    }

                    is Command.TransparentBackground -> {
                        transparentBackground = command.transparent
                        renderer?.transparentBackground = command.transparent
                        dirty = true
                    }

                    is Command.Background -> {
                        background = command.argb
                        renderer?.setBackground(command.argb)
                        dirty = true
                    }

                    is Command.Clear -> {
                        renderer?.clear(layout.size)
                        egl.swap()
                        dirty = true
                    }

                    is Command.Quit -> return
                }
            }

            // A cell change means the atlas's masks are the wrong size.
            if (cellChanged) {
                renderer?.onCellChanged()
            }

            // The one reflow, once the surface has been still.
            now = System.nanoTime() / 1_000_000
            layout.poll(now)?.let { change ->
                frameSource?.let { _ ->
                    onGridChange?.invoke(change.columns, change.rows)
                }
                renderer?.onGridChanged(change.size)
                layout.onGridApplied(change.columns, change.rows)
                dirty = true
                surfaceChanged = true
            }

            if (surfaceChanged) {
                renderer?.onSizeChanged(layout.size)
            }

            // The renderer's step follows the layout's size, whatever changed
            // it. A live pinch changes the cell and nothing else — no grid
            // change, no surface change — and the text has to be bigger on the
            // screen while the gesture runs.
            if (renderer != null && !sameStep(rendererCellSize, layout.size)) {
                rendererCellSize = layout.size
                renderer?.onGridChanged(layout.size)
            }

            // Nothing is drawn before the font has measured a cell and the
            // surface has a size: a frame from either before is a frame of the
            // wrong shape, and one of them is the black square at the origin.
            if (dirty && egl.isReady && egl.windowSurface != null && layout.cellReady) {
                dirty = false
                renderer?.focused = focused
                if (forceFullFrame) {
                    renderer?.forceFullFrame = true
                    forceFullFrame = false
                }
                drawFrame()
            }

            // The picture's shift is the loop's business, and a caller that
            // reads a tap against the grid needs it.
            scrollOffsetPx = layout.size.scrollOffsetPx

            if (!dirty) {
                awaitWork(now)
                // The wait ended because the blink interval ran out: the cursor
                // is drawn by this loop and the loop has just woken to flip its
                // phase, so a frame has to be produced. A wake that only waits
                // again leaves the cursor frozen in whichever half of the blink
                // the last frame happened to fall in — which looks like a cursor
                // that disappears and never comes back.
                dirty = true
            }
        }

        renderer?.release()
        renderer = null
        egl.release()
    }

    /**
     * The layout's size as the renderer last saw it.
     *
     * The renderer's step is the layout's step, and the layout's step changes
     * for reasons the grid does not: a live pinch makes the cell bigger
     * without changing the grid's shape.
     */
    private var rendererCellSize: SizeInfo? = null

    /** True when two sizes put the cells in the same places. */
    private fun sameStep(a: SizeInfo?, b: SizeInfo): Boolean {
        if (a == null) return false
        return a.stepX == b.stepX && a.stepY == b.stepY
    }

    /**
     * Told when the grid needs laying out, on the loop thread.
     *
     * The session is resized from here rather than from a caller: the loop is
     * the only thread that knows the grid and the surface agree.
     */
    var onGridChange: ((columns: Int, rows: Int) -> Unit)? = null

    private fun drawFrame() {
        val started = System.nanoTime()

        val current = renderer ?: FrameRenderer().also {
            // The font size the loop already knows, before the first frame is
            // built: a renderer created with no size rasterises at the cell's
            // height, which is the text-too-big case the field exists to avoid.
            it.fontPx = fontPx
            typeface?.let { face ->
                it.setTypeface(face)
                appliedTypeface = face
            }
            it.create()
            it.onSizeChanged(layout.size)
            // Everything the theme said before the renderer existed. A pane is
            // told its colours the moment it is built, and the first frame is
            // a moment after that: without this the new renderer would draw on
            // its own defaults.
            background?.let { argb -> it.setBackground(argb) }
            highlightColour?.let { argb -> it.setHighlightColour(argb) }
            highlightAllColour?.let { argb -> it.setHighlightAllColour(argb) }
            bellColour?.let { argb -> it.setBellColour(argb) }
            transparentBackground?.let { transparent -> it.transparentBackground = transparent }
            renderer = it
        }

        // The face can arrive after the renderer does — a surface that was
        // built before the first frame — and a host that measures its cell
        // with one face while the atlas rasterises with another is text that
        // does not fit its own advance. Ask every frame; it is a reference
        // comparison.
        val face = typeface
        if (face != null && face !== appliedTypeface) {
            current.setTypeface(face)
            appliedTypeface = face
        }

        val frame = fetchFrame()

        // The pictures are read after the update that produced them.
        //
        // They come from the same render state the frame does, and that state
        // is only complete once the frame's update has run: read before it, the
        // list is the previous frame's, and a picture a program just sent
        // arrives one frame late — which for a single still picture looks like
        // it never arrived at all.
        renderer?.setImages(imageSource?.invoke())

        // The snapshot is over. Everything past this point is the renderer's
        // own work and does not hold the terminal's lock — the split is what
        // the lock-shortening work attacks, and the number below is what says
        // whether it worked.
        val pumpEnd = System.nanoTime()

        // The selected cells, measured while the last frame was drawn.
        selectionBounds = renderer?.selectionBounds
        frame?.let {
            // The first few cells' colours, so a cell that should be coloured
            // and is not says so on the screen: the engine's answer and the
            // frame's answer are two different places to look, and which one
            // is wrong is the question.
            //
            // Built only when someone is listening: the sample is four
            // formatted strings, and a format is an allocation — sixty of them
            // a second is garbage the GC collects in the middle of a frame.
            val report = onFrameShape ?: return@let
            val sample = buildString {
                for (i in 0 until minOf(4, it.cellCount)) {
                    append(' ')
                    append("%06x/%06x".format(
                        it.background(i) and 0xffffff,
                        it.foreground(i) and 0xffffff,
                    ))
                }
            }
            report.invoke(
                it.columns,
                it.rows,
                it.cellCount,
                layout.size.stepX,
                layout.size.stepY,
                // The cursor's numbers, as the engine reported them. They are
                // on the screen because a cursor that is drawn in the wrong
                // place, or in the wrong colour, is a question the code cannot
                // answer by reading itself: the numbers have to be seen.
                it.cursorColumn,
                it.cursorRow,
                it.cursorStyle,
                it.cursorVisible,
                it.cursorColor,
                sample,
            )
        }
        val result = current.draw(frame, layout.size)

        // The renderer measured the selection as part of this frame. Only a
        // change is published: the chrome's flow compares rectangles by their
        // edges, and a fresh Rect with the same edges is still a new object.
        val bounds = current.selectionBounds
        val previous = publishedSelection
        if (!sameSelection(previous, bounds)) {
            publishedSelection = bounds
            onSelectionBounds?.invoke(bounds)
        }

        // Only a drawn frame is presented. A frame that drew nothing leaves
        // the previous picture alone, which is what stops the screen from
        // wiping to the clear colour and coming back.
        val swapped = result.cells > 0 && egl.swap()

        val total = System.nanoTime() - started
        onFrame(
            FrameTiming(
                totalNanos = total,
                drawNanos = total,
                cellsDrawn = result.cells,
                swapped = swapped,
            ),
        )

        // The measurement.
        //
        // The phases are laid out on the same clock as the frame: the snapshot
        // ended when it ended, and the renderer reports how long each of its
        // own phases took. The swap is counted with the draw, because a
        // present that blocks is a present the user waited for.
        val buildEnd = pumpEnd + result.buildNanos
        val uploadEnd = buildEnd + result.uploadNanos
        RenderBench.record(
            startNanos = started,
            pumpEndNanos = pumpEnd,
            buildEndNanos = buildEnd,
            uploadEndNanos = uploadEnd,
            drawEndNanos = System.nanoTime(),
            cells = result.cells,
            sprites = result.sprites,
            images = result.images,
            drawCalls = result.drawCalls,
            frameBytes = frame?.frameBytes ?: 0,
            atlasUploads = result.atlasUploads,
            dirty = frame?.dirty ?: 0,
            dirtyRows = result.dirtyRows,
        )?.let { line ->
            Log.i(BENCH_TAG, line)
            BenchmarkLog.sample(line)
        }
    }

    /**
     * The next frame, or null when there was none.
     *
     * The frame is written into this thread's own buffer, so nothing is
     * allocated per frame and nothing is copied on the Kotlin side. The buffer
     * is reused from frame to frame, and the frame is read and drawn before
     * the next one is asked for.
     */
    private fun fetchFrame(): dev.drosh.terminal.engine.protocol.Frame? {
        val source = frameSource ?: return null
        var buffer = frameBuffer ?: newFrameBuffer(INITIAL_FRAME_BYTES).also { frameBuffer = it }
        var written = source(buffer)
        if (written < 0) {
            // The frame does not fit: the grid is bigger than the guess. Make
            // a bigger buffer and ask again — once, because the engine's
            // answer is the only one that knows how big it is.
            buffer = newFrameBuffer(buffer.capacity() * 2)
            frameBuffer = buffer
            written = source(buffer)
            if (written < 0) {
                return null
            }
        }
        if (written <= 0) {
            return null
        }
        buffer.clear()
        buffer.limit(written)
        return dev.drosh.terminal.engine.protocol.Frame.parse(buffer, written)
    }

    /** A direct buffer, in the frame's byte order. */
    private fun newFrameBuffer(bytes: Int): java.nio.ByteBuffer =
        java.nio.ByteBuffer.allocateDirect(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)

    /** Two rectangles with the same edges are the same selection. */
    private fun sameSelection(a: android.graphics.Rect?, b: android.graphics.Rect?): Boolean {
        if (a == null || b == null) return a === b
        return a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom
    }

    private fun drainCommands(): List<Command> = lock.withLock {
        if (pending.isEmpty()) return emptyList()
        val out = ArrayList<Command>(pending.size)
        while (pending.isNotEmpty()) out.add(pending.removeFirst())
        out
    }

    /**
     * Wait for work, or for the blink interval.
     *
     * A condition variable rather than a sleep: the loop wakes the instant a
     * command is posted, so input is never delayed by a polling interval.
     */
    private fun awaitWork(now: Long) {
        lock.withLock {
            if (pending.isNotEmpty()) return
            val waitMillis = (layout.settlePending).let {
                if (it) {
                    LAYOUT_POLL_MS
                } else {
                    // The visual bell: the flash is over in a moment, and the
                    // frames have to keep coming for it to be seen.
                    val flashLeft = bellFlashUntil - System.currentTimeMillis()
                    if (flashLeft > 0) {
                        minOf(RenderClock.blinkWaitMillis(), BELL_FLASH_POLL_MS)
                    } else {
                        RenderClock.blinkWaitMillis()
                    }
                }
            }
            work.await(waitMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    private companion object {
        const val TAG = "RenderThread"

        /**
         * The tag the benchmark lines go out under.
         *
         * `adb logcat -s DroshBench` is one command and every number the
         * renderer produced is in it.
         */
        const val BENCH_TAG = "DroshBench"

        /** How long [stop] waits for the loop to finish its frame. */
        const val STOP_TIMEOUT_MS = 2_000L

        /** How often the loop wakes while a surface is settling. */
        const val LAYOUT_POLL_MS = 16L

        /** How long the bell's wash lasts, in milliseconds. */
        const val BELL_FLASH_WINDOW_MS = 400L

        /** How often the loop wakes while the bell's flash is on. */
        const val BELL_FLASH_POLL_MS = 16L

        /**
         * The first frame buffer's size, in bytes.
         *
         * Enough for a phone's grid — a hundred columns and sixty rows of
         * twenty-byte cells, with room to spare — so the buffer is made once
         * and then reused.
         */
        const val INITIAL_FRAME_BYTES = 128 * 1024
    }
}
