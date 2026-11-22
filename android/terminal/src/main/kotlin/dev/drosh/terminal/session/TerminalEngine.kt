package dev.drosh.terminal.session

import android.view.View

/**
 * A terminal — whichever engine draws it.
 *
 * ## Why this exists
 *
 * Both engines in this tree present the same host surface, and the manager,
 * the panes and the Compose screens are written once against this interface
 * rather than twice against two classes. The old Rust engine is deleted; the
 * engine driving the GL surface is the only one on the runtime path today.
 *
 * ## What is here and what is not
 *
 * Only what the host actually asks for. A method that one engine cannot answer
 * is here with a documented empty answer, not omitted: the host should not have
 * to know which engine it holds in order to compile.
 *
 * The `view` is a `View`, not `TerminalView` or `TerminalSurface`: the host puts
 * it in an `AndroidView` and that is all it does with it.
 */
/**
 * The theme the engine draws a session with.
 *
 * The engine's colours are per terminal, not per screen: a session that starts
 * after the theme was chosen has to be told it, or it draws in the library's
 * own black until something else sets them. Keeping the theme on the pane
 * means every path that opens a session — the manager's, a refresh's, a
 * bridged one's — applies it without each of them having to remember.
 */
data class TerminalEngineTheme(
    /** The default text colour, packed 0xRRGGBB. */
    val foreground: Int,
    /** The default background colour, packed 0xRRGGBB. */
    val background: Int,
    /** The 256-entry palette, packed 0xRRGGBB. */
    val palette: IntArray,
    /** The cursor's shape, as the engine's own style numbers. */
    val cursorStyle: Int,
    /** Whether the cursor blinks. */
    val cursorBlink: Boolean,
)

interface TerminalEngine {

    /** The Android view that draws the terminal. */
    val view: View

    /**
     * The theme every session this pane opens is given.
     *
     * Set by the manager, applied when a session is shown. Null until the
     * manager says; a pane with no theme draws in the engine's defaults, which
     * is what a terminal with no app behind it would show.
     */
    var theme: TerminalEngineTheme?

    /** Where the pane is in its lifecycle. */
    val state: PaneState

    /** The persistent id of the session this terminal runs. */
    var persistentId: String

    /** The name the sidebar shows. */
    var sessionName: String

    /** The IME's focus target; the host keeps it in the hierarchy. */
    val imeTarget: View

    // --- lifecycle ---------------------------------------------------------

    /**
     * Start a session that runs [command] inside the guest.
     *
     * The dimensions are what the *caller* believes the grid is. An engine that
     * measures its own view — the old one does — ignores them; an engine that
     * needs them before the child runs — the new one does, because a PTY has to
     * know its size before the first prompt — uses them.
     */
    fun start(
        command: dev.drosh.linux.GuestCommand,
        columns: Int = 0,
        rows: Int = 0,
        cellWidth: Float = 0f,
        cellHeight: Float = 0f,
    ): Boolean

    /** Attach a grid that already exists, with no local process. */
    fun attachExisting(
        handle: Long,
        columns: Int,
        rows: Int,
        cellWidth: Float,
        cellHeight: Float,
    ): Boolean

    /** Feed bytes to the grid; a bridged session's transport calls this. */
    fun feed(bytes: ByteArray, length: Int = bytes.size)

    /** Write bytes to the child. */
    fun write(bytes: ByteArray)

    /** Stop the session, keeping the view alive. */
    fun stop()

    /** Release: the session is closed and the view is dead. */
    fun release()

    fun bindIdentity(persistentId: String, name: String)

    fun rebind(persistentId: String, name: String)

    /**
     * Show a session in this pane.
     *
     * A pane is a view and a session is what runs; the two have different
     * lifetimes, so the pane is told which session to show rather than owning
     * one. The session is not disturbed — it keeps running and keeps its
     * output, which is what lets the session grid show a session that is not
     * on screen.
     *
     * A pane whose engine has no such notion does nothing, and says so.
     */
    fun showSession(session: RenderedSession) {
        // Nothing to do: this engine's pane and session are the same object.
    }

    // --- appearance --------------------------------------------------------

    /** The font's cell changed: the grid has to reflow. */
    fun setCell(width: Float, height: Float)

    fun setTextSize(sp: Float)

    fun applyAppearance(config: dev.drosh.domain.settings.TerminalConfig)

    fun setCustomFont(bytes: ByteArray?): Boolean

    // --- input -------------------------------------------------------------

    fun focus()

    fun hasViewFocus(): Boolean

    fun onHostResume()

    fun onHostPause()

    // --- host queries ------------------------------------------------------

    fun scrollOffset(): Int

    /**
     * The absolute row the viewport starts at.
     *
     * The transcript's lines are the engine's rows, oldest first, so a match
     * found in the transcript is in this row space — and the viewport's own
     * first row is where the drawing of it begins.
     */
    fun viewportRow(): Int

    fun scrollTo(offset: Int)

    /**
     * Highlight the range the search matched, in viewport rows.
     *
     * A length of zero clears the highlight.
     */
    fun setHighlight(row: Int, column: Int, length: Int)

    /**
     * Highlight every range the search's other matches cover, in viewport rows.
     *
     * The active match is not part of this set — it is drawn by
     * [setHighlight] in its own, stronger colour. An empty list clears the
     * marking.
     */
    fun setHighlights(ranges: List<Triple<Int, Int, Int>>) {}

    /**
     * Draw no all-matches highlight; the active one is untouched.
     */
    fun clearHighlights() {}

    /**
     * Scroll to the prompt above or below the viewport.
     *
     * Negative for the prompt above, positive for the one below. False when
     * there is no prompt to go to — the shell has to report its prompts
     * (OSC 133) for the engine to know where they are.
     */
    fun scrollToPrompt(delta: Int): Boolean

    fun historySize(): Int

    fun promptRows(): IntArray

    fun shellPhase(): dev.drosh.terminal.engine.shell.Phase

    fun workingDirectory(): String

    fun isAltBufferActive(): Boolean

    fun modes(): Int

    // --- selection ---------------------------------------------------------

    fun selectionText(): String

    fun hasSelection(): Boolean

    fun copySelection(): Boolean

    fun selectWord(row: Int, col: Int, chrome: Boolean = false): Boolean

    fun selectLine(row: Int, col: Int, chrome: Boolean = false): Boolean

    fun selectAll(): Boolean

    fun clearSelection()

    fun selectionBounds(): android.graphics.Rect?

    // --- find --------------------------------------------------------------

    fun find(query: String, limit: Int = 200): List<Triple<Int, Int, Int>>

    /**
     * The grid's shape, in cells.
     *
     * What the terminal shows: the columns and the rows of the live grid.
     */
    val columns: Int
        get() = 0
    val rows: Int
        get() = 0

    /** The cell the font measured, in pixels. */
    val cellWidth: Float
        get() = 1f
    val cellHeight: Float
        get() = 1f

    /**
     * The colours of a region of the grid, as `(foreground, background)` pairs.
     *
     * The values are the engine's own packed colours — red in the low byte —
     * so a caller reads them with the same helpers the renderer uses.
     */
    fun cellColors(
        firstRow: Int,
        firstColumn: Int,
        rows: Int,
        columns: Int,
    ): IntArray = IntArray(0)

    /**
     * The last lines the session printed, oldest first.
     *
     * Empty on an engine that does not keep them. The Ghostty core does not
     * hand out its grid's bytes, so this is the one query whose answer differs
     * by engine — and the host already treats an empty list as "nothing to
     * show".
     */
    fun transcript(): List<String>

    /**
     * The URI of the hyperlink a cell belongs to, or null.
     *
     * Null on an engine whose API does not expose `OSC 8` per cell.
     */
    fun hyperlinkAt(row: Int, column: Int): String?

    /**
     * The manager's session-exit callback.
     *
     * The manager attaches this at registration time to drop a session from
     * its list when the child dies. A pane that cannot report exits keeps
     * the default and never fires; the manager's bookkeeping is then blind.
     */
    var onSessionExitedCallback: ((Int) -> Unit)?
        get() = null
        set(_) {}

    /**
     * Paste the platform clipboard into the pane.
     *
     * Bracketed paste when the shell asked for it; a pane that cannot tell
     * writes the text plain.
     */
    fun pasteClipboard() {}

    /**
     * Moves the selection's end to a point, in the pane's own pixels.
     *
     * The handles at the ends of a selection are how a user fixes a selection
     * they did not get right. `topLeft` names the grip the finger is on: the
     * other endpoint stays where it is.
     */
    fun extendSelectionTo(x: Float, y: Float, topLeft: Boolean): Boolean = false

    /** The cell the toolbar's word and line actions grow from, or null. */
    fun selectionAnchorCell(): Pair<Int, Int>? = null

    /**
     * A program rang the bell.
     *
     * The terminal flashes and the app buzzes. A pane that cannot flash
     * simply does nothing.
     */
    fun bell() {}

    /**
     * The ambient tint of the session's palette as `[r, g, b]`, or empty.
     *
     * Empty on an engine whose palette the app cannot read; the tint bloom is
     * then never applied.
     */
    fun ambientTint(): IntArray = IntArray(0)

    /**
     * The manager's focus advisory.
     *
     * The manager tells the pane when focus moves to or away from it, so a
     * pane that needs to wake from a sleepy state can. A pane with no
     * opinion has none.
     */
    fun notifyFocus(focused: Boolean) {}

    /**
     * The manager's selection listener — chrome feedback for "is there a
     * selection".
     *
     * A pane that cannot report selection events keeps the default and never
     * fires; the chrome simply never comes up.
     */
    var selectionListener: ((Boolean) -> Unit)?
}
