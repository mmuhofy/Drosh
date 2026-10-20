package dev.drosh.terminal.engine.view

import android.view.View
import dev.drosh.terminal.engine.core.EngineSession
import dev.drosh.terminal.engine.input.Encoder

/**
 * The public surface a host (activity, fragment, Compose view) talks to.
 *
 * It owns the internal [TerminalView] and exposes the stable operations:
 * lifecycle, session start, clipboard, selection handles, a find hook, a zoom
 * placeholder and the Rust-served ambient tint. Keeping it separate from the view
 * lets the view's internal structure change without breaking hosts — they only
 * ever see this class.
 *
 * Add [view] to your layout, then drive it through [onHostResume] /
 * [onHostPause] and [release].
 */
class TerminalController(context: android.content.Context) {

    /** The view to add to your layout. */
    val view: TerminalView = TerminalView(context)

    var listener: TerminalView.Listener?
        get() = view.listener
        set(value) { view.listener = value }

    /** Where user input goes for a bridged session (the host's transport). */
    var inputSink: ((ByteArray) -> Unit)?
        get() = view.inputSink
        set(value) { view.inputSink = value }

    /** The session behind the view; the host may [EngineSession.configure] it. */
    val session: EngineSession get() = view.session

    /** The IME's focus target; keep it in the hierarchy (1×1 px, inside [view]). */
    val imeTarget: View get() = view.imeTarget

    // --- lifecycle ---------------------------------------------------------

    fun onHostResume() = view.onHostResume()
    fun onHostPause() = view.onHostPause()

    /** Stop the session and release the view. The view is dead after this. */
    fun release() = view.release()

    // --- session control ---------------------------------------------------

    fun start(
        program: String = "/system/bin/sh",
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        cwd: String? = null,
    ): Boolean = view.start(program, args, env, cwd)

    fun restart(): Boolean = view.restart()

    // --- clipboard ---------------------------------------------------------

    fun copySelection(): Boolean = view.copySelection()
    fun pasteClipboard() = view.pasteClipboard()
    fun sendNamedKey(named: Encoder.Named, ctrl: Boolean) = view.sendNamedKey(named, ctrl)

    // --- selection handles -------------------------------------------------

    /** Select the word under (row, col). */
    fun selectWord(row: Int, col: Int) = view.selectionOverlay.selectWord(row, col)

    /** Select the whole line containing (row, col). */
    fun selectLine(row: Int, col: Int) = view.selectionOverlay.selectLine(row, col)

    /** Clear any active selection. */
    fun clearSelection() = view.selectionOverlay.clear()

    /** The currently selected text, or "" when nothing is selected. */
    fun selectionText(): String = view.session.selectionText()

    // --- find hook ---------------------------------------------------------

    /** Find `query` in the visible grid; each result is `row, col, len`. */
    fun find(query: String, limit: Int = 200): List<Triple<Int, Int, Int>> = view.session.search(query, limit)

    // --- zoom (placeholder) ------------------------------------------------

    /**
     * Pinch/drag zoom hook. The engine view does not scale the grid yet; the
     * Termux-compatible `TerminalView.zoomTo` focal anchoring lands here once the
     * renderer supports cell-size changes (tracked in TODO).
     */
    fun zoom(_scale: Float) {
        // Not implemented in the prototype engine view.
    }

    // --- ambient tint from Rust -------------------------------------------

    /** The average background colour of the grid as `[r, g, b]`, or empty if none. */
    fun ambientTint(): IntArray = view.ambientTint()

    // --- introspection -----------------------------------------------------

    fun modes(): Int = view.modes()
    fun mouseMode(): Boolean = view.mouseMode()
    fun appCursor(): Boolean = view.appCursor()
    fun frameStatsSummary(): String = view.frameStatsSummary()
}
