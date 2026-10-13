package dev.drosh.ui.workspace

import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.domain.session.SessionState
import dev.drosh.domain.workspace.Workspace
import dev.drosh.domain.workspace.WorkspaceBoard
import dev.drosh.domain.workspace.WorkspaceGroup
import dev.drosh.ui.R
import dev.drosh.ui.settings.GROUP_GAP
import dev.drosh.ui.settings.TileCap
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of the list, without a device.
 *
 * What a filter keeps, when a project opens itself, what corner a row takes and
 * how far above it the next one sits — these are decisions, and a decision made
 * in four renderers is a decision made four times. They live in one pure
 * function, so they are tested here.
 */
class ProjectsRowsTest {

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
        name: String = id,
        workspaceId: String? = null,
        state: SessionState = SessionState.Idle,
    ) = SessionSnapshot(
        id = id,
        name = name,
        state = state,
        createdAtMs = 0L,
        lastUsedAtMs = 0L,
        workspaceId = workspaceId,
    )

    private fun board(
        groups: List<WorkspaceGroup> = emptyList(),
        ungrouped: List<SessionSnapshot> = emptyList(),
    ) = WorkspaceBoard(groups = groups, ungrouped = ungrouped)

    private fun group(workspace: Workspace, sessions: List<SessionSnapshot>) =
        WorkspaceGroup(workspace = workspace, sessions = sessions)

    @Test
    fun `nothing at all is the first run`() {
        val rows = buildProjectRows(board(), archived = emptyList(), expandedIds = emptySet(), filter = "")

        assertEquals(listOf(ProjectsRow.State(ProjectsState.FirstRun)), rows)
    }

    @Test
    fun `a filter that matches nothing says so, rather than showing an empty page`() {
        val board = board(
            groups = listOf(
                group(workspace("api"), listOf(session("shell", workspaceId = "api"))),
            ),
        )

        val rows = buildProjectRows(board, archived = emptyList(), expandedIds = emptySet(), filter = "nope")

        assertEquals(listOf(ProjectsRow.State(ProjectsState.NoResults)), rows)
    }

    @Test
    fun `sessions with no project get the nudge, which the projects section does not`() {
        val board = board(ungrouped = listOf(session("shell")))

        val rows = buildProjectRows(board, archived = emptyList(), expandedIds = emptySet(), filter = "")

        assertEquals(ProjectsState.UnfiledNudge, (rows.first() as ProjectsRow.State).kind)
    }

    @Test
    fun `a project survives the filter when one of its sessions matches`() {
        val board = board(
            groups = listOf(
                group(
                    workspace("api"),
                    listOf(
                        session("shell", workspaceId = "api"),
                        session("web", name = "web", workspaceId = "api"),
                    ),
                ),
            ),
        )

        val rows = buildProjectRows(board, archived = emptyList(), expandedIds = emptySet(), filter = "web")

        val head = rows.filterIsInstance<ProjectsRow.ProjectHead>().single()
        // The filter is an expansion, but only when there is something to show.
        assertTrue(head.expanded)
        val sessions = rows.filterIsInstance<ProjectsRow.Session>()
        assertEquals(listOf("web"), sessions.map { it.session.name })
        // The count is the project's own, not the filter's.
        assertEquals(2, head.sessionCount)
    }

    @Test
    fun `a project that matches by name only stays closed`() {
        val board = board(
            groups = listOf(
                group(
                    workspace("api"),
                    listOf(session("shell", workspaceId = "api")),
                ),
            ),
        )

        val rows = buildProjectRows(board, archived = emptyList(), expandedIds = emptySet(), filter = "api")

        val head = rows.filterIsInstance<ProjectsRow.ProjectHead>().single()
        assertFalse(head.expanded)
        assertEquals(TileCap.Only, head.cap)
    }

    @Test
    fun `expansion is the user's, and is remembered`() {
        val board = board(
            groups = listOf(
                group(workspace("api"), listOf(session("shell", workspaceId = "api"))),
                group(workspace("web"), listOf(session("serve", workspaceId = "web"))),
            ),
        )

        val closed = buildProjectRows(board, archived = emptyList(), expandedIds = emptySet(), filter = "")
        assertEquals(2, closed.filterIsInstance<ProjectsRow.ProjectHead>().size)
        assertTrue(closed.none { it is ProjectsRow.Session })

        val open = buildProjectRows(board, archived = emptyList(), expandedIds = setOf("web"), filter = "")
        val sessions = open.filterIsInstance<ProjectsRow.Session>()
        assertEquals(listOf("serve"), sessions.map { it.session.name })
    }

    @Test
    fun `an open card is one run of corners, and the next card starts a new one`() {
        val board = board(
            groups = listOf(
                group(
                    workspace("api"),
                    listOf(
                        session("shell", workspaceId = "api"),
                        session("web", workspaceId = "api"),
                    ),
                ),
                group(workspace("docs"), listOf(session("serve", workspaceId = "docs"))),
            ),
        )

        val rows = buildProjectRows(
            board,
            archived = emptyList(),
            expandedIds = setOf("api", "docs"),
            filter = "",
        )

        val caps = rows.filterIsInstance<ProjectsRow.ProjectHead>().map { it.cap }
        assertEquals(listOf(TileCap.First, TileCap.First), caps)
        val sessionCaps = rows.filterIsInstance<ProjectsRow.Session>().map { it.cap }
        // Two under api, one under docs: Middle, Last, Last.
        assertEquals(listOf(TileCap.Middle, TileCap.Last, TileCap.Last), sessionCaps)
    }

    @Test
    fun `gaps - nothing inside a run, four between runs, a section break between sections`() {
        val board = board(
            groups = listOf(
                group(workspace("api"), listOf(session("shell", workspaceId = "api"))),
                group(workspace("web"), listOf(session("serve", workspaceId = "web"))),
            ),
            ungrouped = listOf(session("loose")),
        )

        val rows = buildProjectRows(
            board,
            archived = listOf(workspace("old", archived = true)),
            expandedIds = setOf("api", "web"),
            filter = "",
        )

        val byKey = rows.associateBy { it.key }
        // The first row of the screen has nothing above it.
        assertEquals(0.dp, byKey.getValue("section_${R.string.projects_section_projects}").gapAbove)
        // A second project in the same section continues the rhythm.
        assertEquals(4.dp, byKey.getValue("project_web").gapAbove)
        // A new section breaks it.
        assertEquals(
            GROUP_GAP,
            byKey.getValue("section_${R.string.projects_section_unfiled}").gapAbove,
        )
        // Sessions inside a run have no gap of their own.
        assertEquals(0.dp, byKey.getValue("session_shell").gapAbove)
        assertEquals(0.dp, byKey.getValue("session_loose").gapAbove)
    }

    @Test
    fun `keys are unique, which is what lets the list keep its scroll position`() {
        val board = board(
            groups = listOf(
                group(
                    workspace("api"),
                    listOf(
                        session("shell", workspaceId = "api"),
                        session("web", workspaceId = "api"),
                    ),
                ),
            ),
            ungrouped = listOf(session("loose"), session("older")),
        )

        val rows = buildProjectRows(
            board,
            archived = listOf(workspace("old", archived = true), workspace("older", archived = true)),
            expandedIds = setOf("api"),
            filter = "",
        )

        val keys = rows.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `an archived project counts the sessions that read as unfiled`() {
        val board = board(
            groups = listOf(group(workspace("api"), listOf(session("shell", workspaceId = "api")))),
            ungrouped = listOf(
                session("loose"),
                session("orphan", workspaceId = "old"),
                session("orphan2", workspaceId = "old"),
            ),
        )

        val rows = buildProjectRows(
            board,
            archived = listOf(workspace("old", name = "old", archived = true)),
            expandedIds = emptySet(),
            filter = "",
        )

        val archived = rows.filterIsInstance<ProjectsRow.Archived>().single()
        assertEquals(2, archived.looseCount)
        // Archiving is not deleting: every session is still a row somewhere.
        assertEquals(4, rows.filterIsInstance<ProjectsRow.Session>().size)
    }

    @Test
    fun `an ended session is still a row, and still says nothing about a shell`() {
        val board = board(
            ungrouped = listOf(session("shell", state = SessionState.Closed)),
        )

        val rows = buildProjectRows(board, archived = emptyList(), expandedIds = emptySet(), filter = "")

        val session = rows.filterIsInstance<ProjectsRow.Session>().single()
        assertEquals(SessionState.Closed, session.session.state)
    }
}
