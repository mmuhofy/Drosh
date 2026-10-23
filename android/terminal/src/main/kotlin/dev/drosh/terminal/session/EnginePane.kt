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
 * Termux's [com.termux.view.TerminalView] *attaches* to a session that the
 * manager owns; the engine's [TerminalView] *owns* its [EngineSession] and
 * starts it itself. A host that has to know that difference ends up with two
 * shapes of "a terminal", and the difference leaks into the product: a pane is
 * either something a session is attached to, or something that is a session.
 * This type is the seam — the host holds an [EnginePane], starts it with a
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
 * (`TerminalView`'s `onGridChanged`). The pane does not compute a size of its
 * own — a second rule for it would only be a second opinion, and the two
 * would disagree the first time the cell size changed.
 */
class EnginePane(context: Context) {
    /**
     * The persistent id of the session this pane runs, or "" when the pane
     * has not been bound to a session yet.
     *
     * The engine owns the session, but the app owns the identity: a pane
     * that was started without an id is a pane the app cannot reconcile,
     * close or restore, so it would exist outside the session system.
     */
    var persistentId: String = ""
        private set

    /** The user-visible name of the session this pane runs. */
    var sessionName: String = ""
        private set

    /** Bind this pane to a persistent session identity. */
    fun bindIdentity(persistentId: String, name: String) {
        this.persistentId = persistentId
        this.sessionName = name
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
    val view: TerminalView get() = controller.view

    /** The engine's session behind [view]. */
    val session: EngineSession get() = controller.session

    /** The IME's focus target; the host keeps it in the hierarchy (1×1 px). */
    val imeTarget: View get() = controller.imeTarget

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
            listener?.onTitle(title)
        }

        override fun onBell() {
            listener?.onBell()
        }

        override fun onSessionExited(code: Int) {
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
    var state: EnginePaneState = EnginePaneState.IDLE
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
    fun attachExisting(handle: Long, columns: Int, rows: Int, cellWidth: Int, cellHeight: Int): Boolean {
        if (!state.canStart) {
            Log.w(TAG, "attachExisting refused: the pane is $state")
            return false
        }

        session.attachExistingHandle(handle, columns, rows, cellWidth, cellHeight)
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
    fun start(command: GuestCommand): Boolean {
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
    fun stop() {
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
    fun release() {
        if (state == EnginePaneState.RELEASED) {
            return
        }
        controller.release()
        state = state.released()
    }

    /** The host's resume: the surface and the frame pump come back. */
    fun onHostResume() = controller.onHostResume()

    /** The host's pause: the surface sleeps and the frame pump stops. */
    fun onHostPause() = controller.onHostPause()

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
    fun setCustomFont(bytes: ByteArray?): Boolean = view.setCustomFont(bytes)

    fun focus() {
        view.requestFocus()
        imeTarget.requestFocus()
    }

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
    fun notifyFocus(focused: Boolean) {
        if (modes() and NativeCore.MODE_FOCUS_IN_OUT == 0) {
            return
        }
        session.write(if (focused) FOCUS_IN else FOCUS_OUT)
    }

    // --- clipboard ---------------------------------------------------------

    /** Copy the selection to the clipboard; false when nothing is selected. */
    fun copySelection(): Boolean = controller.copySelection()

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
    fun selectionText(): String = view.selectionOverlay.copyText()

    /** Is there a selection in this pane? */
    fun hasSelection(): Boolean = !view.selectionOverlay.isEmpty

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
    fun selectionBounds(): Rect? {
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
    fun selectWord(row: Int, col: Int) = controller.selectWord(row, col)

    /** Select the whole line containing (row, col). */
    fun selectLine(row: Int, col: Int) = controller.selectLine(row, col)

    /** Select the entire visible grid. */
    fun selectAll() = controller.selectAll()

    /** Clear the selection. */
    fun clearSelection() = controller.clearSelection()

    // --- the viewport ------------------------------------------------------

    /**
     * How far the viewport is from the live edge, in rows.
     *
     * 0 at the live edge; positive in the scrollback, counting the rows the
     * viewport sits above the last line. The engine's own number: the state
     * array reports the display offset with every frame the engine draws.
     */
    fun scrollOffset(): Int =
        if (!session.isAlive) 0 else session.stateArray[NativeCore.STATE_DISPLAY_OFFSET]

    /** Is a program (the alternate screen) on screen rather than a shell? */
    fun isAltBufferActive(): Boolean =
        modes() and NativeCore.MODE_ALT_SCREEN != 0

    // --- find --------------------------------------------------------------

    /** Find [query] in the visible grid; each result is `row, col, len`. */
    fun find(query: String, limit: Int = FIND_LIMIT): List<Triple<Int, Int, Int>> =
        controller.find(query, limit)

    // --- what the engine knows ---------------------------------------------

    /** The engine's mode bits (0 when there is no live session). */
    fun modes(): Int = controller.modes()

    /** The average background colour of the grid as `[r, g, b]`, or empty. */
    fun ambientTint(): IntArray = controller.ambientTint()

    /** The last lines the session printed, oldest first. */
    fun transcript(): List<String> = session.transcript()

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
        const val TAG = "EnginePane"

        /** How many hits a single find returns when the host asks for none. */
        const val FIND_LIMIT = 200

        /** `?1004`: the terminal gained the focus. */
        val FOCUS_IN = "\u001b[I".toByteArray(Charsets.US_ASCII)

        /** `?1004`: the terminal lost the focus. */
        val FOCUS_OUT = "\u001b[O".toByteArray(Charsets.US_ASCII)
    }
}
