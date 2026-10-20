package dev.drosh.terminal.session

import android.content.Context
import android.util.Log
import android.view.View
import dev.drosh.linux.GuestCommand
import dev.drosh.terminal.engine.core.BlockState
import dev.drosh.terminal.engine.core.EngineSession
import dev.drosh.terminal.engine.view.TerminalController
import dev.drosh.terminal.engine.view.TerminalView

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
     * The engine's own controller, which owns the view and the session.
     *
     * The application context is used on purpose: the pane outlives the
     * composition that created it (the manager keeps it), and a view that
     * holds an activity context would keep that activity alive with it.
     */
    private val controller = TerminalController(context.applicationContext ?: context)

    /** The engine's view: what the host puts into its layout. */
    val view: TerminalView get() = controller.view

    /** The engine's session behind [view]. */
    val session: EngineSession get() = controller.session

    /** The IME's focus target; the host keeps it in the hierarchy (1×1 px). */
    val imeTarget: View get() = controller.imeTarget

    /** Where the host learns from the session (title, bell, exit, marks). */
    var listener: TerminalView.Listener?
        get() = controller.listener
        set(value) { controller.listener = value }

    /** Where the pane is in its lifecycle. */
    var state: EnginePaneState = EnginePaneState.IDLE
        private set

    // --- lifecycle ---------------------------------------------------------

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

    /** Select the word under (row, col). */
    fun selectWord(row: Int, col: Int) = controller.selectWord(row, col)

    /** Select the whole line containing (row, col). */
    fun selectLine(row: Int, col: Int) = controller.selectLine(row, col)

    /** Clear the selection. */
    fun clearSelection() = controller.clearSelection()

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
    }
}
