package dev.drosh.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshSurfaceLow
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.domain.session.SessionState
import dev.drosh.domain.workspace.WorkspaceBoard
import dev.drosh.domain.workspace.Workspace
import dev.drosh.domain.workspace.WorkspaceGroup
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.agent.components.ActionButton
import dev.drosh.ui.agent.components.IconAction
import dev.drosh.ui.agent.components.SectionHeader
import dev.drosh.ui.agent.components.TOUCH_TARGET
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
 */
@Composable
fun WorkspaceScreen(
    onBack: () -> Unit,
    /** Open a session and leave this screen for the terminal. */
    onOpenSession: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WorkspaceViewModel = hiltViewModel(),
) {
val board by viewModel.board.collectAsStateWithLifecycle()
    val activeId by viewModel.activeSessionId.collectAsStateWithLifecycle()
    val archived by viewModel.archived.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val assigningId by viewModel.assigningSessionId.collectAsStateWithLifecycle()

    // Which groups are open, surviving rotation and the trip to the terminal and
    // back — `remember` alone reset both, and a list that folds itself up every
    // time you come back from opening a session is a list you stop trusting.
    var expandedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }

    // Resolved from the board rather than kept as its own state, so a sheet left
    // open for a session that has since been deleted finds nothing and simply
    // does not appear. Both lists have to be walked — the board is workspaces
    // and the loose remainder, not one list.
    val assigningSession = board.allSessions().firstOrNull { it.id == assigningId }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // No "+" here on purpose. The bottom bar is the same action one thumb
            // reach away; a second copy at the top of the screen is the end of a
            // phone, and two controls for one thing reads as neither being sure
            // which one to press.
            ProjectsTopBar(onBack = onBack)

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Two empty states, not one. "Nothing here" and "you have
                // sessions but none of them are filed" are different situations
                // and only the second one needs telling what to do about it — the
                // first is a first run, the second is the state the app is in by
                // default, because a launch creates a Default session.
                if (board.groups.isEmpty()) {
                    item(key = "empty") {
                        if (board.ungrouped.isEmpty()) {
                            EmptyProjects()
                        } else {
                            UnfiledNudge(sessionCount = board.ungrouped.size)
                        }
                    }
                }

                board.groups.forEach { group ->
                    item(key = "ws_${group.workspace.id}") {
                        WorkspaceCard(
                            group = group,
                            expanded = group.workspace.id in expandedIds,
                            activeSessionId = activeId,
                            onToggle = { expandedIds = expandedIds.toggling(group.workspace.id) },
                            onEdit = { viewModel.startEdit(group.workspace) },
                            onNewSession = {
                                viewModel.createSessionIn(group.workspace)
                                onOpenSession()
                            },
                            onOpenSession = { session ->
                                viewModel.openSession(session, group.workspace.id)
                                onOpenSession()
                            },
                            onAssign = viewModel::startAssigning,
                        )
                    }
                }

                if (board.ungrouped.isNotEmpty()) {
                    item(key = "hdr_ungrouped") { SectionHeader("PROJEYE BAĞLI DEĞİL") }
                    item(key = "ungrouped") {
                        UngroupedCard(
                            sessions = board.ungrouped,
                            activeSessionId = activeId,
                            onOpenSession = { session ->
                                viewModel.openSession(session, null)
                                onOpenSession()
                            },
                            onAssign = viewModel::startAssigning,
                        )
                    }
                }

                // Archived last and out of the way. It is here because deleting a
                // project is not something to make people do to stop seeing it —
                // archiving keeps the grouping recoverable and the row intact.
                if (archived.isNotEmpty()) {
                    item(key = "hdr_archived") { SectionHeader("ARŞİV") }
                    items(archived.size, key = { "arch_${archived[it].id}" }) { index ->
                        val workspace = archived[index]
                        ArchivedRow(
                            workspace = workspace,
                            // Counted off the board rather than with a query. The
                            // grouping survives archiving — only the display goes —
                            // so the sessions are here with their workspace id
                            // intact, and counting them is a filter over a list
                            // already in hand.
                            looseSessionCount = board.looseCountFor(workspace.id),
                            onRestore = { viewModel.restore(workspace.id) },
                            onEdit = { viewModel.startEdit(workspace) },
                        )
                    }
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
            ActionButton(
                text = "Yeni proje",
                onClick = viewModel::startCreate,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    editing?.let { state ->
        WorkspaceEditSheet(
            state = state,
            onDraftChange = viewModel::updateDraft,
            onSave = viewModel::saveEditor,
            onDelete = viewModel::deleteEditorTarget,
            onToggleArchive = viewModel::toggleArchiveEditorTarget,
            onDismiss = viewModel::dismissEditor,
        )
    }

    assigningSession?.let { session ->
        WorkspaceAssignSheet(
            sessionName = session.name,
            currentWorkspaceId = session.workspaceId,
            workspaces = board.groups.map { it.workspace },
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

@Composable
private fun ProjectsTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = TOUCH_TARGET)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconAction(
            icon = DroshIcons.ArrowLeft,
            contentDescription = "Geri",
            onClick = onBack,
        )

        Spacer(Modifier.width(8.dp))

        Text(
            text = "Projeler",
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = DroshText,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * One project: its accent, name, directory and count, plus its sessions when
 * the card is open.
 *
 * The header row is the toggle and the `⋯` inside it opens the editor — the
 * inner control consumes its own tap, so the two do not fight. So does the `+`
 * once the card is open.
 *
 * The accent is a bar down the left edge rather than a border around the card.
 * A border has to draw all four sides and reads as a selection state; a bar
 * reads as "this thing has a colour", which is what it is. The 8dp dot it
 * replaces was not enough — on a dim screen at arm's length it disappeared,
 * which is the one job the colour was doing.
 */
@Composable
private fun WorkspaceCard(
    group: WorkspaceGroup,
    expanded: Boolean,
    activeSessionId: String?,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onNewSession: () -> Unit,
    onOpenSession: (SessionSnapshot) -> Unit,
    onAssign: (String) -> Unit,
) {
    val workspace = group.workspace
    val accent = workspaceSeedColor(workspace.colorSeed)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DroshSurfaceLow),
    ) {
        Box(
            modifier = Modifier
                .width(ACCENT_BAR_WIDTH)
                .fillMaxHeight()
                .background(accent),
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(start = 10.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = workspace.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = DroshText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = workspace.rootPath,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = DroshTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (workspace.description.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = workspace.description,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = DroshTextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                Text(
                    text = sessionCountLabel(group.sessions.size),
                    fontSize = 10.sp,
                    color = DroshTextMuted,
                )

                IconAction(
                    icon = DroshIcons.EllipsisVertical,
                    contentDescription = "${workspace.name} ayarları",
                    onClick = onEdit,
                    modifier = Modifier.size(40.dp),
                )

                Icon(
                    imageVector = if (expanded) DroshIcons.ChevronDown else DroshIcons.ChevronRight,
                    contentDescription = if (expanded) "Daralt" else "Genişlet",
                    tint = DroshTextMuted,
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(18.dp),
                )
            }

            if (expanded) {
                Box(
                    modifier = Modifier
                        .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(DroshOutline.copy(alpha = 0.35f)),
                )

                if (group.sessions.isEmpty()) {
                    Text(
                        text = "Bu projeye bağlı session yok.",
                        fontSize = 12.sp,
                        color = DroshTextMuted,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp),
                    )
                } else {
                    group.sessions.forEach { session ->
                        SessionRow(
                            session = session,
                            active = session.id == activeSessionId,
                            onOpen = { onOpenSession(session) },
                            onAssign = { onAssign(session.id) },
                        )
                    }
                }

                // A project you just made looks identical to one you abandoned:
                // a name, a path, "no sessions". This is the way out of that, and
                // it only exists while there is something to be done about it.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNewSession)
                        .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 12.dp),
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
                        text = "Bu projede session aç",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = accent,
                    )
                }
            }
        }
    }
}

/** Wide enough to read as a colour, thin enough not to compete with the name. */
private val ACCENT_BAR_WIDTH = 4.dp

/**
 * An archived project: one row, and the two things worth doing to one.
 *
 * Restore first. An archived project is one the user stepped away from, not one
 * they threw out, and making them retype the name to get it back would turn
 * archiving into a trap.
 *
 * [looseSessionCount] is the consequence of archiving, stated on the row that
 * caused it. Archiving hides the project and the grouping stops being displayed,
 * so those sessions turn up in the ungrouped section — which, read on its own,
 * looks like the archive action destroyed the grouping. It did not; saying the
 * number here is cheaper than the user having to work that out. Phrased without a
 * position on screen ("above"), which would go stale the moment the list scrolls.
 */
@Composable
private fun ArchivedRow(
    workspace: Workspace,
    looseSessionCount: Int,
    onRestore: () -> Unit,
    onEdit: () -> Unit,
) {
    val accent = workspaceSeedColor(workspace.colorSeed)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DroshSurfaceLow)
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.5f)),
        )
        Spacer(Modifier.width(10.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit),
        ) {
            Text(
                text = workspace.name,
                fontSize = 13.sp,
                color = DroshTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (looseSessionCount == 0) workspace.rootPath
                else "${sessionCountLabel(looseSessionCount)} projeler arasında",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = DroshTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        IconAction(
            icon = DroshIcons.RotateCcw,
            contentDescription = "${workspace.name} projeleri geri al",
            onClick = onRestore,
            modifier = Modifier.size(40.dp),
        )
    }
}

@Composable
private fun UngroupedCard(
    sessions: List<SessionSnapshot>,
    activeSessionId: String?,
    onOpenSession: (SessionSnapshot) -> Unit,
    onAssign: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DroshSurfaceLow),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(DroshOutline),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = sessionCountLabel(sessions.size),
                fontSize = 12.sp,
                color = DroshTextSecondary,
            )
        }
        Spacer(Modifier.height(2.dp))
        sessions.forEach { session ->
            SessionRow(
                session = session,
                active = session.id == activeSessionId,
                onOpen = { onOpenSession(session) },
                onAssign = { onAssign(session.id) },
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * One session inside a project.
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionSnapshot,
    active: Boolean,
    onOpen: () -> Unit,
    onAssign: () -> Unit,
) {
    val ended = session.state == SessionState.Closed

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onAssign)
            .background(if (active) DroshSurfaceHigh else Color.Transparent)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            // One description for the whole row. A screen reader reading the name,
            // the state and the hint as three fragments does not say "dev-local,
            // bitti, uzun bas: projeye taşı" — it says them as separate things.
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(session.name)
                    if (ended) append(", bitti")
                    if (active) append(", aktif")
                    append(", projeye taşı")
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = session.name,
            fontSize = 13.sp,
            color = if (ended) DroshTextMuted else DroshTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp)
                .clearAndSetSemantics { },
        )
        if (active) {
            Text(
                text = "aktif",
                fontSize = 9.sp,
                color = DroshTextMuted,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .clearAndSetSemantics { },
            )
        }
        IconAction(
            icon = DroshIcons.FolderOpen,
            contentDescription = "${session.name}: projeye taşı",
            onClick = onAssign,
            modifier = Modifier.size(38.dp),
            tint = DroshTextMuted,
        )
    }
}

private fun sessionCountLabel(count: Int): String = when (count) {
    0 -> "session yok"
    1 -> "1 session"
    else -> "$count session"
}

@Composable
private fun EmptyProjects() {
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
            text = "Henüz proje yok",
            fontSize = 15.sp,
            color = DroshText,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Bir proje, bir klasör ve ona bağlı session'lar.\n" +
                "Session'lar kalıcı değildir — proje yalnızca gruplar.",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = DroshTextMuted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Sessions exist, no project does — which is the state the app is in by default,
 * because a launch creates a `Default` session whether or not you ever make a
 * project.
 *
 * This is a different message from [EmptyProjects] for that reason. Showing the
 * first-run text here would read as "you have nothing", which is false: there is
 * a session right there, below, and the thing missing is a filing cabinet, not
 * content.
 */
@Composable
private fun UnfiledNudge(sessionCount: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = DroshIcons.Folder,
            contentDescription = null,
            tint = DroshOutline,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = if (sessionCount == 1) "1 session projeye bağlı değil" else
                "$sessionCount session projeye bağlı değil",
            fontSize = 15.sp,
            color = DroshText,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Aşağıdaki session'ları bir projeye topla,\n" +
                "ya da önce bir proje oluştur.",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = DroshTextMuted,
            textAlign = TextAlign.Center,
        )
    }
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
 * How many sessions point at [workspaceId] but are not displayed under it.
 *
 * Always the count for an archived project, whose sessions land in [ungrouped]
 * while keeping the id. Walking [allSessions] is enough: the grouping survives
 * archiving, only the display of it goes, so nothing here needs a query the
 * screen does not already have the answer to.
 */
private fun WorkspaceBoard.looseCountFor(workspaceId: String): Int =
    allSessions().count { it.workspaceId == workspaceId } -
        groups.firstOrNull { it.workspace.id == workspaceId }?.sessions?.size.orZero()

private fun Int?.orZero(): Int = this ?: 0

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