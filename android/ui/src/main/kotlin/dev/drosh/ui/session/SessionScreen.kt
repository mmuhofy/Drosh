package dev.drosh.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.domain.session.SessionState
import dev.drosh.ui.R

/**
 * The two card filters. They used to be a row of tabs above the grid; now they
 * are the only two entries in the dropdown off the header title.
 */
internal enum class SessionTab { Groups, Singles }

/**
 * Every session, as a card with the last preview of its terminal.
 *
 * ## Hierarchy
 *
 * Three kinds of card and they must not look alike:
 *
 *  - **The active one** wears the accent border, so "you are here" survives a
 *    glance back after scrolling. Status pills were removed: the border is the
 *    only marker, and the card's left-hand icon identifies the session.
 *  - **A card the grouping gesture is over** also wears the accent, but over a
 *    tinted surface — the tint is what separates it from plain "you are here".
 *  - **Everything else** is a quiet card on a subtle border.
 *
 * ## Where the arrangement lives
 *
 * The pin set, the drag order and the groups are persisted by
 * [SessionGridLayoutStore], through [SessionSwitcherViewModel]. They used to be
 * `remember`ed here, which meant closing the grid — switching to a session and
 * coming back — silently ungrouped everything the user had grouped. The screen
 * still owns the *rules* (what a drop does); the ViewModel owns the state.
 *
 * ## Reordering, grouping and splitting are different
 *
 * A long press followed by a drag reorders or groups. Dropping on a *group*
 * card adds the held card (or the whole held group) to that group; dropping a
 * group on a single card reorders it; dropping one single card on another
 * creates a group, target first so the group card takes the target's slot. A
 * group is a UI group, like a Chrome tab group — it never touches the
 * terminal's panes. A tap on a group card opens a centred overlay that can
 * also remove a member or dissolve the group.
 *
 * A split still goes through [onGroupSessions], but only from the card's
 * overflow menu: `Split with…` opens the partner picker directly.
 *
 * ## Closing
 *
 * A close — one card, from its menu, or all at once from the header — never
 * fires on the tap that asked for it. It lands in [pendingCloseId] /
 * [pendingCloseAll] and a dialog asks once, because the transcript it erases is
 * not recoverable.
 */
@Composable
fun SessionScreen(
    sessions: List<SessionSnapshot>,
    activeId: String?,
    /**
     * The sessions that are running but not on screen.
     *
     * Kept in the public signature so the call site is a pure rename. Cards no
     * longer carry status chips or recency labels, so this is not rendered.
     */
    backgroundIds: Set<String> = emptySet(),
    /**
     * The titles the programs asked for, by session id (`OSC 0` / `OSC 2`).
     * Also used by the search pill, so a search for the program's title works.
     */
    titles: Map<String, String> = emptyMap(),
    /**
     * The `OSC 1` icon hints the programs set, by session id.
     *
     * A hint, not the answer: most programs never set one. A card's glyph is
     * chosen from the foreground program first, then this, then the title.
     */
    icons: Map<String, String> = emptyMap(),
    /**
     * The program each session has in the foreground, by session id.
     *
     * The kernel's answer from `/proc`, so it is right even for a program that
     * sets no title — which is most of them. This is what gives a Python REPL
     * Python's mark and a `claude` prompt Claude's, instead of a row of
     * identical terminal outlines.
     */
    programs: Map<String, String> = emptyMap(),
    onOpen: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewSession: () -> Unit,
    onCloseAll: () -> Unit,
    /**
     * Joins two sessions into a split. Called only by `Split with…`.
     * The first id is the session whose menu was opened, the second the choice.
     */
    onGroupSessions: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Rename reaches the existing domain API (SessionSwitcherViewModel.rename ->
    // SessionRepository.rename) through this ViewModel; the call site does not
    // need a new callback parameter. The same ViewModel owns the card previews,
    // the persistent toggle and the grid's arrangement.
    val switcherViewModel: SessionSwitcherViewModel = hiltViewModel()
    val thumbnails by switcherViewModel.thumbnails.collectAsStateWithLifecycle()
    val persistentIds by switcherViewModel.persistentIds.collectAsStateWithLifecycle()
    val storedLayout by switcherViewModel.gridLayout.collectAsStateWithLifecycle()
    val fallbackName = stringResource(R.string.session_screen_default_name)

    var query by rememberSaveable { mutableStateOf("") }
    // Stored as a string so the saved state survives a process death without
    // relying on a custom Saver for an enum.
    var savedTab by rememberSaveable { mutableStateOf(SessionTab.Singles.name) }
    val tab = SessionTab.entries.firstOrNull { it.name == savedTab } ?: SessionTab.Singles

    // Null until the store has been read. An empty arrangement is never written
    // back during that window: doing so would erase the saved one.
    val pinnedIds = storedLayout?.pinnedIds ?: emptySet()
    val order = storedLayout?.orderedIds ?: emptyList()
    val groups = remember(storedLayout) {
        storedLayout?.groups?.map { SessionGroup(it) } ?: emptyList()
    }

    // Staged rename shown immediately; the same value is written through to the
    // repository in the rename dialog's confirm handler below.
    var localNames by remember { mutableStateOf(emptyMap<String, String>()) }

    /**
     * A close waiting for its confirmation. The two never set together — the
     * dialog is modal — but stay separate so confirming can read which one it
     * serves.
     */
    var pendingCloseId by remember { mutableStateOf<String?>(null) }
    var pendingCloseAll by remember { mutableStateOf(false) }

    /** The source of a split whose partner is being chosen, or null. */
    var splitSourceId by remember { mutableStateOf<String?>(null) }
    /** The session whose rename dialog is open, or null. */
    var renameTargetId by remember { mutableStateOf<String?>(null) }
    /** The group whose overlay is open, or null. */
    var overlayGroup by remember { mutableStateOf<SessionGroup?>(null) }

    /** The card under a held finger, or null. */
    var heldId by remember { mutableStateOf<String?>(null) }
    /** The card the finger is over, when it is over one and not the held one. */
    var dropTarget by remember { mutableStateOf<String?>(null) }

    /**
     * Every card's rectangle, in root coordinates.
     *
     * Root coordinates because the only common frame between a card and the
     * card under the finger is the window. A plain map rather than Compose
     * state: it is written during layout and read during a gesture, and routing
     * it through state would invalidate layout on every frame of a drag.
     */
    val bounds = remember { mutableMapOf<String, Rect>() }

    val live = remember(sessions) {
        sessions
            .filter { it.state != SessionState.Closed }
            .sortedByDescending { it.lastUsedAtMs }
    }
    val liveIds = remember(live) { live.map { it.id } }

    // Reconcile the remembered drag order against closed/new sessions, and drop
    // closed members from groups. A group under two live members dissolves and
    // its sessions return to the singles view.
    val orderedIds = remember(order, live) { reconcileSessionOrder(order, liveIds) }
    val groupsLive = remember(groups, live) { liveSessionGroups(groups, liveIds.toSet()) }
    val groupBySlot = remember(groupsLive) { groupsLive.associateBy { it.memberIds.first() } }
    // Every member of every group, not just the first. [groupBySlot] only
    // maps a group's first member — that is the group's slot — so without
    // this set a non-first member falls through to the single branch below
    // and renders twice: once inside the group card and once as its own card.
    val groupedMemberIds = remember(groupsLive) { groupsLive.flatMap { it.memberIds }.toSet() }

    // The brand slug per session: the foreground program first, the `OSC 1`
    // hint second, the title's words last. Resolved once per change rather
    // than per card, because the resolution walks a table.
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

    // The search matches the user's name, the program's title and a rename made
    // in this screen, so all three ways of naming a session work in one pill.
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
    // Pinned slots are stably moved to the front of their section.
    val slots = remember(orderedIds, live, groupBySlot, groupedMemberIds, matchedIds, pinnedIds) {
        buildList<SessionGridItem> {
            orderedIds.forEach { id ->
                val group = groupBySlot[id]
                when {
                    group != null -> {
                        val members = live.filter { it.id in group.memberIds }
                        // A group whose first member was filtered out still gets a
                        // slot when any other member matches the search.
                        if (members.size >= MIN_GROUP_MEMBERS && members.any { it.id in matchedIds }) {
                            add(SessionGridItem.Group(group, members))
                        }
                    }
                    // A member already shown inside its group card has no slot
                    // of its own; only ungrouped sessions are singles.
                    id in matchedIds && id !in groupedMemberIds -> {
                        live.firstOrNull { it.id == id }?.let { add(SessionGridItem.Single(it)) }
                    }
                }
            }
        }.let { items ->
            val pinned = items.filter { it.memberIds.any { id -> id in pinnedIds } }
            val others = items.filterNot { it.memberIds.any { id -> id in pinnedIds } }
            pinned + others
        }
    }

    // "Sessions" shows ungrouped cards only. "Groups" shows the groups AND the
    // remaining single cards, so a single can always be dragged onto a group.
    val visibleItems = remember(slots, tab) {
        when (tab) {
            SessionTab.Groups -> slots
            SessionTab.Singles -> slots.filterIsInstance<SessionGridItem.Single>()
        }
    }
    val visibleSlotIds = remember(visibleItems) { visibleItems.map { it.slotId }.toSet() }

    /**
     * Applies one completed drop: extend the target group, reorder a group
     * onto a single card, or — two ungrouped cards — create a new group.
     *
     * The three cases are chosen from the groups that are live *at drop
     * time*, so they cannot overlap: a target that is a group takes the first
     * branch whatever is being held, and a held group on a single card
     * reorders. Neither ever splits a group.
     */
    fun applyDrop(held: String, target: String) {
        if (held == target) return
        val targetGroup = groupsLive.firstOrNull { target in it.memberIds }
        val heldGroup = groupsLive.firstOrNull { held in it.memberIds }
        when {
            targetGroup != null -> {
                // Dropped on a group: the held card — or the whole held
                // group, merged member by member — joins it.
                val incoming = heldGroup?.memberIds?.filterNot { it == target }
                    ?: listOf(held)
                switcherViewModel.setGroups(addToGroup(groups, targetGroup, incoming))
            }
            heldGroup != null -> {
                // A grouped member is represented by its group's slot, so
                // move that slot: a group dropped on a single reorders.
                switcherViewModel.reorder(heldGroup.memberIds.first(), target)
            }
            else -> {
                // Two singles become a group, target first so the group card
                // takes the target's slot.
                switcherViewModel.setGroups(groupTwoSessions(groups, target, held))
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        SessionScreenHeader(
            count = live.size,
            tab = tab,
            onSelectTab = { savedTab = it.name },
            onCloseAll = {
                if (live.isNotEmpty()) {
                    pendingCloseAll = true
                }
                // Nothing left to close, and a dialog saying so would be noise:
                // the empty state is already on screen.
            },
        )

        if (visibleItems.isEmpty()) {
            SessionScreenEmpty(
                tab = tab,
                hasQuery = query.isNotBlank(),
                // A session can be live and still have no card in this view:
                // every one of them may be inside a group while "Sessions" is
                // showing. Saying "No sessions" there was a lie that left the
                // groups one unreachable tap away.
                hiddenByFilter = live.isNotEmpty(),
                onClearQuery = { query = "" },
                onShowGroups = { savedTab = SessionTab.Groups.name },
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                // The floating bar is lifted 24dp and ~40dp tall; the extra
                // inset below keeps the last row clear of it and of the IME.
                contentPadding = PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    top = 4.dp,
                    bottom = BOTTOM_BAR_CLEARANCE,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                                onBounds = { bounds[item.slotId] = it },
                                onTap = { onOpen(session.id) },
                                onDragStart = { _ ->
                                    heldId = session.id
                                    dropTarget = null
                                },
                                onDrag = { position ->
                                    // Hit-testing compares window-space
                                    // rectangles against the finger's
                                    // window-space position, and ignores stale
                                    // bounds of offscreen cards.
                                    dropTarget = bounds.entries
                                        .firstOrNull { (id, rect) ->
                                            id != session.id && id in visibleSlotIds && rect.contains(position)
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
                                onSplitWith = { splitSourceId = session.id },
                                onTogglePin = { switcherViewModel.togglePin(session.id) },
                                onTogglePersistent = {
                                    // Persistent is owned by the ViewModel, OFF by default.
                                    switcherViewModel.setPersistent(session.id, session.id !in persistentIds)
                                },
                                onRename = { renameTargetId = session.id },
                                onClose = { pendingCloseId = session.id },
                                onCloseAll = {
                                    if (live.isNotEmpty()) pendingCloseAll = true
                                },
                            )
                        }
                        is SessionGridItem.Group -> {
                            SessionGroupCard(
                                group = item.group,
                                members = item.members,
                                thumbnails = thumbnails,
                                displayNames = displayNames,
                                glyphSlugs = glyphSlugs,
                                pinnedIds = pinnedIds,
                                persistentIds = persistentIds,
                                isDropTarget = item.group.memberIds.firstOrNull() == dropTarget,
                                isHeld = item.group.memberIds.firstOrNull() == heldId,
                                onBounds = { bounds[item.slotId] = it },
                                onTap = { overlayGroup = item.group },
                                onDragStart = { _ ->
                                    heldId = item.slotId
                                    dropTarget = null
                                },
                                onDrag = { position ->
                                    dropTarget = bounds.entries
                                        .firstOrNull { (id, rect) ->
                                            id != item.slotId && id in visibleSlotIds && rect.contains(position)
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

        // A centred, iOS-like floating control rather than a full-width bar.
        SessionBottomBar(
            query = query,
            onQueryChange = { query = it },
            onNewSession = onNewSession,
        )
    }

    splitSourceId?.let { sourceId ->
        SplitWithDialog(
            candidates = live.filter { it.id != sourceId },
            onDismiss = { splitSourceId = null },
            onChoose = { partnerId ->
                onGroupSessions(sourceId, partnerId)
                splitSourceId = null
            },
        )
    }

    renameTargetId?.let { id ->
        if (live.any { it.id == id }) {
            RenameSessionDialog(
                // Prefill with the current user rename, never the OSC title:
                // a title is program-owned and would otherwise overwrite the
                // name the user is about to set.
                initialValue = localNames[id]
                    ?: live.firstOrNull { it.id == id }?.name
                    ?: fallbackName,
                onConfirm = { newName ->
                    // Persist through the existing domain API, then stage the same
                    // value locally so the card reflects it in this composition.
                    switcherViewModel.rename(id, newName)
                    localNames = localNames + (id to newName)
                    renameTargetId = null
                },
                onDismiss = { renameTargetId = null },
            )
        }
    }

    // Re-resolve the overlay against the live groups: a closed member, a removal
    // or an ungroup closes it instead of showing a stale membership.
    overlayGroup?.let { stored ->
        groupsLive.firstOrNull { it.sameMembersAs(stored) }?.let { current ->
            SessionGroupOverlay(
                group = current,
                members = live.filter { it.id in current.memberIds },
                displayNames = displayNames,
                thumbnails = thumbnails,
                glyphSlugs = glyphSlugs,
                pinnedIds = pinnedIds,
                persistentIds = persistentIds,
                onDismiss = { overlayGroup = null },
                onOpen = { id ->
                    overlayGroup = null
                    onOpen(id)
                },
                onRemoveMember = { id ->
                    // Removing the last-but-one member dissolves the group
                    // back into single cards.
                    val remaining = current.memberIds.filterNot { it == id }
                    val updated = removeFromGroup(groups, current, id)
                    switcherViewModel.setGroups(updated)
                    overlayGroup = if (remaining.size >= MIN_GROUP_MEMBERS) {
                        updated.firstOrNull { it.memberIds.toSet() == remaining.toSet() }
                    } else {
                        null
                    }
                },
                onUngroupAll = {
                    // Every member returns to the singles view, so grouping is
                    // never a one-way door.
                    switcherViewModel.setGroups(ungroup(groups, current))
                    overlayGroup = null
                },
            )
        }
    }

    // The close confirmation, drawn outside the grid so a scroll underneath it
    // cannot move the question out from under the finger. One dialog serves both
    // a single close and close-all; the destructive call is not fired by the tap.
    if (pendingCloseId != null || pendingCloseAll) {
        // The name the card shows, not the stored one: closing a card the
        // user recognises by its title must not ask about a different string.
        val targetName = pendingCloseId
            ?.let { id -> displayNames[id] ?: live.firstOrNull { it.id == id }?.name }
            ?: stringResource(R.string.session_screen_default_name)
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
    /** Stable identity for the LazyVerticalGrid key. */
    val key: String
    /** The id used by drag hit-testing and the remembered order. */
    val slotId: String
    /** Every session represented by this slot, for the pinned-first sort. */
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
        override val key: String get() = "group:${group.memberIds.firstOrNull().orEmpty()}"
        override val slotId: String get() = group.memberIds.first()
        override val memberIds: List<String> get() = group.memberIds
    }
}
