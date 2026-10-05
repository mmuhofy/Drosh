package dev.drosh.domain.workspace

import dev.drosh.domain.session.SessionSnapshot

/** One workspace and the sessions filed under it. */
data class WorkspaceGroup(
    val workspace: Workspace,
    val sessions: List<SessionSnapshot>,
)

/**
 * The workspace screen's whole model: workspaces with their sessions, plus the
 * sessions that belong to none of them.
 *
 * [ungrouped] is a separate field rather than a group with a null workspace,
 * because "not in a project" is a real bucket the user acts on — they file those
 * sessions away or leave them alone — and hiding it inside a list of workspaces
 * would make it the one thing on the screen with no label.
 */
data class WorkspaceBoard(
    val groups: List<WorkspaceGroup> = emptyList(),
    val ungrouped: List<SessionSnapshot> = emptyList(),
) {
    val isEmpty: Boolean get() = groups.isEmpty() && ungrouped.isEmpty()
}

/**
 * Builds a [WorkspaceBoard] from the two independent streams it is derived from.
 *
 * Pure, so the rules below are testable without a database and without Android —
 * which is the point, because three of them are decisions rather than
 * conveniences and would otherwise only be discoverable by running the app.
 *
 * ## The rules
 *
 * 1. **Ordering is the caller's.** Neither input is re-sorted. The workspace
 *    stream is already most-recently-opened first and the session stream
 *    most-recently-used first; re-sorting here would throw away an ordering the
 *    repositories chose deliberately.
 * 2. **An archived workspace is not a workspace.** Archived ones are not in the
 *    list handed in, so their sessions fall through to [ungrouped] — which is
 *    the honest reading, since the group is no longer something the user can see.
 * 3. **A session naming a workspace that is not in the list is ungrouped, not
 *    dropped.** The foreign key is `ON DELETE SET NULL`, so this should not
 *    happen — but a session silently missing from the screen is invisible
 *    breakage, and ungrouped is a bucket the user can see and fix. The same
 *    applies to a session id that matches nothing.
 * 4. **A workspace with no sessions is still shown.** An empty project is a
 *    thing the user made; hiding it would make it impossible to edit or delete
 *    from this screen.
 *
 * Rule 2 has a consequence worth stating on its own, because it is the one that
 * looks like a bug: **archiving a project ungroups its sessions.** The user did
 * not ask to lose that grouping, but they did ask to stop seeing the project, and
 * an archived project shown in the active list would be a group they cannot
 * leave. So the grouping goes and the sessions become visible again rather than
 * disappearing with it.
 */
object WorkspaceGrouping {

    fun board(
        workspaces: List<Workspace>,
        sessions: List<SessionSnapshot>,
    ): WorkspaceBoard {
        val byId = workspaces.associateBy { it.id }
        val buckets = HashMap<String, MutableList<SessionSnapshot>>(workspaces.size)

        val ungrouped = mutableListOf<SessionSnapshot>()
        for (session in sessions) {
            val target = session.workspaceId
            if (target == null || target !in byId) {
                ungrouped += session
            } else {
                buckets.getOrPut(target) { mutableListOf() } += session
            }
        }

        return WorkspaceBoard(
            groups = workspaces.map { WorkspaceGroup(it, buckets[it.id].orEmpty()) },
            ungrouped = ungrouped,
        )
    }
}