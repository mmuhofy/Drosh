package dev.drosh.domain.session

import kotlinx.coroutines.flow.StateFlow

/**
 * How the session grid arranges its cards: the pinned set, the drag order and
 * the UI groups.
 *
 * ## Why it is not UI state
 *
 * All three used to live in `remember` inside the grid, so pinning a session or
 * grouping two of them survived until the grid left the composition and then
 * silently undid itself. They are the user's arrangement of their own sessions,
 * so they belong beside the sessions, not inside one screen's lifetime.
 *
 * Groups are stored as membership lists, not as a type: the domain has no
 * opinion about how the grid paints a group, and a list of ids is the same
 * thing the grid keeps.
 */
data class SessionGridLayout(
    val pinnedIds: Set<String> = emptySet(),
    val orderedIds: List<String> = emptyList(),
    val groups: List<List<String>> = emptyList(),
) {
    companion object {
        val EMPTY = SessionGridLayout()
    }
}

/**
 * Persisted grid arrangement.
 *
 * `layout` is null until the backing store has been read: a caller must not
 * treat the first emission as "the user has no pins" and write that back over
 * a saved arrangement.
 */
interface SessionGridLayoutStore {
    val layout: StateFlow<SessionGridLayout?>

    suspend fun save(layout: SessionGridLayout)
}
