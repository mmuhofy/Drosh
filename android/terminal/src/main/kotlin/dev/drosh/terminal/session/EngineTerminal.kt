package dev.drosh.terminal.session

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.View
import dev.drosh.linux.GuestCommand
import dev.drosh.terminal.engine.core.BlockState
import dev.drosh.terminal.engine.core.EngineSession
import dev.drosh.terminal.engine.core.NativeCore
import dev.drosh.terminal.engine.view.TerminalController
import dev.drosh.terminal.engine.view.TerminalView
import kotlin.math.roundToInt

/**
 * One engine-backed terminal pane: the host-facing half of a terminal the
 * Rust engine draws.
 *
 * ## Why this type exists
 *
 * The two terminal implementations do not agree on who owns the session.
 * A vendored view *attaches* to a session that the
 * manager owns; the engine's [TerminalView] *owns* its [EngineSession] and
 * starts it itself. A host that has to know that difference ends up with two
 * shapes of "a terminal", and the difference leaks into the product: a pane is
 * either something a session is attached to, or something that is a session.
 * This type is the seam — the host holds an [EngineTerminal], starts it with a
 * guest command, and never learns that the engine owns the session underneath.
 *
 * ## What it adds to the engine's own surface
 *
 * [TerminalController] is already the engine's host-facing surface, and this
 * type delegates to it rather than re-implementing it. What it adds is the
 * two things the controller cannot know:
 *
 *  - **starting a guest session** — a [GuestCommand] translated by
 *    [EngineLaunch] and handed to the engine;
 *  - **owning the lifecycle** — the pane knows whether it is idle, running or
 *    released, and it closes the session when the host is done with it.
 *
 * ## The size of the session
 *
 * The session is started with the size the view actually has: the engine's
 * view derives the grid from its own width and the cell metrics
 * (`TerminalView.gridColumns()` / `gridRows()`), and the renderer reports the
 * grid again whenever the surface changes, which resizes the session to match
 * (`TerminalRenderer.updateSize`). The pane does not compute a size of its
 * own — a second rule for it would only be a second opinion, and the two
 * would disagree the first time the cell size changed.
 */
class EngineTerminal(context: Context) : TerminalEngine {
    /**
     * The persistent id of the session this pane runs, or "" when the pane
     * has not been bound to a session yet.
     *
     * The engine owns the session, but the app owns the identity: a pane
     * that was started without an id is a pane the app cannot reconcile,
     * close or restore, so it would exist outside the session system.
     */
    override var persistentId: String = ""

    /** The user-visible name of the session this pane runs. */
    override var sessionName: String = ""

    /** Bind this pane to a persistent session identity. */
    override fun bindIdentity(persistentId: String, name: String) {
        this.persistentId = persistentId
        this.sessionName = name
    }

    /**
     * Point this pane at another session.
     *
     * A pane is one view and one session at a time, and switching sessions
     * rebinds the view rather than building a new one: the GL surface, the
     * atlas and the glyph cache are the expensive things, and a new pane per
     * switch would throw all of them away and rebuild them behind a black
     * rectangle.
     *
     * The old session is closed first. It is not stopped-and-kept: the engine
     * holds a process and a PTY per session, and a switch that left the old
     * one alive would leak both.
     */
    override fun rebind(persistentId: String, name: String) {
        if (this.persistentId == persistentId) {
            // The same session: nothing to rebind, and closing a live one to
            // reopen it would lose its output.
            this.sessionName = name
            return
        }

        // The old session goes first, so its exit event cannot arrive while
        // the new one is starting and be reported against the wrong id.
        if (state.canStop) {
            session.close()
            state = state.stopped()
        }
        bindIdentity(persistentId, name)
    }
    /**
     * The engine's own controller, which owns the view and the session.
     *
     * The context is the one the host gave — the activity's, for a view. A
     * `GLSurfaceView` built with the application context has no window to
     * draw in, and the terminal is a surface that never draws: the app shows
     * a blank rectangle where the terminal should be.
     *
     * The pane does not outlive its host: the host releases it when the
     * composition goes, and the manager drops it then too. So the view's
     * context is the activity's for exactly as long as the activity is.
     */
    private val controller = TerminalController(context)

    /** The engine's view: what the host puts into its layout. */
    override val view: TerminalView get() = controller.view

    /** The engine's session behind [view]. */
    val session: EngineSession get() = controller.session

    /** The IME's focus target; the host keeps it in the hierarchy (1×1 px). */
    override val imeTarget: View get() = controller.imeTarget

    /**
     * The size a pinch has reached, or 0 when none is in progress.
     *
     * The view knows it and the host draws the chip, so the pane passes the
     * report along rather than the host reaching into the view.
     */
    var zoomListener: (sp: Float) -> Unit
        get() = view.zoomListener
        set(value) {
            view.zoomListener = value
        }

    /**
     * Told when the user long-presses a cell that carries an `OSC 8`
     * hyperlink. The URI is the cell's own; the host opens it.
     */
    var onHyperlink: ((String) -> Unit)?
        get() = view.onHyperlink
        set(value) {
            view.onHyperlink = value
        }

    /** Where the host learns from the session (title, bell, exit, marks). */
    var listener: TerminalView.Listener? = null
        set(value) {
            field = value
            controller.listener = forwarder
        }

    /**
     * Called when the session exits, in addition to [listener.onSessionExited].
     *
     * The manager uses this to track engine sessions without replacing the
     * host's listener. Both are called: the host's for UI, the manager's for
     * bookkeeping.
     */
    var onSessionExitedCallback: ((Int) -> Unit)? = null

    /**
     * Told when the selection in this pane appeared or disappeared.
     *
     * The engine's view is what knows when the user selected something, and
     * the host publishes the selection — so the pane passes the change on.
     * The host's own [listener] hears it too.
     */
    var selectionListener: ((Boolean) -> Unit)? = null

    /**
     * The engine's listener, with the pane's own observation in front of it.
     *
     * The engine has room for one listener and the host needs two: the
     * events it cares about, and the selection change the pane has to see.
     * One listener that answers to both is what keeps the host from losing
     * its own.
     */
    private val forwarder = object : TerminalView.Listener {
        override fun onTitle(title: String) {
            // The title is the guest's own idea of itself, and it is the first
            // thing a terminal says: a shell that prints "root@…" in its title
            // has started and is about to print a prompt. Logged because a
            // blank screen cannot tell "the shell never ran" from "the shell
            // ran and nothing was drawn", and this is the line that can.
            Log.i(TAG, "title: $title")
            listener?.onTitle(title)
        }

        override fun onBell() {
            listener?.onBell()
        }

        override fun onSessionExited(code: Int) {
            Log.w(TAG, "session exited: $code")
            listener?.onSessionExited(code)
            onSessionExitedCallback?.invoke(code)
        }

        override fun onSessionEvent(record: String) {
            listener?.onSessionEvent(record)
        }

        override fun onSelectionChanged(hasSelection: Boolean) {
            selectionListener?.invoke(hasSelection)
            listener?.onSelectionChanged(hasSelection)
        }
    }

    /** Where the pane is in its lifecycle. */
    override var state: EngineTerminalState = EngineTerminalState.IDLE
        private set

    init {
        // The forwarder goes in from the start: the engine's view starts
        // reporting the moment it has something to report, and a host that
        // sets its listener later must not lose the events in between.
        controller.listener = forwarder
    }

    // --- lifecycle ---------------------------------------------------------

    /**
     * Attach an existing bridged session handle (e.g. from SSH) to this pane.
     *
     * The handle must already be a live bridged session from
     * [NativeCore.nativeCreateBridgedSession]. The pane takes ownership of the
     * session and runs it as if it had started it.
     *
     * Returns false when the pane cannot start — it is already running or it
     * was released.
     */
    override fun attachExisting(
        handle: Long,
        columns: Int,
        rows: Int,
        cellWidth: Float,
        cellHeight: Float,
    ): Boolean {
        if (!state.canStart) {
            Log.w(TAG, "attachExisting refused: the pane is $state")
            return false
        }

        session.attachExistingHandle(handle, columns, rows, cellWidth.toInt(), cellHeight.toInt())
        state = state.started()
        return true
    }

    /**
     * Start a session that runs [command] inside the guest.
     *
     * The command is translated by [EngineLaunch]: the program is the guest
     * launcher, the arguments are the rest of the vector, and the executable
     * is **not** repeated among them.
     *
     * Returns false when the pane cannot start — it is already running, it was
     * released, or the engine refused the session.
     */
    override fun start(
        command: GuestCommand,
        columns: Int,
        rows: Int,
        cellWidth: Float,
        cellHeight: Float,
    ): Boolean {
        // This engine measures its own view and derives the grid from it; the
        // caller's numbers are for an engine that cannot.
        if (!state.canStart) {
            Log.w(TAG, "start refused: the pane is $state")
            return false
        }

        val launch = EngineLaunch.from(command)
        val started = try {
            controller.start(launch.program, launch.args, launch.env, launch.cwd)
        } catch (error: Exception) {
            // The engine throws when its bundled font could not be loaded —
            // a packaging bug — and a host must not die with it.
            Log.e(TAG, "the engine refused the session", error)
            false
        }

        if (started) {
            state = state.started()
        }
        return started
    }

    /**
     * Stop the session, keeping the pane alive.
     *
     * The engine's view stays; the pane can be started again afterwards.
     */
    override fun stop() {
        if (!state.canStop) {
            return
        }
        session.close()
        state = state.stopped()
    }

    /**
     * Release the pane: the session is closed and the view is dead.
     *
     * This is what the host calls when it is done with the pane. The session
     * lives in the native core, and this is the only thing that closes it.
     */
    override fun release() {
        if (state == EngineTerminalState.RELEASED) {
            return
        }
        controller.release()
        state = state.released()
    }

    /** The host's resume: the surface and the frame pump come back. */
    override fun onHostResume() = controller.onHostResume()

    /** The host's pause: the surface sleeps and the frame pump stops. */
    override fun onHostPause() = controller.onHostPause()

    // --- focus -------------------------------------------------------------

    /**
     * Gives the pane the keyboard focus.
     *
     * The engine's keys arrive through the IME target — the one-pixel editor
     * inside the view — so the target is what has to be focused; the view
     * alone would take the focus and swallow the keys. The view is asked
     * first, because the host asks the *view* whether the pane has the focus,
     * and a view with a focused descendant counts as focused itself.
     *
     * The soft keyboard is not raised here: the engine's own tap gesture
     * raises it, and a focus that arrives from somewhere else is not the
     * user asking for a keyboard.
     */
    /**
     * Use a font of the host's choosing, instead of the bundled one.
     *
     * The bytes are the font file itself; `null` goes back to the bundled
     * face. A font that cannot be used leaves the terminal as it was.
     */
    override fun setCustomFont(bytes: ByteArray?): Boolean = view.setCustomFont(bytes)

    override fun focus() {
        if (!view.hasFocus()) {
            view.requestFocus()
        }
        if (!imeTarget.hasFocus()) {
            imeTarget.requestFocus()
        }
    }

    /**
     * Whether the engine's view already holds the keyboard focus.
     *
     * Asked before [focus], because the view's focus listener reports back
     * into the manager: without the check, "the pane got the focus" and "make
     * the pane focused" call each other until the stack runs out.
     */
    override fun hasViewFocus(): Boolean = view.hasFocus() || imeTarget.hasFocus()

    /**
     * Tells the session that this pane has (or has not) the focus.
     *
     * `?1004` — the escape sequences xterm and alacritty send when the
     * terminal gains and loses the focus, so a program can show that it is
     * waiting for input. They are sent only when the application asked for
     * them, exactly as the engine's own report does.
     *
     * The engine reports the *window's* focus; the pane is what the host
     * knows about, and a split has two panes in one window — so this is the
     * pane's own report.
     */
    /** Feed bytes to the grid; a bridged session's transport calls this. */
    override fun feed(bytes: ByteArray, length: Int) {
        session.feed(bytes)
    }

    /** Write bytes to the child. */
    override fun write(bytes: ByteArray) {
        session.write(bytes)
    }

    /** The font's cell changed: the grid has to reflow. */
    override fun setCell(width: Float, height: Float) {
        view.setTextSize(view.textSizeSp)
    }

    override fun setTextSize(sp: Float) {
        view.setTextSize(sp)
    }

    override fun applyAppearance(config: dev.drosh.domain.settings.TerminalConfig) {
        view.applyAppearance(config)
    }

    /**
     * The shell's phase, as its `OSC 133` marks report it.
     *
     * The Rust engine tracks the marks on its PTY path; the phase codes are the
     * same, so the host reads one enum whichever engine it holds.
     */
    override fun shellPhase(): dev.drosh.terminal.engine.shell.Phase =
        when (session.blockState().phase) {
            dev.drosh.terminal.engine.core.BlockState.PHASE_PROMPT ->
                dev.drosh.terminal.engine.shell.Phase.PROMPT
            dev.drosh.terminal.engine.core.BlockState.PHASE_TYPING ->
                dev.drosh.terminal.engine.shell.Phase.TYPING
            dev.drosh.terminal.engine.core.BlockState.PHASE_RUNNING ->
                dev.drosh.terminal.engine.shell.Phase.RUNNING
            dev.drosh.terminal.engine.core.BlockState.PHASE_FINISHED ->
                dev.drosh.terminal.engine.shell.Phase.FINISHED
            else -> dev.drosh.terminal.engine.shell.Phase.UNKNOWN
        }

    /** The working directory the shell last reported. */
    override fun workingDirectory(): String =
        session.blockState().workingDirectory ?: ""

    fun notifyFocus(focused: Boolean) {
        if (modes() and NativeCore.MODE_FOCUS_IN_OUT == 0) {
            return
        }
        session.write(if (focused) FOCUS_IN else FOCUS_OUT)
    }

    // --- clipboard ---------------------------------------------------------

    /** Copy the selection to the clipboard; false when nothing is selected. */
    override fun copySelection(): Boolean = controller.copySelection()

    /** Paste from the clipboard, bracketed when the shell asked for it. */
    fun pasteClipboard() = controller.pasteClipboard()

    // --- selection ---------------------------------------------------------

    /**
     * The selected text, or "" when nothing is selected.
     *
     * This is the text of the selection the user *sees* — the one the copy
     * button copies, and the one the renderer highlights. The engine's
     * controller answers this from the *native* selection instead, which is a
     * different thing: the engine's view never sets it, so it is always
     * empty. Reading the overlay is what makes the two agree.
     */
    override fun selectionText(): String = view.selectionOverlay.copyText()

    /** Is there a selection in this pane? */
    override fun hasSelection(): Boolean = !view.selectionOverlay.isEmpty

    /**
     * The selected region in view pixels, or null when nothing is selected.
     *
     * The same shape the vendored view answers with: a rectangle in the
     * pane's own coordinates, so a host can anchor a menu to it without
     * knowing which terminal drew it.
     *
     * The engine's selection is a range of *cells*, and the cell is not a
     * square number of pixels: the font is measured, not counted. So the
     * rectangle is built from the cell the renderer draws with, which is the
     * only size that can be right.
     *
     * Null when the session is gone — and that is also what keeps the cell
     * size safe to read: a session can only exist if the font was loaded,
     * because the engine refuses to start without it.
     */
    override fun selectionBounds(): Rect? {
        if (!session.isAlive) {
            return null
        }
        val selection = view.selectionOverlay.selection
        if (selection.isEmpty) {
            return null
        }
        val cellWidth = view.metrics.width
        val cellHeight = view.metrics.height
        val left = selection.firstColumn * cellWidth
        val right = (selection.lastColumn + 1) * cellWidth
        val top = selection.firstRow * cellHeight
        val bottom = (selection.lastRow + 1) * cellHeight
        return Rect(
            left.roundToInt(),
            top.roundToInt(),
            right.roundToInt(),
            bottom.roundToInt(),
        )
    }

    /** Select the word under (row, col). */
    override fun selectWord(row: Int, col: Int): Boolean {
        controller.selectWord(row, col)
        return hasSelection()
    }

    /** Select the whole line containing (row, col). */
    override fun selectLine(row: Int, col: Int): Boolean {
        controller.selectLine(row, col)
        return hasSelection()
    }

    /** Select the entire visible grid. */
    override fun selectAll(): Boolean {
        controller.selectAll()
        return hasSelection()
    }

    /** Clear the selection. */
    override fun clearSelection() = controller.clearSelection()

    // --- the viewport ------------------------------------------------------

    /**
     * How far the viewport is from the live edge, in rows.
     *
     * 0 at the live edge; positive in the scrollback, counting the rows the
     * viewport sits above the last line. The engine's own number: the state
     * array reports the display offset with every frame the engine draws.
     */
    override fun scrollOffset(): Int =
        if (!session.isAlive) 0 else session.stateArray[NativeCore.STATE_DISPLAY_OFFSET]

    /** The URI of the hyperlink at viewport (`row`, `column`), or null. */
    override fun hyperlinkAt(row: Int, column: Int): String? =
        if (!session.isAlive) null else session.hyperlinkAt(row, column)

    /** The rows a prompt started on, oldest first, as rows above the live edge. */
    override fun promptRows(): IntArray =
        if (!session.isAlive) IntArray(0) else session.promptRows()

    /** How much history there is above the viewport, in rows. */
    override fun historySize(): Int =
        if (!session.isAlive) 0 else session.historySize()

    /** Scroll so [offset] rows of history sit above the viewport. */
    override fun scrollTo(offset: Int) {
        if (!session.isAlive) return
        session.scrollTo(offset)
        view.requestRender()
    }

    /** Is a program (the alternate screen) on screen rather than a shell? */
    override fun isAltBufferActive(): Boolean =
        modes() and NativeCore.MODE_ALT_SCREEN != 0

    // --- find --------------------------------------------------------------

    /** Find [query] in the visible grid; each result is `row, col, len`. */
    override fun find(query: String, limit: Int): List<Triple<Int, Int, Int>> =
        controller.find(query, limit)

    // --- what the engine knows ---------------------------------------------

    /** The engine's mode bits (0 when there is no live session). */
    override fun modes(): Int = controller.modes()

    /** The average background colour of the grid as `[r, g, b]`, or empty. */
    fun ambientTint(): IntArray = controller.ambientTint()

    /** The last lines the session printed, oldest first. */
    override fun transcript(): List<String> = session.transcript()

    /** Where the shell is in its cycle, as the engine's OSC 133 marks report it. */
    fun blockState(): BlockState = session.blockState()

    /**
     * The engine's colour table: one entry per palette index, `-1` for "the
     * default colour".
     *
     * A snapshot, because the engine rewrites the table in place when it
     * pumps the grid.
     */
    fun colors(): IntArray = session.colors.copyOf()

    private companion object {
        const val TAG = "EngineTerminal"

        /** How many hits a single find returns when the host asks for none. */
        const val FIND_LIMIT = 200

        /** `?1004`: the terminal gained the focus. */
        val FOCUS_IN = "\u001b[I".toByteArray(Charsets.US_ASCII)

        /** `?1004`: the terminal lost the focus. */
        val FOCUS_OUT = "\u001b[O".toByteArray(Charsets.US_ASCII)
    }
}
