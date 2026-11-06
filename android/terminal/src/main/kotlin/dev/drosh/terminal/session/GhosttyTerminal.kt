package dev.drosh.terminal.session

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
class GhosttyTerminal(context: Context) {

    /** The view, as the host sees it. */
    val view: TerminalSurface = TerminalSurface(context)

    /** The grid. Zero until [start]. */
    @Volatile
    var sessionHandle: Long = 0
        private set

    /** The process. Zero until [start], and for a bridged session. */
    @Volatile
    var ptyHandle: Long = 0
        private set

    /** Where the pane is in its lifecycle. */
    var state: EngineTerminalState = EngineTerminalState.IDLE
        private set

    /** The persistent id of the session this terminal runs. */
    var persistentId: String = ""

    /** The name the sidebar shows. */
    var sessionName: String = ""

    /** Told when the child exits, with its status. */
    var onSessionExited: ((Int) -> Unit)? = null

    /** Told when the shell's phase changes. */
    var onShellPhase: ((dev.drosh.terminal.engine.shell.Phase) -> Unit)? = null

    /** The cell size the font measured, in pixels. */
    private var cellWidth = 1f
    private var cellHeight = 1f

    init {
        view.onExit = { status ->
            state = state.exited()
            onSessionExited?.invoke(status)
        }
        view.onGridChanged = { _, _ -> }
    }

    // --- identity ----------------------------------------------------------

    fun bindIdentity(persistentId: String, name: String) {
        this.persistentId = persistentId
        this.sessionName = name
    }

    fun rebind(persistentId: String, name: String) {
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
    fun start(command: GuestCommand, columns: Int, rows: Int, cellWidthPx: Float, cellHeightPx: Float): Boolean {
        if (!state.canStart) {
            Log.w(TAG, "start refused: the terminal is $state")
            return false
        }
        if (!NativeLibraries.ensureLoaded()) {
            Log.e(TAG, "the engine did not load: ${NativeLibraries.failure}")
            return false
        }

        cellWidth = cellWidthPx
        cellHeight = cellHeightPx

        val session = NativeSession.create(columns, rows)
        if (session == 0L) {
            Log.e(TAG, "the grid could not be created")
            return false
        }

        val launch = EngineLaunch.from(command)
        val pty = NativePty.spawn(
            argv = listOf(launch.program) + launch.args,
            environment = launch.env.toMap(),
            cwd = launch.cwd ?: "",
            columns = columns,
            rows = rows,
            cellWidth = cellWidthPx.toInt(),
            cellHeight = cellHeightPx.toInt(),
        )

        if (pty == 0L) {
            Log.e(TAG, "the process could not be started")
            NativeSession.free(session)
            return false
        }

        sessionHandle = session
        ptyHandle = pty
        view.attach(session, pty, cellWidthPx, cellHeightPx)
        state = state.started()
        return true
    }

    /**
     * Attach a grid that already exists, with no process.
     *
     * An SSH session is bridged: the bytes come from the transport rather than
     * a local child, so there is no PTY. The caller feeds the grid itself.
     */
    fun attachExisting(handle: Long, columns: Int, rows: Int, cellWidthPx: Float, cellHeightPx: Float): Boolean {
        if (!state.canStart) return false
        if (!NativeLibraries.ensureLoaded()) return false

        cellWidth = cellWidthPx
        cellHeight = cellHeightPx

        sessionHandle = handle
        ptyHandle = 0
        view.attach(handle, 0, cellWidthPx, cellHeightPx)
        state = state.started()
        return true
    }

    /** Feed bytes to the grid. Used by a bridged session's transport. */
    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        val session = sessionHandle
        if (session == 0L) return
        NativeSession.write(session, bytes, length)
        view.invalidate()
    }

    /** Write bytes to the child. Used by the keyboard and the paste path. */
    fun write(bytes: ByteArray) {
        view.write(bytes)
    }

    /** Stop the session, keeping the view alive. */
    fun stop() {
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
    fun release() {
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
    fun setCell(width: Float, height: Float) {
        cellWidth = width
        cellHeight = height
        view.setCell(width, height)
    }

    fun setTextSize(sp: Float) {
        // The font stack measures; this class only carries the result.
    }

    fun applyAppearance(config: dev.drosh.domain.settings.TerminalConfig) {
        // Colours are the engine's: the palette comes from the frame.
    }

    fun setCustomFont(bytes: ByteArray?): Boolean = false

    // --- input -------------------------------------------------------------

    /** The IME target. The surface is its own editor. */
    val imeTarget: View get() = view

    fun focus() {
        view.requestFocus()
    }

    fun hasViewFocus(): Boolean = view.hasFocus()

    fun onHostResume() {}

    fun onHostPause() {}

    // --- host queries ------------------------------------------------------

    /** The rows a prompt started on, as rows above the live edge. */
    fun promptRows(): IntArray = IntArray(0)

    /** How much history there is above the viewport, in rows. */
    fun historySize(): Int {
        val session = sessionHandle
        if (session == 0L) return 0
        val scrollbar = NativeSession.scrollbar(session)
        return scrollbar?.first ?: 0
    }

    /** The viewport's position, in rows above the live edge. */
    fun scrollOffset(): Int {
        val session = sessionHandle
        if (session == 0L) return 0
        return NativeSession.scrollbar(session)?.second ?: 0
    }

    /** Scroll so [offset] rows of history sit above the viewport. */
    fun scrollTo(offset: Int) {
        val session = sessionHandle
        if (session == 0L) return
        NativeSession.scroll(session, NativeSession.SCROLL_ROW, offset)
        view.invalidate()
    }

    /** The shell's phase, as its `OSC 133` marks report it. */
    fun shellPhase(): dev.drosh.terminal.engine.shell.Phase {
        val session = sessionHandle
        if (session == 0L) return dev.drosh.terminal.engine.shell.Phase.UNKNOWN
        return dev.drosh.terminal.engine.shell.Phase.of(NativeSession.shellPhase(session))
    }

    /** The working directory the shell last reported. */
    fun workingDirectory(): String {
        val session = sessionHandle
        if (session == 0L) return ""
        return NativeSession.workingDirectory(session)
    }

    /** True when the alternate screen is on: a program, not a shell. */
    fun isAltBufferActive(): Boolean {
        val session = sessionHandle
        if (session == 0L) return false
        return NativeSession.alternateScreen(session)
    }

    /** The engine's mode bits, or 0. */
    fun modes(): Int {
        val session = sessionHandle
        if (session == 0L) return 0
        return NativeSession.modes(session)
    }

    // --- selection ---------------------------------------------------------

    fun selectionText(): String = view.selectedText()

    fun hasSelection(): Boolean = view.hasSelection()

    fun copySelection(): Boolean = view.copySelection()

    fun selectWord(row: Int, col: Int): Boolean = view.selectWord(row, col)

    fun selectLine(row: Int, col: Int): Boolean = view.selectLine(row, col)

    fun selectAll(): Boolean = view.selectAll()

    fun clearSelection() = view.clearSelection()

    fun selectionBounds(): Rect? = view.selectionBounds()

    // --- find --------------------------------------------------------------

    fun find(query: String, limit: Int = FIND_LIMIT): List<Triple<Int, Int, Int>> {
        val session = sessionHandle
        if (session == 0L) return emptyList()
        return NativeSession.search(session, query, limit)
    }

    /** Not carried by this engine: the grid does not hand out its bytes. */
    fun transcript(): List<String> = emptyList()

    /** Not carried by this engine: `OSC 8` has no C API surface yet. */
    fun hyperlinkAt(row: Int, column: Int): String? = null

    private companion object {
        const val TAG = "GhosttyTerminal"
        const val FIND_LIMIT = 200
    }
}
