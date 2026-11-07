package dev.drosh.terminal.engine.render

import android.util.Log
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

        /** The font's cell size changed. */
        data class Cell(val width: Float, val height: Float) : Command

        /** A live pinch, in progress. */
        data class Pinch(val scale: Float, val focusX: Float, val focusY: Float) : Command

        /** The sub-line scroll remainder, in pixels. */
        data class ScrollOffset(val pixels: Float) : Command

        /** Redraw without changing anything — new output arrived. */
        data object Invalidate : Command

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
     * Where the cells come from. Set before [start], cleared after [stop].
     *
     * A function rather than an object so the loop does not need to know what a
     * session is; it asks for bytes and draws them.
     */
    @Volatile
    var frameSource: (() -> dev.drosh.terminal.engine.protocol.Frame?)? = null

    /** The renderer's own GL state, created on the loop thread. */
    private var renderer: FrameRenderer? = null

    /** Why the renderer came up empty, or null when it drew. */
    @Volatile
    val rendererFailure: String? get() = renderer?.failure

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
                        layout.onCell(command.width, command.height, now)
                        cellChanged = true
                    }

                    is Command.Pinch -> {
                        layout.onPinch(command.scale, command.focusX, command.focusY)
                        dirty = true
                    }

                    is Command.ScrollOffset -> {
                        layout.onScrollOffset(command.pixels)
                        dirty = true
                    }

                    is Command.Invalidate -> dirty = true

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

            if (dirty && egl.isReady && egl.windowSurface != null) {
                dirty = false
                drawFrame()
            }

            if (!dirty) {
                awaitWork(now)
            }
        }

        renderer?.release()
        renderer = null
        egl.release()
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
            it.create()
            it.onSizeChanged(layout.size)
            renderer = it
        }

        val frame = frameSource?.invoke()
        val cellsDrawn = current.draw(frame, layout.size)

        // Only a drawn frame is presented. A frame that drew nothing leaves
        // the previous picture alone, which is what stops the screen from
        // wiping to the clear colour and coming back.
        val swapped = cellsDrawn > 0 && egl.swap()

        val total = System.nanoTime() - started
        onFrame(FrameTiming(totalNanos = total, drawNanos = total, cellsDrawn = cellsDrawn, swapped = swapped))
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
                if (it) LAYOUT_POLL_MS else BLINK_INTERVAL_MS
            }
            work.await(waitMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    private companion object {
        const val TAG = "RenderThread"

        /** How long [stop] waits for the loop to finish its frame. */
        const val STOP_TIMEOUT_MS = 2_000L

        /** How often the loop wakes while a surface is settling. */
        const val LAYOUT_POLL_MS = 16L

        /** How often the loop wakes to blink the cursor when nothing else does. */
        const val BLINK_INTERVAL_MS = 500L
    }
}
