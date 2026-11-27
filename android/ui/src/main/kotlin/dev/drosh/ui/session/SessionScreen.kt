package dev.drosh.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.domain.session.SessionState
import dev.drosh.theme.DroshBorderSubtle
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

/**
 * The two views of the grid.
 *
 * - [Sessions] is everything the user has: single cards **and** group cards
 *   together, in the order they arranged. Choosing this view never hides a
 *   group, and a group-only screen is still one tap from its members.
 * - [Groups] is the groups alone — the same cards, without the singles — for
 *   working on the arrangement rather than on one terminal.
 */
internal enum class SessionTab { Sessions, Groups }

/**
 * Every session, as a card with the last frame of its terminal, beside the UI
 * groups the user made out of them.
 *
 * ## One list, two views
 *
 * Groups used to live in their own view and the "Sessions" view held singles
 * only, so a session the user grouped disappeared from the first view entirely.
 * Now the first view is the complete one — groups and singles, in one grid —
 * and the second is a filter of it. An empty first view means the user really
 * has no sessions, which is the only case that deserves an empty state.
 *
 * ## A group is a place, not a dialog
 *
 * A tap on a group opens [SessionGroupPanel] over the grid: a full-screen
 * surface where the group can be named, its members reordered, one removed,
 * one closed, or a session opened. The old dialog listed members and nothing
 * else; a group the user cannot rearrange is a folder they cannot tidy.
 *
 * ## Split without a dialog
 *
 * `Split with…` on a card closes its menu and puts the grid into a selection
 * mode: a banner says what is being chosen, and picking a second card shows the
 * pair in a preview bar with the two real terminal frames. Confirming calls the
 * same `onGroupSessions` the terminal always used.
 *
 * ## Closing
 *
 * A close never fires on the tap that asked for it: it lands in
 * [pendingCloseId] / [pendingCloseAll] and one dialog asks, because the
 * transcript it erases is not recoverable.
 */
@Composable
fun SessionScreen(
    sessions: List<SessionSnapshot>,
    activeId: String?,
    backgroundIds: Set<String> = emptySet(),
    titles: Map<String, String> = emptyMap(),
    icons: Map<String, String> = emptyMap(),
    programs: Map<String, String> = emptyMap(),
    onOpen: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewSession: () -> Unit,
    onCloseAll: () -> Unit,
    onGroupSessions: (String, String) -> Unit,
    /** Closes the grid, back to the terminal. */
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val switcherViewModel: SessionSwitcherViewModel = hiltViewModel()
    val thumbnails by switcherViewModel.thumbnails.collectAsStateWithLifecycle()
    val persistentIds by switcherViewModel.persistentIds.collectAsStateWithLifecycle()
    val storedLayout by switcherViewModel.gridLayout.collectAsStateWithLifecycle()
    val fallbackName = stringResource(R.string.session_screen_default_name)

    var query by rememberSaveable { mutableStateOf("") }
    // Stored as a string so the saved state survives process death without a
    // custom Saver for an enum.
    var savedTab by rememberSaveable { mutableStateOf(SessionTab.Sessions.name) }
    val tab = SessionTab.entries.firstOrNull { it.name == savedTab } ?: SessionTab.Sessions

    // Null until the store has been read: an empty arrangement is never written
    // back during that window, because that is how a saved one is lost.
    val pinnedIds = storedLayout?.pinnedIds ?: emptySet()
    val order = storedLayout?.orderedIds ?: emptyList()
    val groups = remember(storedLayout) {
        storedLayout?.groups?.map { SessionGroup(it.memberIds, it.name) } ?: emptyList()
    }

    var localNames by remember { mutableStateOf(emptyMap<String, String>()) }
    var pendingCloseId by remember { mutableStateOf<String?>(null) }
    var pendingCloseAll by remember { mutableStateOf(false) }

    /** The source of a split whose partner is being chosen, or null. */
    var splitSourceId by remember { mutableStateOf<String?>(null) }
    /** The card picked as the split's second pane. */
    var splitPartnerId by remember { mutableStateOf<String?>(null) }
    var renameTargetId by remember { mutableStateOf<String?>(null) }
    /** The first member id of the group whose panel is open, or null. */
    var panelGroupSlot by remember { mutableStateOf<String?>(null) }

    var heldId by remember { mutableStateOf<String?>(null) }
    var dropTarget by remember { mutableStateOf<String?>(null) }

    /** Every card's rectangle, in root coordinates, for drop hit-testing. */
    val bounds = remember { mutableMapOf<String, Rect>() }

    val live = remember(sessions) {
        sessions
            .filter { it.state != SessionState.Closed }
            .sortedByDescending { it.lastUsedAtMs }
    }
    val liveIds = remember(live) { live.map { it.id } }

    val orderedIds = remember(order, live) { reconcileSessionOrder(order, liveIds) }
    val groupsLive = remember(groups, live) { liveSessionGroups(groups, liveIds.toSet()) }
    val groupBySlot = remember(groupsLive) { groupsLive.associateBy { it.slotId } }
    val groupedMemberIds = remember(groupsLive) { groupsLive.flatMap { it.memberIds }.toSet() }

    val glyphSlugs: Map<String, String> = remember(live, titles, localNames, programs, icons) {
        live.mapNotNull { session ->
            ProgramGlyphs.resolve(
                program = programs[session.id] ?: icons[session.id],
                title = titles[session.id] ?: localNames[session.id] ?: session.name,
            )?.let { slug -> session.id to slug }
        }.toMap()
    }

    val displayNames = remember(live, titles, localNames, fallbackName) {
        live.associate { it.id to sessionDisplayName(it.id, localNames, titles, fallbackName, it.name) }
    }

    val matchedIds = remember(live, query, titles, localNames) {
        if (query.isBlank()) {
            live.map { it.id }.toSet()
        } else {
            val needle = query.trim()
            live.filter { session ->
                val name = localNames[session.id] ?: session.name
                name.contains(needle, ignoreCase = true) ||
                    titles[session.id]?.contains(needle, ignoreCase = true) == true
            }.map { it.id }.toSet()
        }
    }

    // One slot per session, with a group taking the slot of its first member.
    // Pinned slots move to the front of their section.
    val slots = remember(orderedIds, live, groupBySlot, groupedMemberIds, matchedIds, pinnedIds) {
        buildList<SessionGridItem> {
            orderedIds.forEach { id ->
                val group = groupBySlot[id]
                when {
                    group != null -> {
                        val members = group.memberIds.mapNotNull { memberId ->
                            live.firstOrNull { it.id == memberId }
                        }
                        if (members.size >= MIN_GROUP_MEMBERS && members.any { it.id in matchedIds }) {
                            add(SessionGridItem.Group(group, members))
                        }
                    }
                    id in matchedIds && id !in groupedMemberIds -> {
                        live.firstOrNull { it.id == id }?.let { add(SessionGridItem.Single(it)) }
                    }
                }
            }
        }.let { items ->
            val pinned = items.filter { item -> item.memberIds.any { it in pinnedIds } }
            val others = items.filterNot { item -> item.memberIds.any { it in pinnedIds } }
            pinned + others
        }
    }

    // Sessions is the complete view; Groups is the same grid without the
    // singles. The old pair was inverted — Sessions dropped every grouped
    // session, so the screen could be empty while four terminals were running.
    val visibleItems = remember(slots, tab) {
        when (tab) {
            SessionTab.Sessions -> slots
            SessionTab.Groups -> slots.filterIsInstance<SessionGridItem.Group>()
        }
    }
    val visibleSlotIds = remember(visibleItems) { visibleItems.mapTo(mutableSetOf()) { it.slotId } }

    fun applyDrop(held: String, target: String) {
        if (held == target) return
        val targetGroup = groupsLive.firstOrNull { target in it.memberIds }
        val heldGroup = groupsLive.firstOrNull { held in it.memberIds }
        when {
            targetGroup != null -> {
                val incoming = heldGroup?.memberIds?.filterNot { it == target }
                    ?: listOf(held)
                switcherViewModel.setGroups(addToGroup(groups, targetGroup, incoming))
            }
            heldGroup != null -> {
                switcherViewModel.reorder(heldGroup.slotId, target)
            }
            else -> {
                switcherViewModel.setGroups(groupTwoSessions(groups, target, held))
            }
        }
    }

    fun leaveSplitMode() {
        splitSourceId = null
        splitPartnerId = null
    }

    fun removeMemberFromGroups(id: String) {
        groupsLive.firstOrNull { id in it.memberIds }?.let { group ->
            switcherViewModel.setGroups(removeFromGroup(groups, group, id))
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SessionScreenHeader(
                count = live.size,
                groupCount = groupsLive.size,
                tab = tab,
                onSelectTab = { savedTab = it.name },
                onCloseAll = { if (live.isNotEmpty()) pendingCloseAll = true },
                onDismiss = {
                    leaveSplitMode()
                    onDismiss()
                },
            )

            splitSourceId?.let { sourceId ->
                SplitModeBanner(
                    sourceName = displayNames[sourceId] ?: fallbackName,
                    onCancel = ::leaveSplitMode,
                )
            }

            if (visibleItems.isEmpty()) {
                SessionScreenEmpty(
                    tab = tab,
                    hasSessions = live.isNotEmpty(),
                    hasQuery = query.isNotBlank(),
                    onClearQuery = { query = "" },
                    onNewSession = onNewSession,
                    onShowSessions = { savedTab = SessionTab.Sessions.name },
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        start = 14.dp,
                        end = 14.dp,
                        top = 4.dp,
                        bottom = BOTTOM_BAR_CLEARANCE,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(visibleItems, key = { it.key }) { item ->
                        when (item) {
                            is SessionGridItem.Single -> {
                                val session = item.snapshot
                                SessionCard(
                                    session = session,
                                    displayName = displayNames[session.id] ?: session.name,
                                    subtitle = sessionSubtitle(
                                        session.id,
                                        localNames,
                                        session.name,
                                        displayNames[session.id] ?: session.name,
                                    ),
                                    iconName = icons[session.id],
                                    glyphSlug = glyphSlugs[session.id],
                                    thumbnail = thumbnails[session.id],
                                    isActive = session.id == activeId,
                                    isPinned = session.id in pinnedIds,
                                    isPersistent = session.id in persistentIds,
                                    isDropTarget = session.id == dropTarget,
                                    isHeld = session.id == heldId,
                                    isSelected = splitPartnerId == session.id,
                                    onBounds = { bounds[item.slotId] = it },
                                    onTap = {
                                        when {
                                            splitSourceId == null -> onOpen(session.id)
                                            session.id == splitSourceId -> Unit
                                            else -> splitPartnerId = session.id
                                        }
                                    },
                                    onDragStart = { _ ->
                                        heldId = session.id
                                        dropTarget = null
                                    },
                                    onDrag = { position ->
                                        dropTarget = bounds.entries
                                            .firstOrNull { (id, rect) ->
                                                id != session.id &&
                                                    id in visibleSlotIds &&
                                                    rect.contains(position)
                                            }
                                            ?.key
                                    },
                                    onDragEnd = {
                                        val target = dropTarget
                                        val held = heldId
                                        heldId = null
                                        dropTarget = null
                                        if (target != null && held != null) {
                                            applyDrop(held, target)
                                        }
                                    },
                                    onSplitWith = {
                                        leaveSplitMode()
                                        splitSourceId = session.id
                                    },
                                    onTogglePin = { switcherViewModel.togglePin(session.id) },
                                    onTogglePersistent = {
                                        switcherViewModel.setPersistent(
                                            session.id,
                                            session.id !in persistentIds,
                                        )
                                    },
                                    onRename = { renameTargetId = session.id },
                                    onClose = { pendingCloseId = session.id },
                                    onCloseAll = { if (live.isNotEmpty()) pendingCloseAll = true },
                                )
                            }
                            is SessionGridItem.Group -> {
                                SessionGroupCard(
                                    group = item.group,
                                    members = item.members,
                                    thumbnails = thumbnails,
                                    displayNames = displayNames,
                                    glyphSlugs = glyphSlugs,
                                    isActive = item.members.any { it.id == activeId },
                                    isDropTarget = item.group.slotId == dropTarget,
                                    isHeld = item.group.slotId == heldId,
                                    onBounds = { bounds[item.slotId] = it },
                                    onTap = {
                                        if (splitSourceId == null) {
                                            panelGroupSlot = item.group.slotId
                                        } else {
                                            item.members
                                                .firstOrNull { it.id != splitSourceId }
                                                ?.let { partner -> splitPartnerId = partner.id }
                                        }
                                    },
                                    onDragStart = { _ ->
                                        heldId = item.slotId
                                        dropTarget = null
                                    },
                                    onDrag = { position ->
                                        dropTarget = bounds.entries
                                            .firstOrNull { (id, rect) ->
                                                id != item.slotId &&
                                                    id in visibleSlotIds &&
                                                    rect.contains(position)
                                            }
                                            ?.key
                                    },
                                    onDragEnd = {
                                        val target = dropTarget
                                        val held = heldId
                                        heldId = null
                                        dropTarget = null
                                        if (target != null && held != null) {
                                            applyDrop(held, target)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // The split's preview: the two real frames, with the one action
            // that commits the pair. It replaces the floating bar while a
            // partner is chosen so the screen says one thing at a time.
            AnimatedVisibility(
                visible = splitSourceId != null && splitPartnerId != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                val source = splitSourceId
                val partner = splitPartnerId
                if (source != null && partner != null) {
                    SplitPreviewBar(
                        sourceName = displayNames[source] ?: fallbackName,
                        sourcePreview = thumbnails[source],
                        partnerName = displayNames[partner] ?: fallbackName,
                        partnerPreview = thumbnails[partner],
                        onCancel = ::leaveSplitMode,
                        onConfirm = {
                            onGroupSessions(source, partner)
                            leaveSplitMode()
                            onDismiss()
                        },
                    )
                }
            }

            if (splitPartnerId == null) {
                SessionBottomBar(
                    query = query,
                    onQueryChange = { query = it },
                    onNewSession = onNewSession,
                )
            }
        }

        // The group's management panel: a surface over the grid, not a dialog.
        val panelGroup = panelGroupSlot?.let { slot -> groupsLive.firstOrNull { it.slotId == slot } }
        AnimatedVisibility(
            visible = panelGroup != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            if (panelGroup != null) {
                SessionGroupPanel(
                    group = panelGroup,
                    members = panelGroup.memberIds.mapNotNull { id -> live.firstOrNull { it.id == id } },
                    displayNames = displayNames,
                    thumbnails = thumbnails,
                    glyphSlugs = glyphSlugs,
                    isActiveId = { it == activeId },
                    onDismiss = { panelGroupSlot = null },
                    onOpen = { id ->
                        panelGroupSlot = null
                        onOpen(id)
                    },
                    onRename = { name -> switcherViewModel.renameGroup(panelGroup.memberIds, name) },
                    onMove = { id, offset ->
                        val moved = moveGroupMember(panelGroup, id, offset)
                        switcherViewModel.setGroups(
                            groups.map { if (it.sameMembersAs(panelGroup)) moved else it },
                        )
                    },
                    onRemoveMember = { id ->
                        val updated = removeFromGroup(groups, panelGroup, id)
                        switcherViewModel.setGroups(updated)
                        // The panel follows the group while it survives; a group
                        // that fell below two members is gone, so the panel is too.
                        panelGroupSlot = updated
                            .firstOrNull { it.sameMembersAs(panelGroup) }
                            ?.slotId
                    },
                    onDeleteMember = { id -> pendingCloseId = id },
                    onUngroupAll = {
                        switcherViewModel.setGroups(ungroup(groups, panelGroup))
                        panelGroupSlot = null
                    },
                )
            }
        }
    }

    renameTargetId?.let { id ->
        if (live.any { it.id == id }) {
            RenameSessionDialog(
                initialValue = localNames[id]
                    ?: live.firstOrNull { it.id == id }?.name
                    ?: fallbackName,
                onConfirm = { newName ->
                    switcherViewModel.rename(id, newName)
                    localNames = localNames + (id to newName)
                    renameTargetId = null
                },
                onDismiss = { renameTargetId = null },
            )
        }
    }

    if (pendingCloseId != null || pendingCloseAll) {
        val targetName = pendingCloseId
            ?.let { id -> displayNames[id] ?: live.firstOrNull { it.id == id }?.name }
            ?: fallbackName
        CloseSessionDialog(
            title = stringResource(
                if (pendingCloseAll) {
                    R.string.session_screen_close_all_title
                } else {
                    R.string.session_screen_close_one_title
                },
            ),
            message = if (pendingCloseAll) {
                stringResource(R.string.session_screen_close_all_message, live.size)
            } else {
                stringResource(R.string.session_screen_close_one_message, targetName)
            },
            onConfirm = {
                val id = pendingCloseId
                if (id != null) {
                    onClose(id)
                    removeMemberFromGroups(id)
                    localNames = localNames - id
                } else {
                    onCloseAll()
                }
                pendingCloseId = null
                pendingCloseAll = false
            },
            onDismiss = {
                pendingCloseId = null
                pendingCloseAll = false
            },
        )
    }
}

/** One slot in the grid: either a session or a local UI group of sessions. */
internal sealed interface SessionGridItem {
    val key: String
    val slotId: String
    val memberIds: List<String>

    data class Single(val snapshot: SessionSnapshot) : SessionGridItem {
        override val key: String get() = "session:${snapshot.id}"
        override val slotId: String get() = snapshot.id
        override val memberIds: List<String> get() = listOf(snapshot.id)
    }

    data class Group(
        val group: SessionGroup,
        val members: List<SessionSnapshot>,
    ) : SessionGridItem {
        override val key: String get() = "group:${group.slotId}"
        override val slotId: String get() = group.slotId
        override val memberIds: List<String> get() = group.memberIds
    }
}
