package dev.drosh.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.domain.session.DEFAULT_SESSION_NAME
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.domain.session.SessionState
import dev.drosh.theme.DroshBorderSubtle
import dev.drosh.theme.DroshError
import dev.drosh.theme.DroshSurfaceHigh
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.ui.DroshDropdownMenu
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.DroshMenuItem
import dev.drosh.ui.R

/**
 * The two card filters. They used to be a row of tabs above the grid; now they
 * are the only two entries in the dropdown off the header title.
 */
internal enum class SessionTab { Groups, Singles }

/**
 * The name a card shows, in the order Termux treats as the session identity.
 *
 * Termux presents the title the program set with OSC 0 / OSC 2 as the session's
 * name — `TerminalSession.getTitle()` delegates to `TerminalEmulator.getTitle()`.
 * It never reads the foreground process name from /proc, which is why a runtime
 * binary such as `libchroot-ng.so` must never become a card title. Drosh follows
 * that order: OSC title first, then a user rename, then "shell".
 *
 * A persisted rename lives in `session.name`. The default "Default" is generated
 * by the repository, not chosen by the user, so it never wins over a real title.
 * `localNames` is a rename staged by this screen, so it belongs to the same
 * "user rename" tier, just ahead of the stored value.
 *
 * Source: github.com/termux/termux-app —
 *   terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java
 */
private fun sessionDisplayName(
    sessionId: String,
    localNames: Map<String, String>,
    titles: Map<String, String>,
    fallbackName: String,
    storedName: String? = null,
): String =
    titles[sessionId]?.takeIf { it.isNotBlank() }
        ?: localNames[sessionId]?.takeIf { it.isNotBlank() }
        ?: storedName?.takeIf { it.isNotBlank() && it != DEFAULT_SESSION_NAME }
        ?: fallbackName

/**
 * The user rename shown on a card's second line when the OSC title took the
 * first. Null when there is no rename or it would repeat the first line.
 */
private fun sessionSubtitle(
    sessionId: String,
    localNames: Map<String, String>,
    storedName: String?,
    displayName: String,
): String? {
    val rename = localNames[sessionId]?.takeIf { it.isNotBlank() }
        ?: storedName?.takeIf { it.isNotBlank() && it != DEFAULT_SESSION_NAME }
    return rename?.takeIf { it != displayName }
}

/**
 * Every session, as a card with the last screenshot of its terminal.
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
    // need a new callback parameter. The same ViewModel owns the card thumbnails
    // and the persistent toggle.
    val switcherViewModel: SessionSwitcherViewModel = hiltViewModel()
    val thumbnails by switcherViewModel.thumbnails.collectAsStateWithLifecycle()
    val persistentIds by switcherViewModel.persistentIds.collectAsStateWithLifecycle()
    val fallbackName = stringResource(R.string.session_screen_default_name)

    var query by rememberSaveable { mutableStateOf("") }
    // Stored as a string so the saved state survives a process death without
    // relying on a custom Saver for an enum.
    var savedTab by rememberSaveable { mutableStateOf(SessionTab.Singles.name) }
    val tab = SessionTab.entries.firstOrNull { it.name == savedTab } ?: SessionTab.Singles

    // TODO: persist — no domain field exists for pin. It stays UI-local and
    // resets when the screen leaves composition. A pin only sorts to the top of
    // its section; it never locks the card.
    var pinnedIds by remember { mutableStateOf(setOf<String>()) }
    // Staged rename shown immediately; the same value is written through to the
    // repository in the rename dialog's confirm handler below.
    var localNames by remember { mutableStateOf(emptyMap<String, String>()) }

    // TODO: persist — UI-local drag order and groups. The terminal owns the real
    // splits. Pinned cards are sorted to the top at render time, not reordered.
    var order by remember { mutableStateOf(emptyList<String>()) }
    var groups by remember { mutableStateOf(emptyList<SessionGroup>()) }

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
     *
     * UNTESTED — verify before use.
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
                groups = addToGroup(groups, targetGroup, incoming)
            }
            heldGroup != null -> {
                // A grouped member is represented by its group's slot, so
                // move that slot: a group dropped on a single reorders.
                order = moveSlot(order, heldGroup.memberIds.first(), target)
            }
            else -> {
                // Two singles become a group, target first so the group card
                // takes the target's slot. UNTESTED — verify before use.
                groups = groupTwoSessions(groups, target, held)
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
                onClearQuery = { query = "" },
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
                                thumbnail = thumbnails[session.id],
                                isActive = session.id == activeId,
                                isPinned = session.id in pinnedIds,
                                isPersistent = session.id in persistentIds,
                                isDropTarget = session.id == dropTarget,
                                isHeld = session.id == heldId,
                                onBounds = { bounds[item.slotId] = it },
                                onTap = { onOpen(session.id) },
                                onDragStart = { position ->
                                    heldId = session.id
                                    dropTarget = null
                                },
                                onDrag = { position ->
                                    // UNTESTED — verify before use: hit-testing
                                    // compares window-space rectangles against
                                    // the finger's window-space position, and
                                    // ignores stale bounds of offscreen cards.
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
                                    // UNTESTED — verify before use: a single
                                    // dropped on a single creates a group; on a
                                    // group it joins it. Never splits.
                                    if (target != null && held != null) {
                                        applyDrop(held, target)
                                    }
                                },
                                onSplitWith = { splitSourceId = session.id },
                                onTogglePin = {
                                    pinnedIds = if (session.id in pinnedIds) {
                                        pinnedIds - session.id
                                    } else {
                                        pinnedIds + session.id
                                    }
                                },
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
                                    // UNTESTED — verify before use: see SessionCard.
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
                                    // UNTESTED — verify before use: a group
                                    // dropped on a group merges; on a single
                                    // card it reorders. Never splits.
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
                pinnedIds = pinnedIds,
                persistentIds = persistentIds,
                onDismiss = { overlayGroup = null },
                onOpen = { id ->
                    overlayGroup = null
                    onOpen(id)
                },
                onRemoveMember = { id ->
                    // UNTESTED — verify before use: removing the last-but-one
                    // member dissolves the group back into two single cards.
                    val remaining = current.memberIds.filterNot { it == id }
                    val updated = removeFromGroup(groups, current, id)
                    groups = updated
                    overlayGroup = if (remaining.size >= MIN_GROUP_MEMBERS) {
                        updated.firstOrNull { it.memberIds.toSet() == remaining.toSet() }
                    } else {
                        null
                    }
                },
                onUngroupAll = {
                    // UNTESTED — verify before use: every member returns to the
                    // singles view, so grouping is never a one-way door.
                    groups = ungroup(groups, current)
                    overlayGroup = null
                },
            )
        }
    }

    // The close confirmation, drawn outside the grid so a scroll underneath it
    // cannot move the question out from under the finger. One dialog serves both
    // a single close and close-all; the destructive call is not fired by the tap.
    if (pendingCloseId != null || pendingCloseAll) {
        val targetName = pendingCloseId
            ?.let { id -> live.firstOrNull { it.id == id }?.name }
            ?: "Session"
        CloseSessionDialog(
            title = if (pendingCloseAll) "Tüm session'lar kapatılsın mı?" else "Session kapatılsın mı?",
            message = if (pendingCloseAll) {
                "${live.size} açık session'ın tamamı kapanacak."
            } else {
                "\"$targetName\" kapatılacak. Çıktısı geri alınamaz."
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

/**
 * The header. The whole title — label AND chevron — is one button that opens the
 * two-way filter. The ⋮ at the end holds the only destructive action left up here.
 */
@Composable
private fun SessionScreenHeader(
    count: Int,
    tab: SessionTab,
    onSelectTab: (SessionTab) -> Unit,
    onCloseAll: () -> Unit,
) {
    var filterExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = { filterExpanded = true },
                    ),
            ) {
                Row(
                    modifier = Modifier.align(Alignment.CenterStart),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.session_screen_title),
                        color = DroshText,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = DroshIcons.ChevronDown,
                        contentDescription = stringResource(R.string.session_screen_filter),
                        tint = DroshTextMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
                // The old ▦ / ▢ tab row is gone. These two entries filter the
                // grid exactly the way those tabs did; dispatch uses the stable
                // id, never the translated label.
                DroshDropdownMenu(
                    expanded = filterExpanded,
                    onDismissRequest = { filterExpanded = false },
                    items = listOf(
                        DroshMenuItem(
                            id = "filter_singles",
                            label = stringResource(R.string.session_screen_tab_singles),
                            icon = DroshIcons.Square,
                        ),
                        DroshMenuItem(
                            id = "filter_groups",
                            label = stringResource(R.string.session_screen_tab_groups),
                            icon = DroshIcons.SessionGrid,
                        ),
                    ),
                    onItemClick = { item ->
                        filterExpanded = false
                        when (item.id) {
                            "filter_singles" -> onSelectTab(SessionTab.Singles)
                            "filter_groups" -> onSelectTab(SessionTab.Groups)
                        }
                    },
                )
            }
            Text(
                text = stringResource(R.string.session_screen_count, count),
                color = DroshTextMuted,
                fontSize = 12.sp,
            )
        }
        // New-session lives only in the bottom bar now; this menu keeps close-all.
        HeaderMenuButton(onCloseAll = onCloseAll)
    }
}

/**
 * The grid with nothing in it.
 *
 * An empty box says "broken". This says "start here": what happened and the one
 * action that moves forward — the `+` in the bottom bar, which is always there.
 */
@Composable
private fun SessionScreenEmpty(
    tab: SessionTab,
    hasQuery: Boolean,
    onClearQuery: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(DroshSurfaceVariant)
                .border(1.dp, DroshBorderSubtle, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (tab == SessionTab.Groups) DroshIcons.SessionGrid else DroshIcons.Terminal,
                contentDescription = null,
                tint = DroshTextMuted,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(
                when {
                    hasQuery -> R.string.session_screen_empty_search_title
                    tab == SessionTab.Groups -> R.string.session_screen_empty_groups_title
                    else -> R.string.session_screen_empty_title
                },
            ),
            color = DroshText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                when {
                    hasQuery -> R.string.session_screen_empty_search_body
                    tab == SessionTab.Groups -> R.string.session_screen_empty_groups_body
                    else -> R.string.session_screen_empty_body
                },
            ),
            color = DroshTextMuted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
        )
        if (hasQuery) {
            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onClearQuery,
                    )
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.session_screen_clear_search),
                    color = DroshTextMuted,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/**
 * A centred, floating control: a moderate-width glass search pill and one glass
 * `+` beside it. Not full width, lifted off the bottom, and pushed above the IME.
 */
@Composable
private fun SessionBottomBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onNewSession: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = BOTTOM_BAR_LIFT)
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SessionSearchPill(
                query = query,
                onQueryChange = onQueryChange,
                modifier = Modifier.width(SEARCH_PILL_WIDTH),
            )
            TransparentAddButton(onNewSession = onNewSession)
        }
    }
}

/** The close question. Kept from the original screen: it is the single point
 * where a destructive session action is confirmed, whatever opened it. */
@Composable
private fun CloseSessionDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(DroshSurfaceHigh)
                .padding(20.dp),
        ) {
            Text(
                text = title,
                color = DroshText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                color = DroshText,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialogButton("İptal", DroshTextMuted, onDismiss)
                DialogButton("Kapat", DroshError, onConfirm)
            }
        }
    }
}

/** A dialog action, sized to the 48dp floor and coloured by its caller. */
@Composable
private fun DialogButton(
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(percent = 50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = tint,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** How far the floating bar sits above the very bottom of the screen. */
private val BOTTOM_BAR_LIFT = 24.dp

/** Grid inset: lift (24) + control height (40) + breathing room (32). */
private val BOTTOM_BAR_CLEARANCE = 96.dp

/** Moderate pill width — the bar stays centred rather than filling the row. */
private val SEARCH_PILL_WIDTH = 240.dp
