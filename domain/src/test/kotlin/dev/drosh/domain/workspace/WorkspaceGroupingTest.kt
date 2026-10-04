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
    ) = Workspace(
        id = id,
        name = name,
        rootPath = "/home/$id",
        description = "",
        colorSeed = 0,
        createdAtMs = 0L,
        lastOpenedAtMs = 0L,
        archived = false,
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
     * Rule 2: an archived workspace is not in the list the repository hands over,
     * so its sessions fall through. That is the honest reading — the group is
     * gone as far as the user can see, so pretending the session still belongs to
     * something would be worse than showing it loose.
     */
    @Test
    fun `a session in an archived workspace becomes ungrouped`() {
        val board = WorkspaceGrouping.board(
            workspaces = listOf(workspace("a")),
            sessions = listOf(session("s1", "old"), session("s2", "a")),
        )

        assertEquals(listOf("s1"), board.ungrouped.map { it.id })
        assertEquals(listOf("s2"), board.groups.single().sessions.map { it.id })
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