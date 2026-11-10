package dev.drosh.terminal.session

/**
 * One row of the session list: the pane that shows it and the facts the app
 * keeps about it.
 *
 * ## Why the pane is typed to the interface
 *
 * The manager and the session list do not care which pane draws the session,
 * only that it is one it can focus, release and name. Typing the pane to the
 * interface keeps the session grid free of the drawing code; the one place
 * that needs the session behind the pane casts for it.
 *
 * Ported from: mmuhofy/IrisCode — terminal/TerminalManager.kt
 * Adapted for Drosh — dev.drosh
 */
class SessionEntry(
    /** The pane showing this session, when one does. */
    var enginePane: TerminalEngine? = null,
    /** The app's id for the session. */
    val persistentId: String,
    /** The name the sidebar and the grid show. */
    var name: String,
    /** The shell's process id, when the session has one. */
    var pid: Int = 0,
    /** True for the session the app falls back to when there is no other. */
    var isFallbackSession: Boolean = false,
    /**
     * The live session this entry names, when there is one.
     *
     * The session lives in the manager, not in the pane, so that it outlives
     * the pane that showed it: a session that is not on screen keeps running
     * and keeps its output, which is what its thumbnail is made of.
     */
    var liveSession: GhosttySession? = null,
) {
    /**
     * The session behind the pane, or null when the pane has not been
     * registered yet.
     */
    val session: GhosttySession? get() = liveSession
}
