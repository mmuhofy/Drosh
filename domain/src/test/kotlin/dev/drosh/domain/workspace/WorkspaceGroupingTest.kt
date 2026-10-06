package dev.drosh.domain.workspace

import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.domain.session.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The board is what the Projects screen renders, and three of its rules are
 * decisions rather than conveniences — an archived project's sessions, a session
 * naming a project that is gone, and a project with nothing in it. None of those
 * are discoverable by reading the screen, so they are pinned here.
 */
class WorkspaceGroupingTest {

    private fun workspace(
        id: String,
        name: String = id,
        archived: Boolean = false,
    ) = Workspace(
        id = id,
        name = name,
        rootPath = "/home/$id",
        description = "",
        colorSeed = 0,
        createdAtMs = 0L,
        lastOpenedAtMs = 0L,
        archived = archived,
    )

    private fun session(
        id: String,
        workspaceId: String? = null,
    ) = SessionSnapshot(
        id = id,
        name = id,
        state = SessionState.Idle,
        createdAtMs = 0L,
        lastUsedAtMs = 0L,
        workspaceId = workspaceId,
    )

    @Test
    fun `sessions land under the workspace they name`() {
        val board = WorkspaceGrouping.board(
            workspaces = listOf(workspace("a"), workspace("b")),
            sessions = listOf(
                session("s1", "a"),
                session("s2", "b"),
                session("s3", "b"),
            ),
        )

        assertEquals(2, board.groups.size)
        assertEquals(listOf("s1"), board.groups[0].sessions.map { it.id })
        assertEquals(listOf("s2", "s3"), board.groups[1].sessions.map { it.id })
        assertTrue(board.ungrouped.isEmpty())
    }

    @Test
    fun `a session with no workspace is ungrouped`() {
        val board = WorkspaceGrouping.board(
            workspaces = listOf(workspace("a")),
            sessions = listOf(session("s1"), session("s2", "a")),
        )

        assertEquals(listOf("s1"), board.ungrouped.map { it.id })
    }

    /**
     * Rule 2, and the one that looks like a bug if you only read the rule.
     *
     * Archiving removes the project from the list the repository hands over —
     * that is `observeAll()` filtering on `archived = 0` — so the board cannot see
     * it and its sessions fall into the ungrouped bucket. The user did not ask to
     * lose that grouping, but they did ask to stop seeing the project, and
     * showing an archived project in the active list would be a group they cannot
     * leave. So the grouping goes and the sessions stay visible.
     *
     * Both halves are asserted: the project is gone from `groups`, and its
     * sessions are still on the board.
     */
    @Test
    fun `archiving a project ungroups its sessions and hides the project`() {
        // "b" is the archived one. Building both projects and then filtering is
        // how the first version of this test read, and it asserted that "a" also
        // vanished — because neither was archived to begin with, so the filter
        // removed both. The archive has to be in the fixture, not introduced later.
        val archivedB = workspace("b", archived = true)
        val all = listOf(workspace("a"), archivedB)
        val sessions = listOf(session("s1", "a"), session("s2", "b"))

        // Before archiving: both projects listed, nothing loose.
        val before = WorkspaceGrouping.board(
            workspaces = listOf(workspace("a"), workspace("b")),
            sessions = sessions,
        )
        assertEquals(2, before.groups.size)
        assertTrue(before.ungrouped.isEmpty())

        // After: `observeAll()` filters on `archived = 0`, so "b" is not returned.
        val visible = all.filterNot { it.archived }
        val after = WorkspaceGrouping.board(workspaces = visible, sessions = sessions)

        assertEquals(listOf("a"), after.groups.map { it.workspace.id })
        assertEquals(listOf("s2"), after.ungrouped.map { it.id })
        // Nothing lost: s1 under "a", s2 loose, and s2 still names "b".
        assertEquals(2, after.groups.flatMap { it.sessions }.size + after.ungrouped.size)
        assertEquals("b", after.ungrouped.single().workspaceId)
    }

    /**
     * Rule 3. The foreign key is ON DELETE SET NULL so this should be
     * unreachable — but a session missing from the screen entirely is invisible
     * breakage, and ungrouped is a bucket the user can see and fix.
     */
    @Test
    fun `a session naming a missing workspace is ungrouped, not dropped`() {
        val board = WorkspaceGrouping.board(
            workspaces = listOf(workspace("a")),
            sessions = listOf(session("s1", "ghost"), session("s2", "a")),
        )

        assertEquals(listOf("s1"), board.ungrouped.map { it.id })
        assertEquals(listOf("s2"), board.groups.single().sessions.map { it.id })
    }

    /** Rule 4: an empty project is something the user made and can still edit. */
    @Test
    fun `a workspace with no sessions is still listed`() {
        val board = WorkspaceGrouping.board(
            workspaces = listOf(workspace("a"), workspace("b")),
            sessions = listOf(session("s1", "a")),
        )

        assertEquals(2, board.groups.size)
        assertEquals("b", board.groups[1].workspace.id)
        assertTrue(board.groups[1].sessions.isEmpty())
    }

    /** Rule 1: both upstream streams already carry a deliberate order. */
    @Test
    fun `the caller's order is preserved on both sides`() {
        val board = WorkspaceGrouping.board(
            workspaces = listOf(workspace("newer"), workspace("older")),
            sessions = listOf(session("s1", "older"), session("s2", "newer")),
        )

        assertEquals(listOf("newer", "older"), board.groups.map { it.workspace.id })
        assertEquals(listOf("s2"), board.groups[0].sessions.map { it.id })
        assertEquals(listOf("s1"), board.groups[1].sessions.map { it.id })
    }

    @Test
    fun `an empty board is empty`() {
        val board = WorkspaceGrouping.board(workspaces = emptyList(), sessions = emptyList())
        assertTrue(board.isEmpty)
    }

    /**
     * Sessions sitting in a workspace the list never mentioned are still visible
     * somewhere, which is the whole point of rule 3 — checked through the public
     * shape rather than a private helper.
     */
    @Test
    fun `every session appears exactly once across the board`() {
        val sessions = listOf(
            session("s1", "a"),
            session("s2", "b"),
            session("s3"),
            session("s4", "ghost"),
        )
        val board = WorkspaceGrouping.board(
            workspaces = listOf(workspace("a"), workspace("b")),
            sessions = sessions,
        )

        val seen = board.groups.flatMap { it.sessions } + board.ungrouped
        assertEquals(sessions.map { it.id }.sorted(), seen.map { it.id }.sorted())
        assertEquals(sessions.size, seen.distinctBy { it.id }.size)
    }
}