package dev.drosh.domain.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SessionRepository {
    fun observeAll(): Flow<List<SessionSnapshot>>
    fun observe(id: String): Flow<SessionSnapshot?>

    /**
     * Create a session row and make it the active one.
     *
     * @param workspaceId file it under this workspace from the start, or leave
     *        it ungrouped. Grouping only — no process is spawned here. The
     *        terminal layer spawns one when it observes the row, which is the
     *        same path an ungrouped session takes.
     */
    suspend fun create(name: String, workspaceId: String? = null): String

    suspend fun rename(id: String, newName: String)
    suspend fun delete(id: String)

    /**
     * Removes every session that has already ended, keeping the active one.
     *
     * A closed session's row doubles as its history, so rows accumulate for as
     * long as the app is used and nothing pruned them. This is the manual sweep;
     * a retention policy would be a better answer but is a product decision.
     */
    suspend fun purgeEnded(keepActiveId: String?)
    suspend fun restoreSession(snapshot: SessionSnapshot, activate: Boolean = false)
    suspend fun touch(id: String)
    suspend fun updateLivePreview(id: String, lines: List<String>)
    suspend fun updateState(id: String, state: SessionState)

    /**
     * File the session under [workspaceId], or ungroup it with null.
     *
     * Grouping only — it creates no session, spawns no process, and does not
     * change [SessionSnapshot.state]. Assigning a session to a project says which
     * list it appears in, not that anything is kept alive for it.
     *
     * An unknown [workspaceId] is refused rather than written: the column is a
     * foreign key, and letting a bad id reach it would turn a UI mistake into a
     * database error at the worst moment.
     */
    suspend fun assignToWorkspace(sessionId: String, workspaceId: String?)

    val shouldExit: StateFlow<Boolean>
    suspend fun setShouldExit(value: Boolean)
}
