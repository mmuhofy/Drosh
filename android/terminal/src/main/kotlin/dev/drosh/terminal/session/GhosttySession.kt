package dev.drosh.terminal.session

import dev.drosh.linux.GuestCommand
import dev.drosh.terminal.engine.native.NativePty
import dev.drosh.terminal.engine.native.NativeSession
import java.io.File

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
) : RenderedSession, ShellSession {

    /** The engine's grid. Zero until [start]. */
    override var terminalHandle: Long = 0
        private set

    /** The process behind the grid. Zero until [start], and for a bridged one. */
    override var ptyHandle: Long = 0
        private set

    /** Where the session is in its life. */
    var state: PaneState = PaneState.IDLE
        private set

    /** Why the last start failed, or null when it did not. */
    var lastError: String? = null
        private set

    /** Told when the process exits, with its status. */
    var onExit: ((Int) -> Unit)? = null

    /**
     * The child's process id, or 0 while there is none.
     *
     * The kernel knows the child as this number, and the app's facts about the
     * session — its default name, its pid in the session table — are read from
     * the process rather than guessed.
     */
    var processId: Int = 0
        private set

    /**
     * Told the name of the process behind the session, once it is known.
     *
     * The session's default name is the process's own name, and only the
     * kernel can say what that is after the guest launcher has exec'd. The
     * receiver applies it; the session only reports what it read.
     */
    var onProcessName: ((String) -> Unit)? = null

    /** The grid's shape, in cells: what the engine is holding. */
    var columns: Int = 0
        private set
    var rows: Int = 0
        private set

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
        if (state != PaneState.IDLE) return false
        lastError = null

        val grid = NativeSession.create(columns, rows)
        this.columns = columns
        this.rows = rows
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
        state = PaneState.RUNNING
        startReading()
        // The child's own name is the session's default display name, and the
        // kernel only knows it under the child's pid — which exists now.
        val pid = NativePty.pid(pty)
        if (pid > 0) {
            processId = pid
            startProcessNameProbe(pid)
        }
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

    /**
     * Reads the child's process name and reports it as the session's default.
     *
     * The session's child is the guest launcher first and the shell only after
     * its exec, which lands a moment after the fork — so the first read of
     * `/proc/<pid>/comm` may answer `chroot-ng` where the shell's name is
     * wanted. The probe reads again on a short interval and reports as soon as
     * a read differs from the one before it, which is the exec having happened;
     * when the window closes with no transition it reports what it last saw,
     * because by then that is whatever the process really is.
     *
     * On its own short-lived thread: the read is a filesystem poll, and the
     * reader thread already has a job of its own.
     */
    private fun startProcessNameProbe(pid: Int) {
        val report = onProcessName ?: return
        Thread({
            var previous: String? = null
            var latest: String? = null
            var attempt = 0
            while (attempt < PROCESS_NAME_ATTEMPTS) {
                val read = readProcessName(pid) ?: return@Thread
                latest = read
                if (previous != null && read != previous) break
                previous = read
                attempt += 1
                if (attempt < PROCESS_NAME_ATTEMPTS) {
                    runCatching { Thread.sleep(PROCESS_NAME_RETRY_MS) }
                }
            }
            val resolved = latest
            if (!resolved.isNullOrEmpty()) report(resolved)
        }, "drosh-session-name-$persistentId").apply {
            isDaemon = true
            start()
        }
    }

    /** The child's name, as the kernel knows it, or null when it cannot be read. */
    private fun readProcessName(pid: Int): String? =
        runCatching {
            File("/proc/$pid/comm").readText().trim()
        }.getOrNull()?.takeIf { it.isNotEmpty() }

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
        if (state != PaneState.IDLE) return false
        this.columns = columns
        this.rows = rows
        terminalHandle = handle
        ptyHandle = 0
        state = PaneState.RUNNING
        return true
    }

    /**
     * The shell's state, as the engine reports it.
     *
     * The engine taps the shell's marks where the bytes enter, so it knows the
     * phase and the directory without anyone reading the screen. The editor
     * target is not among them yet: the engine reports it as absent rather
     * than as a guess.
     */
    override fun shellState(): dev.drosh.domain.block.ShellState? {
        val handle = terminalHandle
        if (handle == 0L) return null
        val phase = dev.drosh.terminal.engine.shell.Phase.of(NativeSession.shellPhase(handle))
        val directory = NativeSession.workingDirectory(handle).takeIf { it.isNotBlank() }
        val shellPhase = phase.toShellPhase()
        if (shellPhase == dev.drosh.domain.block.ShellPhase.UNKNOWN && directory == null) {
            return null
        }
        return dev.drosh.domain.block.ShellState(
            phase = shellPhase,
            workingDirectory = directory,
            editorTarget = editorTarget(),
        )
    }

    /** The file the shell last asked the editor to open, or null. */
    override fun editorTarget(): String? = null

    /** The frame the renderer draws, or null when there is nothing to draw. */
    fun frame(): dev.drosh.terminal.engine.protocol.Frame? {
        val handle = terminalHandle
        if (handle == 0L) return null
        pollTitle(handle)
        return NativeSession.frame(handle)
    }

    /**
     * The title the program asked for, with `OSC 0` or `OSC 2`.
     *
     * A terminal's window title follows the program: `vim` puts the file name
     * there, a shell puts the directory. It is the program's title and not the
     * session's name — the user named the session, and the program does not get
     * to rename it.
     */
    @Volatile
    var title: String? = null
        private set

    /** Told when the program's title changes. */
    var onTitle: ((String?) -> Unit)? = null

    private var lastTitlePollAt: Long = 0L

    /**
     * Reads the title, now and then.
     *
     * The title changes when the program says so, which is not often, so it is
     * asked for twice a second rather than on every frame.
     */
    private fun pollTitle(handle: Long) {
        val now = System.currentTimeMillis()
        if (now - lastTitlePollAt < TITLE_POLL_MILLIS) return
        lastTitlePollAt = now
        val read = NativeSession.title(handle)?.takeIf { it.isNotBlank() }
        if (read == title) return
        title = read
        onTitle?.invoke(read)
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
        this.columns = columns
        this.rows = rows
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

    /**
     * A match in what the session has printed.
     *
     * The coordinates are the engine's text coordinates, not pixels: [row] is
     * the index of the line in [transcript] — the grid and its scrollback,
     * oldest line first, one line per grid row — and [column] and [length] are
     * the character column the match starts at and how many characters it
     * covers.
     */
    data class Match(
        /** The line of the transcript, which is also the grid row. */
        val row: Int,
        /** The character column the match starts at, within the line. */
        val column: Int,
        /** How many characters the match covers. */
        val length: Int,
        /** The text that matched, quoted back for the find bar. */
        val text: String,
    )

    /**
     * The matches of [query] in everything the session has printed.
     *
     * ## Why the transcript and not the engine's search
     *
     * The engine's own search answers in *viewport* coordinates: it reports
     * only what shares a page with the visible rows, so it can never reach the
     * output that has scrolled past — which is most of what a find bar is for.
     * Its rows are screen rows to boot, so they do not map onto the scrollbar's
     * row space and cannot be scrolled to.
     *
     * The transcript is the engine's own text of the whole record, and its
     * lines are the engine's rows, oldest first. A plain substring pass over it
     * therefore finds the output wherever it is, and its line numbers are the
     * rows the viewport can be scrolled to.
     *
     * Matching is case-insensitive, the way the engine's own matching is; each
     * match carries its text so the bar can quote what it found.
     *
     * @param limit How many matches to report; the scan stops there.
     */
    fun search(query: String, limit: Int = SEARCH_LIMIT): List<Match> {
        if (query.isEmpty() || limit <= 0) return emptyList()
        val handle = terminalHandle
        if (handle == 0L) return emptyList()
        val text = NativeSession.transcript(handle)
        if (text.isEmpty()) return emptyList()
        return scan(text, query, limit)
    }

    /**
     * A line-by-line, case-insensitive scan of [text] for [query].
     *
     * The next match is found once and carried forward rather than re-found
     * from the start of every line, so the whole scan is one pass. A match that
     * runs past the end of a line is skipped: a line is a row of the grid, and
     * a match split across two rows is not what the user typed. The scan stops
     * at [limit] matches.
     */
    private fun scan(text: String, query: String, limit: Int): List<Match> {
        val matches = ArrayList<Match>()
        val length = text.length
        var row = 0
        var lineStart = 0
        var index = text.indexOf(query, 0, ignoreCase = true)
        while (lineStart <= length && matches.size < limit) {
            val newline = text.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0) length else newline
            while (index in lineStart until lineEnd && matches.size < limit) {
                if (index + query.length <= lineEnd) {
                    matches.add(
                        Match(
                            row = row,
                            column = index - lineStart,
                            length = query.length,
                            text = text.substring(index, index + query.length),
                        ),
                    )
                }
                index = text.indexOf(query, index + 1, ignoreCase = true)
            }
            row += 1
            lineStart = lineEnd + 1
        }
        return matches
    }

    /** Stop the process, keeping the grid. */
    fun stop() {
        stopReading()
        val pty = ptyHandle
        if (pty != 0L) {
            NativePty.close(pty)
            ptyHandle = 0
        }
        if (state == PaneState.RUNNING) {
            state = PaneState.IDLE
        }
    }

    /** Close the process and free the grid. */
    fun release() {
        if (state == PaneState.RELEASED) return
        stopReading()
        stop()
        val grid = terminalHandle
        if (grid != 0L) {
            NativeSession.free(grid)
            terminalHandle = 0
        }
        state = PaneState.RELEASED
    }

    private companion object {
        /** How often the program's title is read, in milliseconds. */
        const val TITLE_POLL_MILLIS = 500L
        /** How long the reader waits before looking again. */
        const val IDLE_SLEEP_MS = 8L

        /** How long a reader is given to notice it should stop. */
        const val READER_STOP_TIMEOUT_MS = 500L

        /**
         * How many times the process-name probe reads `/proc`, and how long it
         * waits between reads.
         *
         * Together they are the window the guest launcher's exec is given to
         * land in — about half a second, against an exec that normally takes
         * milliseconds. A probe that never sees a change reports what it read
         * last rather than nothing, because after that long the name is what
         * the process really is.
         */
        const val PROCESS_NAME_ATTEMPTS = 10
        const val PROCESS_NAME_RETRY_MS = 50L

        /**
         * How many matches one scan reports.
         *
         * A query like "e" matches thousands of times; the limit keeps one scan
         * from building an unbounded answer, and a find bar only ever walks
         * from one match to the next.
         */
        const val SEARCH_LIMIT = 10_000
    }
}

/**
 * The domain's phase for the engine's.
 *
 * The engine's phase is a number it read from the shell's marks; the domain
 * speaks in names. The translation is where the engine's own vocabulary
 * becomes the domain's, and it is a pure function so that it can be checked
 * without an engine and without a device.
 */
internal fun dev.drosh.terminal.engine.shell.Phase.toShellPhase():
    dev.drosh.domain.block.ShellPhase = when (this) {
    dev.drosh.terminal.engine.shell.Phase.PROMPT -> dev.drosh.domain.block.ShellPhase.PROMPT
    dev.drosh.terminal.engine.shell.Phase.TYPING -> dev.drosh.domain.block.ShellPhase.TYPING
    dev.drosh.terminal.engine.shell.Phase.RUNNING -> dev.drosh.domain.block.ShellPhase.RUNNING
    dev.drosh.terminal.engine.shell.Phase.FINISHED -> dev.drosh.domain.block.ShellPhase.FINISHED
    else -> dev.drosh.domain.block.ShellPhase.UNKNOWN
}
