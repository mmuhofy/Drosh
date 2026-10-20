package dev.drosh.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.theme.DroshBackground
import dev.drosh.theme.DroshOutline
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurface
import dev.drosh.theme.DroshSurfaceHigh
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.DroshTile
import dev.drosh.theme.DroshTileSelected
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.domain.session.SessionState
import dev.drosh.domain.workspace.Workspace
import dev.drosh.domain.workspace.WorkspaceBoard
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.ui.agent.components.ActionButton
import dev.drosh.ui.agent.components.IconAction
import dev.drosh.ui.agent.components.TOUCH_TARGET
import dev.drosh.ui.keyboard.droshImePadding
import dev.drosh.ui.settings.GROUP_GAP
import dev.drosh.ui.settings.SettingsTopBar
import dev.drosh.ui.settings.TileCap
import dev.drosh.ui.settings.tileCapFor
import dev.drosh.ui.settings.tileShape
import dev.drosh.ui.workspace.components.WorkspaceAssignSheet
import dev.drosh.ui.workspace.components.WorkspaceEditSheet
import dev.drosh.ui.workspace.components.workspaceSeedColor

/**
 * Projects — every workspace, with the sessions filed under each.
 *
 * ## Why the list is workspaces-first
 *
 * The screen exists to answer "which project am I in, and which sessions belong
 * to it". Ungrouped sessions come last rather than being merged in, because they
 * have no project and folding them in would imply one.
 *
 * ## Why nothing here says "running"
 *
 * A workspace does not own a process and neither does a session row, so a live
 * badge on this screen would be describing something the app cannot promise. What
 * it shows instead is *ended* or not, which is a fact about the record rather
 * than a claim about a shell. See `docs/MEMORYBANK.md` §9.
 *
 * ## Why it is drawn in the settings language
 *
 * It used to be six styles of card: a rounded slab with an accent bar for a
 * project, another slab for the ungrouped remainder, a third for the archive,
 * each with its own corner radius and its own spacing. The screen you arrive at
 * from Settings now speaks the language you left: flat tiles grouped by their
 * corner radius, no strokes, the same 24dp/4dp rhythm and the same large top bar
 * with the agent's back pill. One list of objects that runs, rather than a
 * stack of unrelated containers.
 *
 * ## Why the list is flat
 *
 * Every row — a project, a session filed under it, an ungrouped session, an
 * archived project, a section label — is one item in one `LazyColumn` with a
 * stable key. A card containing a `Column` of its sessions only composes the
 * visible cards, but it *lays out* every row of an open one, and a project with
 * two hundred sessions would spend the frame its tap was measured in laying out
 * two hundred rows. Flattened, on screen is all there is.
 *
 * ## Why there is no working directory
 *
 * The directory a session starts in is a choice about that session, and it is
 * made in the agent sheet where the session is made. A folder field on this
 * screen read as "your shells run here", which the domain says they never did.
 */
@Composable
fun WorkspaceScreen(
    onBack: () -> Unit,
    /** Open a session and leave this screen for the terminal. */
    onOpenSession: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorkspaceViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activeId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val assigningId by viewModel.assigningSessionId.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    // Which groups are open, surviving rotation and the trip to the terminal and
    // back — `remember` alone reset both, and a list that folds itself up every
    // time you come back from opening a session is a list you stop trusting.
    var expandedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var filter by rememberSaveable { mutableStateOf("") }

    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // A failed write is reported here rather than swallowed. The action that
    // failed is offered again as Retry, because the write did not happen and
    // pretending otherwise — which is what a silently-closed sheet does — is the
    // one outcome worse than an error message.
    LaunchedEffect(notice) {
        val current = notice ?: return@LaunchedEffect
        // While the sheet is up, the message goes in the sheet instead: a modal
        // sheet is a window of its own, and a snackbar on this screen would be
        // behind it, where nobody who pressed the button can see it.
        if (editing != null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = context.getString(current.messageRes),
            actionLabel = context.getString(R.string.action_retry),
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.retry()
        viewModel.dismissNotice()
    }

    // Built per input, not per frame. The keys are what make the list cheap to
    // scroll, and they only stay stable if the list identity does.
    //
    // A filter is an expansion. While one is typed, a project shows the sessions
    // that match it whether or not it was open, because the search *is* the
    // request to see inside — a filter that leaves you tapping a chevron to find
    // out whether the thing you searched for is in there is a filter you use
    // twice.
    val rows = remember(state.board, state.archived, state.loading, expandedIds, filter) {
        if (state.loading) {
            listOf(ProjectsRow.State(ProjectsState.Loading))
        } else {
            buildProjectRows(state.board, state.archived, expandedIds.toSet(), filter)
        }
    }

    // Resolved from the board rather than kept as its own state, so a sheet left
    // open for a session that has since been deleted finds nothing and simply
    // does not appear. Both lists have to be walked — the board is workspaces
    // and the loose remainder, not one list.
    val assigningSession = remember(state.board, assigningId) {
        state.board.allSessions().firstOrNull { it.id == assigningId }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground)
            .droshImePadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // The same top bar the settings screen leads with — same large
            // title, same agent back pill — because this screen is reached from
            // Settings and the control that leaves it should be the control that
            // was pressed to arrive.
            // The same clearance the settings screens take: the background runs
            // under the system bar and the content is pushed clear of it, so the
            // screen does not read as having a strip of its own.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues()
                            .calculateTopPadding(),
                    ),
            ) {
                SettingsTopBar(
                    title = stringResource(R.string.projects_title),
                    onBack = onBack,
                )

                // What is on the screen, stated before the list. Hidden on the
                // empty and the loading screens, where a count of nothing above
                // a message about nothing is the same sentence twice.
                if (!state.loading && !state.board.isEmpty) {
                    ProjectsMeta(
                        projects = state.board.groups.size,
                        sessions = state.board.groups.sumOf { it.sessions.size } +
                            state.board.ungrouped.size,
                        filter = filter,
                        onFilterChange = { filter = it },
                        onFilterDone = { focusManager.clearFocus() },
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 10.dp,
                    // The bar at the bottom of the screen is a floating action
                    // for the list, not part of it: the last row scrolls clear
                    // of it instead of under it.
                    bottom = 104.dp,
                ),
            ) {
                items(rows, key = { it.key }) { row ->
                    ProjectsRowItem(
                        row = row,
                        activeSessionId = activeId,
                        onToggle = { id -> expandedIds = expandedIds.toggling(id) },
                        onEdit = viewModel::startEdit,
                        onNewSession = { workspace ->
                            viewModel.createSessionIn(workspace)
                            onOpenSession()
                        },
                        onOpenSession = { session, workspaceId ->
                            filter = ""
                            viewModel.openSession(session, workspaceId)
                            onOpenSession()
                        },
                        onAssign = viewModel::startAssigning,
                        onRestore = viewModel::restore,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(DroshSurface)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(horizontal = 4.dp),
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = DroshSurfaceHigh,
                    contentColor = DroshText,
                    actionColor = DroshPrimary,
                    shape = RoundedCornerShape(12.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            // The only place a project is created from, and the one action that
            // is always available — including on the empty screen, where it is
            // the way out. No second copy at the top of the list: two controls
            // for one thing reads as neither being sure which one to press.
            ActionButton(
                text = stringResource(R.string.projects_action_new_project),
                onClick = viewModel::startCreate,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    editing?.let { editor ->
        WorkspaceEditSheet(
            state = editor,
            onDraftChange = viewModel::updateDraft,
            onSave = viewModel::saveEditor,
            onDelete = viewModel::deleteEditorTarget,
            onToggleArchive = viewModel::toggleArchiveEditorTarget,
            onDismiss = {
                viewModel.dismissEditor()
                // Leaving the sheet takes its error with it: a message about a
                // write the user has walked away from is a message about nothing,
                // and it would otherwise greet them the next time they open one.
                viewModel.dismissNotice()
            },
            // A failure while the sheet is open is the sheet's to report, and
            // it clears when the sheet does.
            errorMessage = notice?.messageRes,
        )
    }

    assigningSession?.let { session ->
        WorkspaceAssignSheet(
            sessionName = session.name,
            currentWorkspaceId = session.workspaceId,
            workspaces = state.board.groups.map { it.workspace },
            onSelect = { workspaceId -> viewModel.assign(session.id, workspaceId) },
            onCreateProject = {
                // Filing a session is how people discover projects exist, so the
                // sheet that lists them has to be able to make one. Otherwise it
                // says "create a project first" and leaves them with nowhere to
                // go from here.
                viewModel.dismissAssigning()
                viewModel.startCreate()
            },
            onDismiss = viewModel::dismissAssigning,
        )
    }
}

// --------------------------------------------------------------------- rows

/**
 * One line of the list.
 *
 * Internal rather than private because the row builder it feeds is internal and
 * tested: a type a test inspects cannot be a type the file keeps to itself.
 *
 * A sealed set rather than a nested composable per section: the whole screen is
 * one lazy list, and what a row is decides its key, its corner and the gap
 * above it. All three are decided when the list is built, so the renderers have
 * nothing to work out and no two of them can disagree about the spacing.
 */
internal sealed interface ProjectsRow {
    val key: String

    /** Space above this row: 0 inside a run, 4dp between runs, 22dp between sections. */
    val gapAbove: Dp

    data class ProjectHead(
        val workspace: Workspace,
        /** Sessions the project holds in total, whatever the filter kept. */
        val sessionCount: Int,
        val expanded: Boolean,
        val cap: TileCap,
        override val gapAbove: Dp,
    ) : ProjectsRow {
        override val key: String get() = "project_${workspace.id}"
    }

    data class Session(
        val session: SessionSnapshot,
        /** Null for a session in no project, which owns no accent. */
        val colorSeed: Int?,
        val cap: TileCap,
        override val gapAbove: Dp,
    ) : ProjectsRow {
        override val key: String get() = "session_${session.id}"
    }

    data class SectionLabel(
        val labelRes: Int,
        override val gapAbove: Dp,
    ) : ProjectsRow {
        override val key: String get() = "section_$labelRes"
    }

    data class Archived(
        val workspace: Workspace,
        /** Sessions that point at this project and are shown as unfiled. */
        val looseCount: Int,
        val cap: TileCap,
        override val gapAbove: Dp,
    ) : ProjectsRow {
        override val key: String get() = "archived_${workspace.id}"
    }

    /** The whole list is one of these four things, and nothing else renders. */
    data class State(
        val kind: ProjectsState,
        override val gapAbove: Dp = 0.dp,
    ) : ProjectsRow {
        override val key: String get() = "state_${kind.name}"
    }
}

internal enum class ProjectsState { Loading, FirstRun, UnfiledNudge, NoResults }

private enum class Section { Projects, Unfiled, Archive }

/**
 * Flatten the board into the rows of one list.
 *
 * Internal rather than private because the rules that decide what appears —
 * which rows a filter keeps, when a project opens itself, what a corner and a
 * gap are — are the part of this screen most worth testing, and a pure function
 * is a testable one. `ProjectsRowsTest` covers them without a device.
 *
 * Pure, so the rules can be read in one place rather than spread across four
 * renderers that each re-derive them.
 *
 * A session appears exactly once: under its project, or in the ungrouped
 * section. That is what keeps the keys unique, and it is also the truth — a
 * session filed under a project is not simultaneously ungrouped.
 */
internal fun buildProjectRows(
    board: WorkspaceBoard,
    archived: List<Workspace>,
    expandedIds: Set<String>,
    filter: String,
): List<ProjectsRow> = buildList {
    val query = filter.trim()
    val filtering = query.isNotEmpty()

    fun matches(text: String) = !filtering || text.contains(query, ignoreCase = true)

    // A project survives the filter if its name matches or any of its sessions
    // do. Either way the project is where the match lives, and a result that
    // shows the session but not its project tells you nothing.
    val groups = board.groups.mapNotNull { group ->
        val projectMatches = matches(group.workspace.name)
        val sessions = group.sessions.filter { matches(it.name) }
        if (projectMatches || sessions.isNotEmpty()) group to sessions else null
    }
    val unfiled = board.ungrouped.filter { matches(it.name) }
    val archivedMatches = archived.filter { matches(it.name) }

    // Nothing at all is the first run, and it is a different message from
    // "nothing matched": one is an empty app, the other is a search.
    if (!filtering && board.groups.isEmpty() && board.ungrouped.isEmpty() &&
        archived.isEmpty()
    ) {
        add(ProjectsRow.State(ProjectsState.FirstRun))
        return@buildList
    }
    if (groups.isEmpty() && unfiled.isEmpty() && archivedMatches.isEmpty()) {
        add(ProjectsRow.State(ProjectsState.NoResults))
        return@buildList
    }

    // Sessions exist and no project does — which is the state the app is in by
    // default, because a launch creates a Default session whether or not you
    // ever make a project. The projects section is absent, so this says the one
    // thing the list does not: what to do about it.
    if (!filtering && board.groups.isEmpty() && unfiled.isNotEmpty()) {
        add(ProjectsRow.State(ProjectsState.UnfiledNudge))
    }

    // One pass, not one per archived row. The count used to walk the whole board
    // for every archived project, which is the same list walked as many times as
    // there are archived projects.
    val looseCounts = board.allSessions().groupingBy { it.workspaceId }.eachCount()

    var section: Section? = null
    var afterLabel = false

    /** A run continues the one before it; a section starts a new one. */
    fun gapFor(next: Section, isLabel: Boolean): Dp = when {
        section == null -> 0.dp
        isLabel -> GROUP_GAP
        afterLabel -> 0.dp
        section == next -> 4.dp
        else -> GROUP_GAP
    }

    fun addLabel(labelRes: Int, next: Section) {
        add(ProjectsRow.SectionLabel(labelRes, gapFor(next, isLabel = true)))
        section = next
        afterLabel = true
    }

    if (groups.isNotEmpty()) {
        addLabel(R.string.projects_section_projects, Section.Projects)
        groups.forEach { (group, sessions) ->
            // A filter opens a project only when it has something to show in
            // it. Outside a filter the user's own expansion decides, and it is
            // remembered across the trip to the terminal and back — an
            // expansion the filter would force on and then take away is an
            // expansion you cannot trust.
            val open = (filtering && sessions.isNotEmpty()) ||
                group.workspace.id in expandedIds
            val showsSessions = open && sessions.isNotEmpty()
            add(
                ProjectsRow.ProjectHead(
                    workspace = group.workspace,
                    // The project's own count, not the filter's: "3 sessions"
                    // under a search that kept one of them is a fact about the
                    // project, and the row below shows what matched.
                    sessionCount = group.sessions.size,
                    expanded = open,
                    cap = if (showsSessions) TileCap.First else TileCap.Only,
                    gapAbove = gapFor(Section.Projects, isLabel = false).also {
                        section = Section.Projects
                        afterLabel = false
                    },
                ),
            )
            if (showsSessions) {
                // Index 0 of the run is the head, so the sessions start at 1.
                sessions.forEachIndexed { index, session ->
                    add(
                        ProjectsRow.Session(
                            session = session,
                            colorSeed = group.workspace.colorSeed,
                            cap = tileCapFor(index + 1, sessions.size + 1),
                            gapAbove = 0.dp,
                        ),
                    )
                }
            }
        }
    }

    if (unfiled.isNotEmpty()) {
        addLabel(R.string.projects_section_unfiled, Section.Unfiled)
        unfiled.forEachIndexed { index, session ->
            add(
                ProjectsRow.Session(
                    session = session,
                    colorSeed = null,
                    cap = tileCapFor(index, unfiled.size),
                    gapAbove = 0.dp,
                ),
            )
        }
    }

    // Archived last and out of the way. It is here because deleting a project is
    // not something to make people do to stop seeing it — archiving keeps the
    // grouping recoverable and the row intact.
    if (archivedMatches.isNotEmpty()) {
        addLabel(R.string.projects_section_archive, Section.Archive)
        archivedMatches.forEachIndexed { index, workspace ->
            add(
                ProjectsRow.Archived(
                    workspace = workspace,
                    looseCount = looseCounts[workspace.id] ?: 0,
                    cap = tileCapFor(index, archivedMatches.size),
                    gapAbove = 0.dp,
                ),
            )
        }
    }
}

/** Hands each row to the renderer that draws it. */
@Composable
private fun ProjectsRowItem(
    row: ProjectsRow,
    activeSessionId: String?,
    onToggle: (String) -> Unit,
    onEdit: (Workspace) -> Unit,
    onNewSession: (Workspace) -> Unit,
    onOpenSession: (SessionSnapshot, String?) -> Unit,
    onAssign: (String) -> Unit,
    onRestore: (String) -> Unit,
) {
    when (row) {
        is ProjectsRow.ProjectHead -> ProjectHeadRow(
            row = row,
            onToggle = { onToggle(row.workspace.id) },
            onEdit = { onEdit(row.workspace) },
            onNewSession = { onNewSession(row.workspace) },
        )

        is ProjectsRow.Session -> FiledSessionRow(
            row = row,
            active = row.session.id == activeSessionId,
            onOpen = { onOpenSession(row.session, row.session.workspaceId) },
            onAssign = { onAssign(row.session.id) },
        )

        is ProjectsRow.SectionLabel -> ProjectsSectionLabel(row)

        is ProjectsRow.Archived -> ArchivedProjectRow(
            row = row,
            onEdit = { onEdit(row.workspace) },
            onRestore = { onRestore(row.workspace.id) },
        )

        is ProjectsRow.State -> ProjectsStateRow(row.kind)
    }
}

// ---------------------------------------------------------------- renderers

/**
 * One project: its accent, name, session count and note.
 *
 * The header row is the toggle, and the `⋯` beside it opens the editor — the
 * inner control consumes its own tap, so the two do not fight.
 *
 * The accent is the folder icon's tint rather than a bar down the side of a
 * card. This screen has no cards: the colour has to live on something that is
 * already there, and the icon is the one object that means "project" in every
 * section of the app.
 *
 * The `⋯` is a sibling of the clickable row rather than a child of it. Inside,
 * it would be swallowed by the row's merged description — reachable by no one,
 * announced by no one. As a sibling it keeps its own 44dp target and its own
 * label, and the row still reads as one line.
 */
@Composable
private fun ProjectHeadRow(
    row: ProjectsRow.ProjectHead,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onNewSession: () -> Unit,
) {
    val workspace = row.workspace
    val accent = workspaceSeedColor(workspace.colorSeed)
    val countLabel = sessionCountLabel(row.sessionCount)
    val toggleLabel = stringResource(
        if (row.expanded) R.string.projects_action_collapse else R.string.projects_action_expand,
    )
    val editLabel = stringResource(R.string.projects_edit_named_cd, workspace.name)

    Column(
        modifier = Modifier
            .padding(top = row.gapAbove)
            .fillMaxWidth()
            .clip(tileShape(row.cap))
            .background(DroshTile),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 60.dp)
                    .clickable(onClick = onToggle)
                    // The `⋯` occupies the end of the row, so the clickable area
                    // stops short of it rather than under it. A tap on the button
                    // is a tap on the button.
                    .padding(start = 14.dp, end = 56.dp, top = 10.dp, bottom = 10.dp)
                    // One description for the whole row. A screen reader reading
                    // the name, the count and the state as three fragments does
                    // not say "myapp, 3 sessions, expand" — it says them as
                    // separate things.
                    .semantics(mergeDescendants = true) {
                        contentDescription = buildString {
                            append(workspace.name)
                            append(", ")
                            append(countLabel)
                            append(", ")
                            append(toggleLabel)
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = DroshIcons.Folder,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier
                        .size(22.dp)
                        .clearAndSetSemantics { },
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = workspace.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DroshText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = countLabel,
                        fontSize = 12.5.sp,
                        color = DroshTextMuted,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    if (workspace.description.isNotEmpty()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = workspace.description,
                            fontSize = 12.5.sp,
                            lineHeight = 17.sp,
                            color = DroshTextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clearAndSetSemantics { },
                        )
                    }
                }
                Icon(
                    imageVector = if (row.expanded) DroshIcons.ChevronDown else DroshIcons.ChevronRight,
                    contentDescription = null,
                    tint = DroshTextMuted,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(18.dp)
                        .clearAndSetSemantics { },
                )
            }

            IconAction(
                icon = DroshIcons.EllipsisVertical,
                contentDescription = editLabel,
                onClick = onEdit,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .size(44.dp),
                tint = DroshTextMuted,
            )
        }

        // Always available while the card is open. A project you just made looks
        // identical to one you abandoned — a name, "no sessions" — and this is
        // the way out of that ambiguity; below a project that already has
        // sessions it is the second way to add one, which a list you have to
        // leave to use is not.
        if (row.expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNewSession)
                    .heightIn(min = TOUCH_TARGET)
                    .padding(start = 14.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = DroshIcons.Plus,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = stringResource(R.string.projects_new_session_in),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = accent,
                )
            }
        }
    }
}

/**
 * One session, filed under a project or in no project.
 *
 * The only thing the row claims is whether the record has **ended** — and that is
 * a fact about the row. It says nothing about a live shell, because a persisted
 * `Running` is not one: `SessionState.Running` is written when a PTY spawns and
 * nothing rewrites it if the process dies with the app, so "running" here would
 * be a claim the app cannot keep. `docs/SESSION-SYSTEM.md` §2 has the same
 * distinction — the state field is the resume marker, not a liveness report.
 *
 * Moving a session to another project has a visible button *and* a long press.
 * The gesture alone was the first version and it was invisible: nothing on the
 * row hinted that pressing and holding did anything, so the feature only existed
 * for people who happened to try it.
 */
@Composable
private fun FiledSessionRow(
    row: ProjectsRow.Session,
    active: Boolean,
    onOpen: () -> Unit,
    onAssign: () -> Unit,
) {
    val session = row.session
    val ended = session.state == SessionState.Closed
    val activeLabel = stringResource(R.string.projects_session_active)
    val endedLabel = stringResource(R.string.projects_session_ended)
    val moveLabel = stringResource(R.string.projects_session_move_hint)
    val moveButtonLabel = stringResource(R.string.projects_move_named_cd, session.name)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tileShape(row.cap))
            .background(if (active) DroshTileSelected else DroshTile),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TOUCH_TARGET)
                .combinedClickable(onClick = onOpen, onLongClick = onAssign)
                .padding(start = 16.dp, end = 56.dp, top = 6.dp, bottom = 6.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append(session.name)
                        if (active) append(", ").append(activeLabel)
                        if (ended) append(", ").append(endedLabel)
                        append(", ").append(moveLabel)
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = session.name,
                fontSize = 13.5.sp,
                color = if (ended) DroshTextMuted else DroshTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp)
                    .clearAndSetSemantics { },
            )
            if (active || ended) {
                Text(
                    text = if (active) activeLabel else endedLabel,
                    fontSize = 10.sp,
                    color = if (active) DroshPrimary else DroshTextMuted,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .clearAndSetSemantics { },
                )
            }
        }

        IconAction(
            icon = DroshIcons.FolderOpen,
            contentDescription = moveButtonLabel,
            onClick = onAssign,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
                .size(44.dp),
            tint = DroshTextMuted,
        )
    }
}

/** The all-caps label above a section, in the settings groups' own style. */
@Composable
private fun ProjectsSectionLabel(row: ProjectsRow.SectionLabel) {
    Text(
        text = stringResource(row.labelRes).uppercase(),
        color = DroshTextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.9.sp,
        modifier = Modifier.padding(start = 6.dp, top = row.gapAbove, bottom = 5.dp),
    )
}

/**
 * An archived project: one row, and the two things worth doing to one.
 *
 * Restore first. An archived project is one the user stepped away from, not one
 * they threw out, and making them retype the name to get it back would turn
 * archiving into a trap.
 *
 * The row itself opens the editor, which is where rename and delete live — an
 * archived project is still a project, and a row you cannot act on except by
 * un-archiving it is a row that traps you.
 *
 * [ProjectsRow.Archived.looseCount] is the consequence of archiving, stated on
 * the row that caused it. Archiving hides the project and the grouping stops
 * being displayed, so those sessions turn up in the ungrouped section — which,
 * read on its own, looks like the archive action destroyed the grouping. It did
 * not; saying the number here is cheaper than the user having to work that out.
 * Phrased without a position on screen, which would go stale the moment the list
 * scrolls.
 */
@Composable
private fun ArchivedProjectRow(
    row: ProjectsRow.Archived,
    onEdit: () -> Unit,
    onRestore: () -> Unit,
) {
    val workspace = row.workspace
    val accent = workspaceSeedColor(workspace.colorSeed)
    val looseLabel = if (row.looseCount == 0) {
        null
    } else {
        stringResource(R.string.projects_archived_loose, row.looseCount)
    }
    val editLabel = stringResource(R.string.projects_edit_named_cd, workspace.name)

    Box(
        modifier = Modifier
            .padding(top = row.gapAbove)
            .fillMaxWidth()
            .clip(tileShape(row.cap))
            .background(DroshTile)
            .heightIn(min = 56.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .padding(start = 14.dp, end = 56.dp, top = 8.dp, bottom = 8.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append(workspace.name)
                        if (looseLabel != null) append(", ").append(looseLabel)
                        append(", ").append(editLabel)
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.5f))
                    .clearAndSetSemantics { },
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = workspace.name,
                    fontSize = 13.5.sp,
                    color = DroshTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clearAndSetSemantics { },
                )
                if (looseLabel != null) {
                    Text(
                        text = looseLabel,
                        fontSize = 11.sp,
                        color = DroshTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
        }

        IconAction(
            icon = DroshIcons.RotateCcw,
            contentDescription = stringResource(
                R.string.projects_restore_named_cd,
                workspace.name,
            ),
            onClick = onRestore,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
                .size(44.dp),
            tint = DroshTextMuted,
        )
    }
}

/**
 * The whole screen is one of four things.
 *
 * Two of them are empty states, and they are two because "nothing here" and "you
 * have sessions but none of them are filed" are different situations and only the
 * second one needs telling what to do about it — the first is a first run, the
 * second is the state the app is in by default, because a launch creates a
 * Default session.
 */
@Composable
private fun ProjectsStateRow(kind: ProjectsState) {
    when (kind) {
        ProjectsState.Loading -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = DroshTextMuted,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.projects_loading),
                fontSize = 12.sp,
                color = DroshTextMuted,
            )
        }

        ProjectsState.FirstRun, ProjectsState.UnfiledNudge -> {
            val isNudge = kind == ProjectsState.UnfiledNudge
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = DroshIcons.Folder,
                    contentDescription = null,
                    tint = DroshOutline,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(
                        if (isNudge) R.string.projects_unfiled_title
                        else R.string.projects_empty_title,
                    ),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = DroshText,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        if (isNudge) R.string.projects_unfiled_body
                        else R.string.projects_empty_body,
                    ),
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = DroshTextMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }

        ProjectsState.NoResults -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = DroshIcons.Search,
                contentDescription = null,
                tint = DroshOutline,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.projects_no_results),
                fontSize = 13.sp,
                color = DroshTextMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// -------------------------------------------------------------------- chrome

/**
 * What is on the screen, and the way to narrow it.
 *
 * The count line sits above the field rather than inside the list: it is a fact
 * about the screen, not one of its rows, and a fact that scrolls away is a fact
 * you have to scroll back for.
 */
@Composable
private fun ProjectsMeta(
    projects: Int,
    sessions: Int,
    filter: String,
    onFilterChange: (String) -> Unit,
    onFilterDone: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.projects_summary, projects, sessions),
            fontSize = 12.sp,
            color = DroshTextMuted,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        ProjectsFilterField(
            value = filter,
            onValueChange = onFilterChange,
            onDone = onFilterDone,
        )
    }
}

/**
 * One field, filtering projects and the sessions under them.
 *
 * Always a field, never a pill that turns into one. The drawer's footer swaps the
 * two because a focused text field resizes the drawer it lives in; this is a
 * screen, there is nothing to resize, and a target that moves when you touch it
 * is a target you miss.
 */
@Composable
private fun ProjectsFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
) {
    val focusManager = LocalFocusManager.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(DroshTile)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = DroshIcons.Search,
            contentDescription = null,
            tint = DroshTextMuted,
            modifier = Modifier.size(16.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = stringResource(R.string.projects_filter_hint),
                    fontSize = 13.5.sp,
                    color = DroshTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.5.sp, color = DroshText),
                cursorBrush = SolidColor(DroshPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onDone() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            IconAction(
                icon = DroshIcons.X,
                contentDescription = stringResource(R.string.projects_filter_clear),
                onClick = {
                    onValueChange("")
                    focusManager.clearFocus()
                },
                modifier = Modifier.size(44.dp),
                tint = DroshTextMuted,
            )
        }
    }
}

// -------------------------------------------------------------------- shared

@Composable
private fun sessionCountLabel(count: Int): String = when (count) {
    0 -> stringResource(R.string.projects_sessions_zero)
    1 -> stringResource(R.string.projects_sessions_one)
    else -> stringResource(R.string.projects_sessions_many, count)
}

/**
 * Every session on the board, grouped or not.
 *
 * Used to resolve the id the assignment sheet is holding into the session it
 * names. The board is two lists rather than one, so a lookup has to walk both —
 * and it has to fail closed, because a sheet left open for a session that was
 * then deleted has nothing to attach to and must simply not appear.
 */
private fun WorkspaceBoard.allSessions(): List<SessionSnapshot> =
    groups.flatMap { it.sessions } + ungrouped

/**
 * A `List<String>`, not a `Set<String>`, because this value is held in
 * `rememberSaveable`.
 *
 * A set has no `Bundle`-compatible `Saver`, so `rememberSaveable` would throw at
 * the first state save — on rotation, of all times — rather than at compile time
 * where it would be a one-line fix. Membership tests here are on a list of a
 * handful of project ids, where the difference is nothing.
 */
private fun List<String>.toggling(id: String): List<String> =
    if (id in this) this - id else this + id
