package dev.drosh.terminal.engine.surface

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.drosh.terminal.engine.layout.SizeInfo
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

    /** The reader thread, while a child is attached. */
    private var reader: Thread? = null

    private val reading = AtomicBoolean(false)

    /** The read buffer, reused. */
    private val readBuffer = ByteArray(64 * 1024)

    /**
     * The render loop.
     *
     * Created here so the surface and the loop have the same lifetime: a loop
     * without a surface draws into nothing, and a surface without a loop is a
     * black rectangle.
     */
    private val renderThread = RenderThread()

    /** Told when the child exits, with its status. */
    var onExit: ((Int) -> Unit)? = null

    /** Told when the grid's shape changed, so the PTY can be resized too. */
    var onGridChanged: ((columns: Int, rows: Int) -> Unit)? = null

    init {
        holder.addCallback(this)
        // The surface draws its own pixels; the platform must not clear it to
        // the window background between frames.
        holder.setFormat(android.graphics.PixelFormat.OPAQUE)
        setZOrderOnTop(false)

        renderThread.frameSource = { frame() }
        renderThread.onGridChange = { columns, rows ->
            // The grid and the PTY's idea of the window have to agree, or a
            // program lays its output out for a shape the screen does not have.
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
        renderThread.invalidate()
        startReading()
    }

    /** Detach both, without freeing them: the host owns their lifetime. */
    fun detach() {
        stopReading()
        ptyHandle = 0
        sessionHandle = 0
    }

    /** Tell the loop the font's cell changed. */
    fun setCell(width: Float, height: Float) {
        cellWidth = width
        cellHeight = height
        renderThread.post(RenderThread.Command.Cell(width, height))
    }

    /** A live pinch. The grid does not move; the glyphs do. */
    fun pinch(scale: Float, focusX: Float, focusY: Float) {
        renderThread.post(RenderThread.Command.Pinch(scale, focusX, focusY))
    }

    /** The sub-line scroll remainder, in pixels. */
    fun scrollOffset(pixels: Float) {
        renderThread.post(RenderThread.Command.ScrollOffset(pixels))
    }

    /** Scroll by rows; negative is up. */
    fun scroll(rows: Int) {
        val session = sessionHandle
        if (session == 0L) return
        NativeSession.scroll(session, NativeSession.SCROLL_DELTA, rows)
        renderThread.invalidate()
    }

    /** Write bytes to the child, e.g. a key sequence. */
    fun write(bytes: ByteArray) {
        val pty = ptyHandle
        if (pty == 0L) return
        NativePty.write(pty, bytes)
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
        /** How long the reader waits when the child has nothing to say. */
        const val IDLE_SLEEP_MS = 8L

        /** How long [stopReading] waits for the reader to notice. */
        const val STOP_TIMEOUT_MS = 500L
    }
}
