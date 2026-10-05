package dev.drosh.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.remember
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
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val assigningId by viewModel.assigningSessionId.collectAsStateWithLifecycle()

    // Which groups are open. Held here rather than per-card so that a card can
    // start collapsed: a project with six sessions in it should not push the
    // next project off the screen just because it was made last.
    var expandedIds by remember { mutableStateOf(emptySet<String>()) }

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
            ProjectsTopBar(
                onBack = onBack,
                onNew = viewModel::startCreate,
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (board.groups.isEmpty() && board.ungrouped.isEmpty()) {
                    item(key = "empty") { EmptyProjects() }
                }

                board.groups.forEach { group ->
                    item(key = "ws_${group.workspace.id}") {
                        WorkspaceCard(
                            group = group,
                            expanded = group.workspace.id in expandedIds,
                            activeSessionId = activeId,
                            onToggle = { expandedIds = expandedIds.toggling(group.workspace.id) },
                            onEdit = { viewModel.startEdit(group.workspace) },
                            onOpenSession = { session ->
                                viewModel.openSession(session, group.workspace.id)
                                onOpenSession()
                            },
                            onAssign = viewModel::startAssigning,
                        )
                    }
                }

                if (board.ungrouped.isNotEmpty()) {
                    item(key = "hdr_ungrouped") { SectionHeader("GRUBU OLMAYANLAR") }
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
            onDismiss = viewModel::dismissEditor,
        )
    }

    assigningSession?.let { session ->
        WorkspaceAssignSheet(
            sessionName = session.name,
            currentWorkspaceId = session.workspaceId,
            workspaces = board.groups.map { it.workspace },
            onSelect = { workspaceId -> viewModel.assign(session.id, workspaceId) },
            onDismiss = viewModel::dismissAssigning,
        )
    }
}

@Composable
private fun ProjectsTopBar(onBack: () -> Unit, onNew: () -> Unit) {
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

        IconAction(
            icon = DroshIcons.Plus,
            contentDescription = "Yeni proje",
            onClick = onNew,
        )
    }
}

/**
 * One project: its accent, name, directory and count, plus its sessions when
 * the card is open.
 *
 * The header row is the toggle and the `⋯` inside it opens the editor — the
 * inner control consumes its own tap, so the two do not fight.
 */
@Composable
private fun WorkspaceCard(
    group: WorkspaceGroup,
    expanded: Boolean,
    activeSessionId: String?,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onOpenSession: (SessionSnapshot) -> Unit,
    onAssign: (String) -> Unit,
) {
    val workspace = group.workspace
    val accent = workspaceSeedColor(workspace.colorSeed)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DroshSurfaceLow)
            // A project is identified by its accent, so the border carries it. The
            // dot alone is 8dp and disappears on a dim screen.
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(Modifier.width(10.dp))

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
                    .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(DroshOutline.copy(alpha = 0.4f)),
            )
            if (group.sessions.isEmpty()) {
                Text(
                    text = "Bu projeye bağlı session yok.",
                    fontSize = 12.sp,
                    color = DroshTextMuted,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                )
            } else {
                Column(
                    modifier = Modifier.padding(bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    group.sessions.forEach { session ->
                        SessionRow(
                            session = session,
                            active = session.id == activeSessionId,
                            onOpen = { onOpenSession(session) },
                            onAssign = { onAssign(session.id) },
                        )
                    }
                }
            }
        }
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
                    .size(10.dp)
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
 * Long press files it somewhere else. That is the only gesture here that is not
 * obvious from the row itself, so it is also spoken in the content description.
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
            .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
            // One description for the whole row. A screen reader reading the name,
            // the state and the hint as three fragments does not say "dev-local,
            // bitti, uzun bas: projeye taşı" — it says them as separate things.
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(session.name)
                    if (ended) append(", bitti")
                    if (active) append(", aktif")
                    append(", uzun bas: projeye taşı")
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
                .clearAndSetSemantics { },
        )
        if (active) {
            Text(
                text = "aktif",
                fontSize = 9.sp,
                color = DroshTextMuted,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
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
 * Every session on the board, grouped or not.
 *
 * Used to resolve the id the assignment sheet is holding into the session it
 * names. The board is two lists rather than one, so a lookup has to walk both —
 * and it has to fail closed, because a sheet left open for a session that was
 * then deleted has nothing to attach to and must simply not appear.
 */
private fun WorkspaceBoard.allSessions(): List<SessionSnapshot> =
    groups.flatMap { it.sessions } + ungrouped

private fun Set<String>.toggling(id: String): Set<String> =
    if (id in this) this - id else this + id