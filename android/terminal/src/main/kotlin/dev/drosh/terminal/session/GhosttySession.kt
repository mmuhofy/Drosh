package dev.drosh.terminal.session

import dev.drosh.linux.GuestCommand
import dev.drosh.terminal.engine.native.NativePty
import dev.drosh.terminal.engine.native.NativeSession

/**
 * A live session: the terminal's grid and the process behind it.
 *
 * ## Why it is not the pane
 *
 * A pane is a view — a surface, a renderer, a place on screen. A session is
 * the thing that is *running*: the grid the shell writes into and the process
 * that writes it. They are not the same lifetime, and pretending they are was
 * the reason the session system never finished:
 *
 *  - a session that is not on screen had no renderer, so it had no preview
 *    and no output — it was a row in a table and nothing else;
 *  - switching sessions in the grid had nothing to switch *to*, because the
 *    session lived inside the pane and was destroyed with it.
 *
 * So the session owns the engine state, and the manager owns the sessions. A
 * pane binds to a session, and the grid shows every session that is alive —
 * running or not, on screen or not.
 *
 * ## What a session knows
 *
 * The grid: its cells, its colours, its cursor. The process: its PTY. Both are
 * the engine's, and both are reached through the same native handle, so the
 * frame a session produces is the frame that session is showing.
 */
class GhosttySession(
    /** The app's id for this session — the row in the session table. */
    val persistentId: String,
    /** The name the sidebar and the grid show. */
    var name: String,
) : RenderedSession {

    /** The engine's grid. Zero until [start]. */
    override var terminalHandle: Long = 0
        private set

    /** The process behind the grid. Zero until [start], and for a bridged one. */
    override var ptyHandle: Long = 0
        private set

    /** Where the session is in its life. */
    var state: EngineTerminalState = EngineTerminalState.IDLE
        private set

    /** Why the last start failed, or null when it did not. */
    var lastError: String? = null
        private set

    /** Told when the process exits, with its status. */
    var onExit: ((Int) -> Unit)? = null

    /** The cell the grid is measured in, in pixels. */
    override var cellWidth: Float = 1f
    override var cellHeight: Float = 1f
    override var fontPx: Float = 0f

    /**
     * The thread that reads the child's output into the grid.
     *
     * It belongs to the session and not to the pane: a session that is not on
     * screen keeps running and keeps its output, and that output is what the
     * grid's thumbnail is made of. A reader owned by the pane would stop the
     * moment the pane showed something else.
     */
    private var reader: Thread? = null
    private val reading = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The read buffer, reused. */
    private val readBuffer = ByteArray(64 * 1024)

    /** Bytes the child has produced since the session started. */
    @Volatile
    var bytesRead: Long = 0
        private set

    /**
     * Start the session: a grid and a process.
     *
     * The grid comes first — a PTY needs to know how many columns it has
     * before the child runs, or the first prompt wraps at eighty columns and is
     * resized a moment later.
     */
    fun start(
        command: GuestCommand,
        columns: Int,
        rows: Int,
        cellWidth: Float,
        cellHeight: Float,
    ): Boolean {
        if (state != EngineTerminalState.IDLE) return false
        lastError = null

        val grid = NativeSession.create(columns, rows)
        if (grid == 0L) {
            lastError = "the grid could not be created (${columns}x$rows)"
            return false
        }

        val launch = EngineLaunch.from(command)
        val pty = NativePty.spawn(
            argv = listOf(launch.program) + launch.args,
            environment = launch.env.toMap(),
            cwd = launch.cwd ?: "",
            columns = columns,
            rows = rows,
            cellWidth = cellWidth.toInt(),
            cellHeight = cellHeight.toInt(),
        )
        if (pty == 0L) {
            lastError = "the process could not be started (${launch.program})"
            NativeSession.free(grid)
            return false
        }

        this.cellWidth = cellWidth
        this.cellHeight = cellHeight
        terminalHandle = grid
        ptyHandle = pty
        state = EngineTerminalState.RUNNING
        startReading()
        return true
    }

    /**
     * Read the child's output into the grid, until the session ends.
     *
     * The engine's own parser does the work; this thread only moves bytes.
     * It runs for the life of the session, not for the life of a pane: a
     * session that is not on screen still has output, and the grid's
     * thumbnail is made of it.
     */
    private fun startReading() {
        if (!reading.compareAndSet(false, true)) return
        val pty = ptyHandle
        if (pty == 0L) {
            reading.set(false)
            return
        }
        reader = Thread({ readLoop(pty) }, "drosh-session-$persistentId").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop(pty: Long) {
        while (reading.get()) {
            val read = NativePty.read(pty, readBuffer)
            when {
                read > 0 -> {
                    bytesRead += read
                    feed(readBuffer, read)
                }
                // Nothing right now. The child's exit is the signal that this
                // is done, not an empty read.
                read == 0 -> {
                    val status = NativePty.tryWait(pty)
                    if (status >= 0) {
                        reading.set(false)
                        onExit?.invoke(status.toInt())
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

    /** Stop reading, without closing the process. */
    private fun stopReading() {
        reading.set(false)
        reader?.let { runCatching { it.join(READER_STOP_TIMEOUT_MS) } }
        reader = null
    }

    /**
     * Attach to a grid that already exists, with no process.
     *
     * An SSH session is bridged: the bytes come from the transport rather than
     * from a local child, so there is no PTY. The caller feeds the grid.
     */
    fun attachExisting(handle: Long, columns: Int = 0, rows: Int = 0): Boolean {
        if (state != EngineTerminalState.IDLE) return false
        terminalHandle = handle
        ptyHandle = 0
        state = EngineTerminalState.RUNNING
        return true
    }

    /** The frame the renderer draws, or null when there is nothing to draw. */
    fun frame(): dev.drosh.terminal.engine.protocol.Frame? {
        val handle = terminalHandle
        if (handle == 0L) return null
        return NativeSession.frame(handle)
    }

    /** Feed bytes to the parser — a bridged session's transport. */
    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        val handle = terminalHandle
        if (handle == 0L) return
        NativeSession.write(handle, bytes, length)
    }

    /** Write bytes to the child. */
    fun write(bytes: ByteArray) {
        val pty = ptyHandle
        if (pty == 0L) return
        NativePty.write(pty, bytes)
    }

    /** The grid's shape changed. */
    fun resize(columns: Int, rows: Int) {
        val grid = terminalHandle
        val pty = ptyHandle
        if (grid != 0L) {
            NativeSession.resize(grid, columns, rows, cellWidth.toInt(), cellHeight.toInt())
        }
        if (pty != 0L) {
            NativePty.resize(pty, columns, rows, cellWidth.toInt(), cellHeight.toInt())
        }
    }

    /**
     * The last lines the session printed, oldest first.
     *
     * The engine's own text — the grid and the scrollback, formatted the way
     * the engine formats them. It does not go through the render state, so the
     * preview ticker can read it while the render thread is drawing; a second
     * `render_state_update` on the same state would have the two fighting over
     * it.
     */
    override fun transcript(): List<String> {
        val handle = terminalHandle
        if (handle == 0L) return emptyList()
        val text = NativeSession.transcript(handle)
        if (text.isEmpty()) return emptyList()
        return text.split('\n')
    }

    /** Stop the process, keeping the grid. */
    fun stop() {
        stopReading()
        val pty = ptyHandle
        if (pty != 0L) {
            NativePty.close(pty)
            ptyHandle = 0
        }
        if (state == EngineTerminalState.RUNNING) {
            state = EngineTerminalState.IDLE
        }
    }

    /** Close the process and free the grid. */
    fun release() {
        if (state == EngineTerminalState.RELEASED) return
        stopReading()
        stop()
        val grid = terminalHandle
        if (grid != 0L) {
            NativeSession.free(grid)
            terminalHandle = 0
        }
        state = EngineTerminalState.RELEASED
    }

    private companion object {
        /** How long the reader waits before looking again. */
        const val IDLE_SLEEP_MS = 8L

        /** How long a reader is given to notice it should stop. */
        const val READER_STOP_TIMEOUT_MS = 500L
    }
}
