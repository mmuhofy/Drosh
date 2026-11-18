package dev.drosh.domain.session

import kotlinx.coroutines.flow.StateFlow

/**
 * Which sessions the user pinned to survive a full app teardown.
 *
 * Default is off for every session: a fresh install has an empty set, and a
 * session becomes durable only when [setPersistent] is called for it. Backed by
 * DataStore on the data side — no Room migration, because the flag is not part
 * of a session row.
 */
interface PersistentSessionStore {
    val persistentIds: StateFlow<Set<String>>

    /** Read straight from the backing store, for teardown code that cannot wait on a flow. */
    suspend fun snapshot(): Set<String>

    suspend fun setPersistent(sessionId: String, enabled: Boolean)
}
