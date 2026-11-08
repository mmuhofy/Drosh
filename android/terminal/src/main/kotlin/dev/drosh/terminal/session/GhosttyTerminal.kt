package dev.drosh.terminal.session

import android.content.Context
import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.View
import dev.drosh.linux.GuestCommand
import dev.drosh.terminal.engine.native.NativeLibraries
import dev.drosh.terminal.engine.native.NativePty
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

    /** The grid. Zero until [start]. */
    @Volatile
    var sessionHandle: Long = 0
        private set

    /** The process. Zero until [start], and for a bridged session. */
    @Volatile
    var ptyHandle: Long = 0
        private set

    /** Where the pane is in its lifecycle. */
    override var state: EngineTerminalState = EngineTerminalState.IDLE
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
    private var cellWidth = 1f
    private var cellHeight = 1f

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
    override fun start(
        command: GuestCommand,
        columns: Int,
        rows: Int,
        cellWidth: Float,
        cellHeight: Float,
    ): Boolean {
        // A PTY has to know its size before the child runs, or the first prompt
        // wraps at 80 columns and is resized a moment later.
        val columns = if (columns > 0) columns else DEFAULT_COLUMNS
        val rows = if (rows > 0) rows else DEFAULT_ROWS
        lastError = null
        if (!state.canStart) {
            // A nullable property cannot be handed to Log; the local is the
            // same string the caller will see.
            val reason = "the terminal is $state"
            lastError = reason
            Log.w(TAG, "start refused: $reason")
            return false
        }
        if (!NativeLibraries.ensureLoaded()) {
            val reason = "the libraries did not load: ${NativeLibraries.failure ?: "no reason"}"
            lastError = reason
            Log.e(TAG, reason)
            return false
        }

        this.cellWidth = if (cellWidth > 0f) cellWidth else 1f
        this.cellHeight = if (cellHeight > 0f) cellHeight else 1f

        val session = NativeSession.create(columns, rows)
        if (session == 0L) {
            val reason = "the grid could not be created (${columns}x$rows)"
            lastError = reason
            Log.e(TAG, reason)
            return false
        }

        val launch = EngineLaunch.from(command)
        val pty = NativePty.spawn(
            argv = listOf(launch.program) + launch.args,
            environment = launch.env.toMap(),
            cwd = launch.cwd ?: "",
            columns = columns,
            rows = rows,
            cellWidth = this.cellWidth.toInt(),
            cellHeight = this.cellHeight.toInt(),
        )

        if (pty == 0L) {
            val reason = "the process could not be started (${launch.program})"
            lastError = reason
            Log.e(TAG, reason)
            NativeSession.free(session)
            return false
        }

        sessionHandle = session
        ptyHandle = pty
        view.attach(session, pty, this.cellWidth, this.cellHeight)
        state = state.started()
        // A session nobody can type into is a session nobody can use; the
        // keyboard is what the start was for.
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

        sessionHandle = handle
        ptyHandle = 0
        view.attach(handle, 0, cellWidth, cellHeight)
        state = state.started()
        return true
    }

    /** Feed bytes to the grid. Used by a bridged session's transport. */
    override fun feed(bytes: ByteArray, length: Int) {
        val session = sessionHandle
        if (session == 0L) return
        NativeSession.write(session, bytes, length)
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

    /** Stop the session, keeping the view alive. */
    override fun stop() {
        if (!state.canStop) return

        val pty = ptyHandle
        if (pty != 0L) {
            NativePty.close(pty)
            ptyHandle = 0
        }
        view.detach()
        state = state.stopped()
    }

    /** Release: the session is closed and the view is dead. */
    override fun release() {
        if (state == EngineTerminalState.RELEASED) return

        stop()

        val session = sessionHandle
        if (session != 0L) {
            NativeSession.free(session)
            sessionHandle = 0
        }
        state = EngineTerminalState.RELEASED
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
        // The font stack measures; this class only carries the result.
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
        val session = sessionHandle
        if (session == 0L) return 0
        val scrollbar = NativeSession.scrollbar(session)
        return scrollbar?.first ?: 0
    }

    /** The viewport's position, in rows above the live edge. */
    override fun scrollOffset(): Int {
        val session = sessionHandle
        if (session == 0L) return 0
        return NativeSession.scrollbar(session)?.second ?: 0
    }

    /** Scroll so [offset] rows of history sit above the viewport. */
    override fun scrollTo(offset: Int) {
        val session = sessionHandle
        if (session == 0L) return
        NativeSession.scroll(session, NativeSession.SCROLL_ROW, offset)
        view.invalidate()
    }

    /** The shell's phase, as its `OSC 133` marks report it. */
    override fun shellPhase(): dev.drosh.terminal.engine.shell.Phase {
        val session = sessionHandle
        if (session == 0L) return dev.drosh.terminal.engine.shell.Phase.UNKNOWN
        return dev.drosh.terminal.engine.shell.Phase.of(NativeSession.shellPhase(session))
    }

    /** The working directory the shell last reported. */
    override fun workingDirectory(): String {
        val session = sessionHandle
        if (session == 0L) return ""
        return NativeSession.workingDirectory(session)
    }

    /** True when the alternate screen is on: a program, not a shell. */
    override fun isAltBufferActive(): Boolean {
        val session = sessionHandle
        if (session == 0L) return false
        return NativeSession.alternateScreen(session)
    }

    /** The engine's mode bits, or 0. */
    override fun modes(): Int {
        val session = sessionHandle
        if (session == 0L) return 0
        return NativeSession.modes(session)
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

    override fun find(query: String, limit: Int): List<Triple<Int, Int, Int>> {
        val session = sessionHandle
        if (session == 0L) return emptyList()
        return NativeSession.search(session, query, limit)
    }

    /**
     * The last lines the session printed, oldest first.
     *
     * The visible viewport, taken from a fresh dump of the grid: the rows a
     * thumbnail actually draws. Scrollback is not included — the grid does
     * not hand it out, and a caller wanting history joins from the log.
     */
    override fun transcript(): List<String> {
        val session = sessionHandle
        if (session == 0L) return emptyList()
        return dev.drosh.terminal.engine.native.NativeSession.frame(session)?.lines() ?: emptyList()
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
