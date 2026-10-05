package dev.drosh.domain.terminal

import kotlinx.coroutines.flow.Flow

/**
 * Persistence for [PaneLayout] — the split geometry and which session is in
 * the second pane.
 *
 * Persisted rather than held in a ViewModel because a rotation is not a
 * session boundary: a user who has arranged two panes wants them back after
 * the screen turns, and a ViewModel would drop the arrangement while
 * [TerminalManager][dev.drosh.domain.session.SessionRepository] kept the two
 * shells running.
 *
 * The secondary session id is written here but *validated* by the caller
 * against the live session set. A stored id can name a session that was
 * deleted while the app was closed, and reopening the app must not resurrect a
 * pane bound to it.
 */
interface PaneLayoutRepository {

    /** Hot stream of the stored layout. Emits [PaneLayout.EMPTY] on first launch. */
    val layout: Flow<PaneLayout>

    /** Persists the whole layout at once. */
    suspend fun setLayout(layout: PaneLayout)

    /**
     * Persists only the divider position.
     *
     * Separate from [setLayout] because this is written on every frame of a
     * drag: a DataStore `edit` per pixel would keep rewriting the session id
     * and the float bounds alongside it, for values that did not change.
     */
    suspend fun setSplitFraction(fraction: Float)

    /** Clears the layout — no second pane. */
    suspend fun clear()
}