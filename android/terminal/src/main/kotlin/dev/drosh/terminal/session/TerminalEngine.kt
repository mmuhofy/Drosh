package dev.drosh.terminal.session

import android.view.View

/**
 * A terminal, whichever engine draws it.
 *
 * ## Why this exists
 *
 * There are two engines in the APK. The old one is Rust and has been on a
 * device; the new one is Ghostty and has not. Both present the same host
 * surface, and the manager, the panes and the Compose screens are written once
 * against this interface rather than twice against two classes.
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
interface TerminalEngine {

    /** The Android view that draws the terminal. */
    val view: View

    /** Where the pane is in its lifecycle. */
    val state: EngineTerminalState

    /** The persistent id of the session this terminal runs. */
    var persistentId: String

    /** The name the sidebar shows. */
    var sessionName: String

    /** The IME's focus target; the host keeps it in the hierarchy. */
    val imeTarget: View

    // --- lifecycle ---------------------------------------------------------

    /** Start a session that runs [command] inside the guest. */
    fun start(
        command: dev.drosh.linux.GuestCommand,
        columns: Int,
        rows: Int,
        cellWidth: Float,
        cellHeight: Float,
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

    fun scrollTo(offset: Int)

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

    fun selectWord(row: Int, col: Int): Boolean

    fun selectLine(row: Int, col: Int): Boolean

    fun selectAll(): Boolean

    fun clearSelection()

    fun selectionBounds(): android.graphics.Rect?

    // --- find --------------------------------------------------------------

    fun find(query: String, limit: Int = 200): List<Triple<Int, Int, Int>>

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
}
