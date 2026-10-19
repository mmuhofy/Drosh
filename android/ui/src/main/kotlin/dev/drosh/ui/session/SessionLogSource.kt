package dev.drosh.ui.session

/**
 * Where a session's terminal output comes from.
 *
 * ## Why an interface and not a parameter
 *
 * `:ui` cannot see `:terminal` — AGENT.md §139 draws the dependency arrows and
 * this is one of them — so a sheet that lists terminal output cannot ask the
 * terminal manager for it. It can ask *something*, and this is what it asks.
 *
 * The implementation lives in `:app`, which can see both, and is passed in. That
 * is also why the session list arrives as plain [SessionLogTarget]s rather than
 * as the ViewModel's snapshots: the sheet describes the sessions it can take a
 * log from, and how those sessions are stored is not its business.
 */
interface SessionLogSource {

    /**
     * The session's output, oldest line first.
     *
     * Empty for a session with nothing behind it — one that has exited, or one
     * that was never started. Not an exception and not null: a session with no
     * output is an ordinary state, and the sheet has an empty state for it.
     *
     * Called on the main thread while the sheet is open, so it should be a read
     * of a buffer that is already in memory rather than anything that goes and
     * fetches.
     */
    fun lines(sessionId: String): List<String>
}

/**
 * A session that can be taken a log from.
 *
 * Ids rather than sessions: the source resolves them, and nothing in the sheet
 * needs to know what a session *is*.
 */
data class SessionLogTarget(
    val id: String,
    val name: String,
)