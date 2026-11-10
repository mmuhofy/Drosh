package dev.drosh.terminal.session

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.View
import dev.drosh.linux.GuestCommand
import dev.drosh.terminal.engine.native.NativeLibraries
import dev.drosh.terminal.engine.native.NativeSession
import dev.drosh.terminal.engine.surface.TerminalSurface

/**
 * One terminal on the Ghostty engine: the host-facing half.
 *
 * ## Why this type exists
 *
 * The same reason [EngineTerminal] does — the host must not have to know which
 * engine it is holding. Both present the same interface, so the manager, the
 * panes and the Compose screens are written once.
 *
 * ## What it adds to the surface
 *
 * [TerminalSurface] is the view: it draws and reads. What it cannot know is
 * product policy — starting a guest, deciding when the session is over, telling
 * the host the shell's phase. That is here.
 *
 * ## The two native handles
 *
 * A terminal is a grid and a process. [NativeSession] owns the grid,
 * [NativePty] owns the process, and this class is the only thing that knows
 * both exist. The surface gets both because a resize has to reach the process
 * and a frame comes from the grid.
 */
class GhosttyTerminal(context: Context) : TerminalEngine {

    /** The view, as the host sees it. */
    override val view: TerminalSurface = TerminalSurface(context)

    /**
     * The session this pane is showing.
     *
     * The pane is a view; the session is what runs. The manager owns the
     * sessions, so a session keeps running and keeps its output while another
     * one is on screen — which is what the grid's thumbnails are made of.
     */
    @Volatile
    var session: GhosttySession? = null
        private set


    /** The grid. Zero until [start]. */
    @Volatile
    var sessionHandle: Long = 0
        private set

    /** The process. Zero until [start], and for a bridged session. */
    @Volatile
    var ptyHandle: Long = 0
        private set

    /** Where the pane is in its lifecycle. */
    override var state: PaneState = PaneState.IDLE
        private set

    /**
     * Why the last start failed, or null when it did not.
     *
     * A start fails in four places — the state, the library load, the grid, the
     * process — and "did not start" is not an answer to any of them.
     */
    var lastError: String? = null
        private set

    /** The persistent id of the session this terminal runs. */
    override var persistentId: String = ""

    /** The name the sidebar shows. */
    override var sessionName: String = ""

    /** Told when the child exits, with its status. */
    var onSessionExited: ((Int) -> Unit)? = null

    /**
     * The manager's exit callback — the other ear of the same fork.
     *
     * [onSessionExited] is the host's UI listener; this one is the manager's
     * bookkeeping, and it is attached at registration time. A session's child
     * dying otherwise leaves the manager holding a row for a session no
     * process backs.
     */
    override var onSessionExitedCallback: ((Int) -> Unit)? = null

    /** Told when the shell's phase changes. */
    var onShellPhase: ((dev.drosh.terminal.engine.shell.Phase) -> Unit)? = null

    /** The cell size the font measured, in pixels. */
    override var cellWidth: Float = 1f
    override var cellHeight: Float = 1f

    init {
        view.onExit = { status ->
            state = state.exited()
            onSessionExited?.invoke(status)
            onSessionExitedCallback?.invoke(status)
        }
        view.onGridChanged = { _, _ -> }
    }

    // --- identity ----------------------------------------------------------

    override fun bindIdentity(persistentId: String, name: String) {
        this.persistentId = persistentId
        this.sessionName = name
    }

    override fun rebind(persistentId: String, name: String) {
        if (this.persistentId == persistentId) {
            this.sessionName = name
            return
        }
        stop()
        this.persistentId = persistentId
        this.sessionName = name
    }

    // --- lifecycle ---------------------------------------------------------

    /**
     * Start a session that runs [command] inside the guest.
     *
     * The grid is created first — a PTY needs to know how many columns it has
     * before the child runs, or the first prompt wraps at 80 columns and is
     * resized a moment later.
     */
    /**
     * Show a session in this pane.
     *
     * The surface follows the session: it draws that session's grid and writes
     * that session's process. The session itself is untouched — it keeps
     * running and keeps its output — so the grid can show it again later.
     */
    override fun showSession(session: RenderedSession) {
        this.session = session as? GhosttySession
        sessionHandle = session.terminalHandle
        ptyHandle = session.ptyHandle
        cellWidth = session.cellWidth
        cellHeight = session.cellHeight
        view.attach(session.terminalHandle, session.ptyHandle, session.cellWidth, session.cellHeight)
    }

    override fun start(
        command: GuestCommand,
        columns: Int,
        rows: Int,
        cellWidth: Float,
        cellHeight: Float,
    ): Boolean {
        // The pane does not own a session any more: the manager opens them, and
        // a session outlives the pane showing it. This path exists for the
        // interface's sake — a caller that has no manager opens a session
        // directly, and it is registered as this pane's own.
        val cols = if (columns > 0) columns else DEFAULT_COLUMNS
        val rowCount = if (rows > 0) rows else DEFAULT_ROWS
        if (!state.canStart) {
            lastError = "the terminal is $state"
            Log.w(TAG, "start refused: ${lastError ?: "no reason"}")
            return false
        }
        if (!NativeLibraries.ensureLoaded()) {
            lastError = "the libraries did not load: ${NativeLibraries.failure ?: "no reason"}"
            Log.e(TAG, lastError ?: "no reason")
            return false
        }

        val opened = GhosttySession(
            persistentId = persistentId.ifEmpty { "local" },
            name = sessionName,
        )
        if (!opened.start(command, cols, rowCount, cellWidth, cellHeight)) {
            lastError = opened.lastError
            Log.e(TAG, "the session could not be started: ${lastError ?: "no reason"}")
            return false
        }
        showSession(opened)
        state = state.started()
        view.post { view.requestFocus(); view.requestKeyboard() }
        return true
    }

    /**
     * Attach a grid that already exists, with no process.
     *
     * An SSH session is bridged: the bytes come from the transport rather than
     * a local child, so there is no PTY. The caller feeds the grid itself.
     */
    override fun attachExisting(
        handle: Long,
        columns: Int,
        rows: Int,
        cellWidth: Float,
        cellHeight: Float,
    ): Boolean {
        if (!state.canStart) return false
        if (!NativeLibraries.ensureLoaded()) return false

        this.cellWidth = cellWidth
        this.cellHeight = cellHeight

        // A bridged session: a grid that exists, with no process behind it. It
        // is a session like any other — the transport feeds it.
        val bridged = GhosttySession(
            persistentId = persistentId.ifEmpty { "bridged" },
            name = sessionName,
        )
        if (!bridged.attachExisting(handle, columns, rows)) return false
        showSession(bridged)
        state = state.started()
        return true
    }

    /** Feed bytes to the grid. Used by a bridged session's transport. */
    override fun feed(bytes: ByteArray, length: Int) {
        val shown = session ?: return
        shown.feed(bytes, length)
        view.invalidate()
    }

    /** Write bytes to the child. Used by the keyboard and the paste path. */
    override fun write(bytes: ByteArray) {
        view.write(bytes)
    }

    /**
     * Paste the platform clipboard into the child.
     *
     * The shell's bracketed-paste mode (`2004`) is not surfaced by the engine's
     * modes accessor — that carries mouse tracking and the kitty flags only —
     * so this writes the text plain, truncated to the same limit the other
     * engine applies. A shell that asked for the bracket gets no echo-off
     * guard against an accidental Enter; it has everything to gain from a
     * paste that works at all.
     */
    override fun pasteClipboard() {
        val clipboard =
            view.context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(view.context)?.toString().orEmpty()
        if (text.isEmpty()) {
            return
        }

        if (text.length > PASTE_LIMIT) {
            Log.w(TAG, "paste truncated to $PASTE_LIMIT characters")
        }
        write(text.take(PASTE_LIMIT).toByteArray(Charsets.UTF_8))
    }

    /**
     * Stop showing this session, keeping the view alive.
     *
     * The session itself is not stopped: it belongs to the manager, it may be
     * on screen in another pane, and it keeps its output either way. The pane
     * only lets go of it.
     */
    override fun stop() {
        if (!state.canStop) return
        view.detach()
        session = null
        sessionHandle = 0
        ptyHandle = 0
        state = state.stopped()
    }

    /**
     * Release: the view is dead and the pane lets go of its session.
     *
     * The session is not closed here either — the manager owns it and closes it
     * when the app is done with it.
     */
    override fun release() {
        if (state == PaneState.RELEASED) return
        stop()
        state = state.released()
    }

    // --- appearance --------------------------------------------------------

    /** The font's cell changed: the grid has to reflow. */
    override fun setCell(width: Float, height: Float) {
        cellWidth = width
        cellHeight = height
        view.setCell(width, height)
    }

    /** The cell and the font's own size, when the caller knows both. */
    fun setCell(width: Float, height: Float, fontPx: Float) {
        cellWidth = width
        cellHeight = height
        view.setCell(width, height, fontPx)
    }

    override fun setTextSize(sp: Float) {
        // The view carries the size for the pinch's origin; the cell itself is
        // measured by the host and arrives through `setCell`.
        view.textSizeSp = sp
    }

    override fun applyAppearance(config: dev.drosh.domain.settings.TerminalConfig) {
        // Colours are the engine's: the palette comes from the frame.
    }

    override fun setCustomFont(bytes: ByteArray?): Boolean = false

    // --- input -------------------------------------------------------------

    /** The IME target. The surface is its own editor. */
    override val imeTarget: View get() = view

    override fun focus() {
        view.requestFocus()
        view.requestKeyboard()
    }

    override fun hasViewFocus(): Boolean = view.hasFocus()

    override fun onHostResume() {}

    override fun onHostPause() {}

    // --- host queries ------------------------------------------------------

    /** The rows a prompt started on, as rows above the live edge. */
    override fun promptRows(): IntArray = IntArray(0)

    /** How much history there is above the viewport, in rows. */
    override fun historySize(): Int {
        val shown = session ?: return 0
        return NativeSession.scrollbar(shown.terminalHandle)?.historyRows ?: 0
    }

    /** The viewport's position, in rows above the live edge. */
    override fun scrollOffset(): Int {
        val shown = session ?: return 0
        val bar = NativeSession.scrollbar(shown.terminalHandle) ?: return 0
        return bar.historyRows - (bar.offset.coerceAtMost(bar.historyRows))
    }

    /**
     * Scroll so [offset] rows of history sit above the viewport.
     *
     * The engine's row space runs from the oldest row (0) to the live edge
     * (`historyRows`), so "how far above the live edge" is the other way round
     * in it: the row to show is `historyRows - offset`.
     */
    override fun scrollTo(offset: Int) {
        val shown = session ?: return
        val bar = NativeSession.scrollbar(shown.terminalHandle) ?: return
        val row = (bar.historyRows - offset).coerceIn(0, bar.historyRows)
        NativeSession.scroll(shown.terminalHandle, NativeSession.SCROLL_ROW, row)
        view.invalidate()
    }

    /** The shell's phase, as its `OSC 133` marks report it. */
    override fun shellPhase(): dev.drosh.terminal.engine.shell.Phase {
        val shown = session ?: return dev.drosh.terminal.engine.shell.Phase.UNKNOWN
        return dev.drosh.terminal.engine.shell.Phase.of(NativeSession.shellPhase(shown.terminalHandle))
    }

    /** The working directory the shell last reported. */
    override fun workingDirectory(): String {
        val shown = session ?: return ""
        return NativeSession.workingDirectory(shown.terminalHandle)
    }

    /** True when the alternate screen is on: a program, not a shell. */
    override fun isAltBufferActive(): Boolean {
        val shown = session ?: return false
        return NativeSession.alternateScreen(shown.terminalHandle)
    }

    /** The engine's mode bits, or 0. */
    override fun modes(): Int {
        val shown = session ?: return 0
        return NativeSession.modes(shown.terminalHandle)
    }

    // --- selection ---------------------------------------------------------

    override fun selectionText(): String = view.selectedText()

    override fun hasSelection(): Boolean = view.hasSelection()

    override fun copySelection(): Boolean = view.copySelection()

    override fun selectWord(row: Int, col: Int): Boolean = view.selectWord(row, col)

    override fun selectLine(row: Int, col: Int): Boolean = view.selectLine(row, col)

    override fun selectAll(): Boolean = view.selectAll()

    override fun clearSelection() = view.clearSelection()

    override fun selectionBounds(): Rect? = view.selectionBounds()

    // --- find --------------------------------------------------------------

    override val columns: Int
        get() = session?.columns ?: 0

    override val rows: Int
        get() = session?.rows ?: 0

    override val cellWidth: Float
        get() = cellWidth

    override val cellHeight: Float
        get() = cellHeight

    override fun cellColors(
        firstRow: Int,
        firstColumn: Int,
        rows: Int,
        columns: Int,
    ): IntArray {
        val shown = session ?: return IntArray(0)
        return NativeSession.cellColors(
            shown.terminalHandle,
            firstRow,
            firstColumn,
            rows,
            columns,
        )
    }

    override fun find(query: String, limit: Int): List<Triple<Int, Int, Int>> {
        val shown = session ?: return emptyList()
        return NativeSession.search(shown.terminalHandle, query, limit)
    }

    /**
     * The last lines the session printed, oldest first.
     *
     * The visible viewport, taken from a fresh dump of the grid: the rows a
     * thumbnail actually draws. Scrollback is not included — the grid does
     * not hand it out, and a caller wanting history joins from the log.
     */
    override fun transcript(): List<String> {
        val shown = session ?: return emptyList()
        return shown.transcript()
    }

    /** Not carried by this engine: `OSC 8` has no C API surface yet. */
    override fun hyperlinkAt(row: Int, column: Int): String? = null

    private companion object {
        const val TAG = "GhosttyTerminal"
        const val FIND_LIMIT = 200

        /** The grid a PTY starts with when the caller has not measured one. */
        const val DEFAULT_COLUMNS = 80
        const val DEFAULT_ROWS = 24

        const val PASTE_LIMIT = 65_536
    }
}
