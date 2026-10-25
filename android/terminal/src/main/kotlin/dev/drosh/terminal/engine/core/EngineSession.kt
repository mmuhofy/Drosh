package dev.drosh.terminal.engine.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One terminal session: the core's handle plus the buffers Kotlin owns.
 *
 * Decision D5 — Kotlin allocates, Rust writes, the renderer reads: [cells] and
 * [stateArray] are handed to the native side and never copied.
 *
 * Threading: the render thread calls [pump]; the UI thread calls everything
 * else. Reallocating in [allocate] replaces the buffers (the renderer re-reads
 * [cells] every frame), and `handle`/sizes are volatile so both threads see the
 * same session description.
 */
class EngineSession(
    program: String = "/system/bin/sh",
    args: List<String> = emptyList(),
    env: Map<String, String> = emptyMap(),
    cwd: String? = null,
) {

    // Not `val`: the guest mode replaces the command and restarts the session
    // (Phase C) while the renderer keeps this same object and its buffers.
    private var program: String = program
    private var args: List<String> = args
    private var env: Map<String, String> = env
    private var cwd: String? = cwd

    /** Changes what [start] launches. Takes effect on the next [start]. */
    fun configure(
        program: String,
        args: List<String>,
        env: Map<String, String>,
        cwd: String?,
    ) {
        this.program = program
        this.args = args
        this.env = env
        this.cwd = cwd
    }

    @Volatile
    var handle: Long = NO_HANDLE
        private set

    /**
     * Change the cursor's shape of a running session.
     *
     * The fallback the terminal has not overridden: alacritty's own rule, and
     * the term's configuration can be changed while it runs, so the setting
     * takes effect without a new session.
     */
    fun applyCursorShape(shape: Int) {
        val current = handle
        if (current == NO_HANDLE) {
            return
        }
        NativeCore.nativeSetCursorShape(current, shape)
    }

    /**
     * The cursor's shape, when the terminal has not asked for one.
     *
     * alacritty's fallback (`TermConfig::default_cursor_style`): the
     * terminal's `DECSCUSR` wins, and this is what it falls back on. It is
     * fixed when the session starts, because the term's configuration is.
     */
    @Volatile
    var cursorShape: Int = NativeCore.CURSOR_BLOCK

    /** True while the core holds a live session for this wrapper. */
    val isAlive: Boolean get() = handle != NO_HANDLE

    @Volatile
    var columns = 0
        private set

    @Volatile
    var rows = 0
        private set

    @Volatile
    var cellWidth = 1
        private set

    @Volatile
    var cellHeight = 1
        private set

    @Volatile
    private var cellBuffer: ByteBuffer? = null

    private var damage = IntArray(0)
    private val state = IntArray(NativeCore.STATE_LEN)

    /**
     * The engine's colour overrides (`OSC 4/10/11/12`), `-1` for "default".
     * Written by [pump] on the render thread; [colorsStamp] says when the
     * contents changed, so the renderer installs the table only then.
     */
    val colors = IntArray(NativeCore.COLOR_COUNT)

    @Volatile
    var colorsStamp = 0
        private set

    private var colorsPulled = false

    val cells: ByteBuffer? get() = cellBuffer

    val stateArray: IntArray get() = state

    /** Version of the loaded core, for the log line. */
    val coreVersion: String get() = NativeCore.nativeVersion()

    /**
     * Where user input goes for a bridged session (the host's transport, e.g.
     * an SSH channel). `null` for a PTY session: input goes to the core.
     */
    @Volatile
    var inputSink: ((ByteArray) -> Unit)? = null

    /** Returns [NativeCore.OK] or a negative error code. */
    fun startBridged(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int): Int {
        close()
        this.cellWidth = cellWidth
        this.cellHeight = cellHeight

        val created = NativeCore.nativeCreateBridgedSession(columns, rows, cellWidth, cellHeight)
        if (created <= 0L) {
            return created.toInt()
        }

        handle = created
        colorsPulled = false
        allocate(columns, rows)
        return NativeCore.OK
    }

    /**
     * Attach an existing bridged session handle (created externally, e.g. by
     * the SSH factory) to this [EngineSession].
     *
     * The handle must already be a live bridged session from
     * [NativeCore.nativeCreateBridgedSession]. This does not create a new
     * session — it takes ownership of the existing one.
     */
    fun attachExistingHandle(
        handle: Long,
        columns: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
    ) {
        close()
        this.handle = handle
        this.cellWidth = cellWidth
        this.cellHeight = cellHeight
        this.columns = columns
        this.rows = rows
        colorsPulled = false
        allocate(columns, rows)
    }

    /** Returns [NativeCore.OK] or a negative error code. */
    fun start(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int): Int {
        close()
        this.cellWidth = cellWidth
        this.cellHeight = cellHeight

        val created = NativeCore.nativeCreateSession(
            columns,
            rows,
            cellWidth,
            cellHeight,
            program,
            args.joinToString("\n"),
            env.entries.joinToString("\n") { (key, value) -> "$key=$value" },
            cwd,
            cursorShape,
        )
        if (created <= 0L) {
            return created.toInt()
        }

        handle = created
        colorsPulled = false
        allocate(columns, rows)
        return NativeCore.OK
    }

    fun resize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        val current = handle
        if (current == NO_HANDLE) {
            return
        }
        if (
            columns == this.columns &&
            rows == this.rows &&
            cellWidth == this.cellWidth &&
            cellHeight == this.cellHeight
        ) {
            return
        }

        this.cellWidth = cellWidth
        this.cellHeight = cellHeight
        allocate(columns, rows)
        NativeCore.nativeResize(current, columns, rows, cellWidth, cellHeight)
    }

    private fun allocate(columns: Int, rows: Int) {
        this.columns = columns
        this.rows = rows
        cellBuffer = ByteBuffer
            .allocateDirect(columns * rows * NativeCore.CELL_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        damage = IntArray(NativeCore.damageLength(rows))
    }

    /**
     * Let the core write the damaged cells into [cells]; returns the damage
     * status ([NativeCore.DAMAGE_FULL] or the number of damaged lines), or a
     * negative code.
     *
     * The damage list is read through the accessors below, so the renderer never
     * sees the array itself.
     */
    fun pump(): Int {
        val current = handle
        val buffer = cellBuffer
        if (current == NO_HANDLE || buffer == null) {
            return NativeCore.INVALID_HANDLE
        }

        val written = NativeCore.nativeSnapshot(current, buffer, damage)
        if (written != NativeCore.OK) {
            return NativeCore.IO_ERROR
        }

        NativeCore.nativeState(current, state)

        // The table only changes when an application sets a colour, so the
        // fingerprint decides whether the 269-entry pull is worth doing.
        val stamp = state[NativeCore.STATE_COLORS]
        if (!colorsPulled || stamp != colorsStamp) {
            if (NativeCore.nativeColors(current, colors) == NativeCore.OK) {
                colorsStamp = stamp
                colorsPulled = true
            }
        }

        return damage[0]
    }

    /** Viewport row of damaged line [index]; only valid after [pump]. */
    fun damageRow(index: Int): Int = damage[1 + index * NativeCore.DAMAGE_STRIDE]

    /** First column that changed on damaged line [index]. */
    fun damageLeft(index: Int): Int = damage[2 + index * NativeCore.DAMAGE_STRIDE]

    /** Last column that changed on damaged line [index]. */
    fun damageRight(index: Int): Int = damage[3 + index * NativeCore.DAMAGE_STRIDE]

    /**
     * Bytes typed but not yet handed to the engine.
     *
     * The engine's channel is bounded: `write` blocks when it is full, and a
     * keystroke that blocks the UI thread is a terminal that freezes while it
     * types. Termux solved this the same way (`TerminalSession`'s
     * `mTerminalToProcessIOQueue`): the UI appends, a writer thread drains.
     */
    private val inputQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private val inputPending = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Append input and return at once.
     *
     * The writer only starts when there is something to write, so an idle
     * terminal costs nothing.
     */
    fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) {
            return
        }

        // A bridged session's input belongs to the host's transport.
        inputSink?.let { sink ->
            sink(bytes)
            return
        }

        inputQueue.add(bytes)
        if (inputPending.compareAndSet(false, true)) {
            jniExecutor.execute(::drainInput)
        }
    }

    /** Hand the queued bytes to the engine, oldest first. */
    private fun drainInput() {
        while (true) {
            val chunk = inputQueue.poll() ?: break
            val current = handle
            if (current == NO_HANDLE) {
                inputQueue.clear()
                break
            }
            NativeCore.nativeWrite(current, chunk)
        }
        inputPending.set(false)
        // A chunk that arrived between the last poll and the clear of the flag
        // would otherwise wait for the next keystroke.
        if (inputQueue.isNotEmpty() && inputPending.compareAndSet(false, true)) {
            jniExecutor.execute(::drainInput)
        }
    }

    /** Output bytes from the host's transport (a bridged session). */
    fun feed(bytes: ByteArray) {
        val current = handle
        if (current != NO_HANDLE && bytes.isNotEmpty()) {
            NativeCore.nativeFeed(current, bytes)
        }
    }

    fun writeUtf8(text: String) = write(text.toByteArray(Charsets.UTF_8))

    fun scroll(delta: Int) {
        val current = handle
        if (current != NO_HANDLE && delta != 0) {
            NativeCore.nativeScroll(current, delta)
        }
    }

    /**
     * Scroll by a finger distance, keeping the sub-line remainder.
     *
     * Kitty's model (`kitty/kitty/screen.c` — `scroll_by_pixels`): one
     * absolute position in pixels, split into whole rows for the engine and a
     * remainder for the renderer. The engine holds both, so a pan and the grid
     * cannot disagree about where the viewport is — which is what made a drag
     * step while the finger slid.
     *
     * `deltaPixels` is positive when the content should move down: the user
     * dragging back into the history. [cellHeight] is a row's height in the
     * same pixels.
     *
     * Returns the whole rows the viewport moved, negative toward the live edge.
     */
    fun scrollByPixels(deltaPixels: Float, cellHeight: Int): Int {
        val current = handle
        if (current == NO_HANDLE || cellHeight <= 0) {
            return 0
        }
        return NativeCore.nativeScrollByPixels(current, deltaPixels, cellHeight)
    }

    /** The sub-line part of the scroll, in pixels, as the engine holds it. */
    val subLinePixels: Int
        get() = stateArray[NativeCore.STATE_SUB_LINE_PX]

    /**
     * Put the viewport at `offset` rows above the live edge.
     *
     * [scroll] moves relative to where the viewport already is, which is what a
     * drag wants; this is absolute, which is what a zoom wants — the row the
     * fingers were on is an absolute row, and asking for it by delta would
     * carry the error from every size change that came before.
     *
     * The engine clamps to what the history still holds, so a request past
     * either end settles there rather than being refused.
     */
    fun scrollToOffset(offset: Int) {
        val current = handle
        if (current == NO_HANDLE) {
            return
        }
        val now = stateArray[NativeCore.STATE_DISPLAY_OFFSET]
        val delta = offset - now
        if (delta != 0) {
            NativeCore.nativeScroll(current, delta)
        }
    }

    fun pollWakeup(): Boolean {
        val current = handle
        return current != NO_HANDLE && NativeCore.nativePollWakeup(current)
    }

    /** Low-frequency engine events (title, bell, exit, …), one record per entry. */
    fun drainEvents(): List<String> {
        val current = handle
        if (current == NO_HANDLE) {
            return emptyList()
        }
        val raw = NativeCore.nativeDrainEvents(current)
        return if (raw.isEmpty()) emptyList() else raw.split('\n')
    }

    // ── Capabilities (P1) ─────────────────────────────────────────────────────

    /** Strip ANSI escape sequences from `data`. */
    fun stripAnsi(data: ByteArray): String {
        val current = handle
        if (current == NO_HANDLE) return ""
        return NativeCore.nativeStripAnsi(current, data)
    }

    /**
     * The last lines the session printed, oldest first.
     *
     * The engine keeps a bounded copy of the output as it arrives, because the
     * scrollback inside the grid cannot be read out of it. The lines come back
     * with the escape sequences stripped.
     */
    fun transcript(): List<String> {
        val current = handle
        if (current == NO_HANDLE) return emptyList()
        val text = NativeCore.nativeTranscript(current)
        return if (text.isEmpty()) emptyList() else text.split('\n')
    }

    /**
     * Where the shell is in its cycle, as the engine's marks report it.
     *
     * The boundaries come from the OSC 133 marks the engine taps where the
     * bytes enter, so the block engine does not have to diff the transcript to
     * find them.
     */
    fun blockState(): BlockState {
        val current = handle
        if (current == NO_HANDLE) return BlockState()
        return BlockState.parse(NativeCore.nativeBlockState(current))
    }

    /** Find `query` in the visible grid; each result is `row:col:len`. */
    fun search(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<Triple<Int, Int, Int>> {
        val current = handle
        if (current == NO_HANDLE) return emptyList()
        return EngineRecords.parseTriples(NativeCore.nativeSearch(current, query, limit))
    }

    /**
     * Find `query` in the session's output log.
     *
     * The log is the engine's bounded copy of the output, so this reaches
     * further back than the visible grid does — but the coordinates are the
     * log's: the index of the line and the column range within that line. A
     * line of the log is an output line, not a grid row, so a hit here cannot
     * be turned into a position on the screen.
     *
     * The match is case-insensitive, the way the app's own search is.
     *
     * At most [limit] matches are reported, oldest first: a log that holds
     * more matches than that has more than the limit, not exactly the limit.
     */
    fun searchTranscript(
        query: String,
        limit: Int = DEFAULT_TRANSCRIPT_SEARCH_LIMIT,
    ): List<TranscriptHit> {
        val current = handle
        if (current == NO_HANDLE) return emptyList()
        return TranscriptHit.parse(NativeCore.nativeSearchTranscript(current, query, limit))
    }

    /**
     * Find URLs on the visible grid.
     *
     * The scan is the engine's, because the grid is where the text is: a
     * caller that walked a transcript copy instead would have a second opinion
     * about where a URL starts.
     *
     * A URL the shell soft-wrapped across two rows is reported once per row,
     * and every row carries the whole link — a tap on any of them opens the
     * whole thing, not the fragment the terminal happened to break it at.
     */
    fun findUrls(limit: Int = 200): List<UrlHit> {
        val current = handle
        if (current == NO_HANDLE) return emptyList()
        return EngineRecords.parseUrlHits(NativeCore.nativeFindUrls(current, limit))
    }

    /** The URL at a visible (row, col), or "" when the cell is not inside one. */
    fun urlAt(row: Int, col: Int): String {
        val current = handle
        if (current == NO_HANDLE) return ""
        return NativeCore.nativeUrlAt(current, row, col)
    }

    /** Currently selected text, or "" when nothing is selected. */
    fun selectionText(): String {
        val current = handle
        if (current == NO_HANDLE) return ""
        return NativeCore.nativeSelectionText(current)
    }

    /** Select the word under (row, col); returns the selected text. */
    fun selectWord(row: Int, col: Int): String {
        val current = handle
        if (current == NO_HANDLE) return ""
        return NativeCore.nativeSelectWord(current, row, col)
    }

    /** Select the line under (row, col); returns the selected text. */
    fun selectLine(row: Int, col: Int): String {
        val current = handle
        if (current == NO_HANDLE) return ""
        return NativeCore.nativeSelectLine(current, row, col)
    }

    /** Clear any active selection. */
    fun clearSelection() {
        val current = handle
        if (current != NO_HANDLE) {
            NativeCore.nativeClearSelection(current)
        }
    }

    /**
     * The colours of the cells in a rectangle of the visible grid.
     *
     * The rectangle is clipped to the grid by the engine, so a region that
     * hangs over the edge reports the cells that are there. The rows are
     * viewport rows: row 0 is the top of the screen, whatever the viewport is
     * scrolled to.
     *
     * An empty list comes back when the session is gone.
     */
    fun cellColors(
        top: Int,
        left: Int,
        rows: Int,
        columns: Int,
    ): List<CellColor> {
        val current = handle
        if (current == NO_HANDLE) return emptyList()
        return CellColor.parse(
            NativeCore.nativeCellColors(current, top, left, rows, columns),
        )
    }

    fun close() {
        val current = handle
        if (current != NO_HANDLE) {
            NativeCore.nativeCloseSession(current)
            handle = NO_HANDLE
        }
    }

    companion object {
        /**
         * Where input is written from.
         *
         * One thread for the whole process: a terminal has one keyboard, and a
         * thread per session would be a thread per split pane for no gain. The
         * engine's own channel does the queueing after this; this only keeps
         * the blocking part off the UI thread.
         */
        private val jniExecutor: java.util.concurrent.Executor =
            java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "drosh-input").apply { isDaemon = true }
            }

        /**
         * Create a bridged session in the Rust engine core.
         *
         * Public wrapper for [NativeCore.nativeCreateBridgedSession], which is
         * internal to `:terminal`. Used by `:ssh` to create SSH-backed sessions.
         */
        fun createBridgedSession(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int): Long =
            NativeCore.nativeCreateBridgedSession(columns, rows, cellWidth, cellHeight)

        fun feedBridgedSession(handle: Long, data: ByteArray) {
            NativeCore.nativeFeed(handle, data)
        }

        fun closeBridgedSession(handle: Long) {
            NativeCore.nativeCloseSession(handle)
        }

        const val NO_HANDLE = 0L

        /** How many matches a grid search reports when the caller does not say. */
        const val DEFAULT_SEARCH_LIMIT = 200

        /**
         * How many matches a log search reports when the caller does not say.
         *
         * The log holds four thousand lines, so a query like "a" can match
         * thousands of times; the limit keeps one scan from building an
         * unbounded answer.
         */
        const val DEFAULT_TRANSCRIPT_SEARCH_LIMIT = 1000
    }
}
