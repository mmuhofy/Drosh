package dev.drosh.ui.session

import androidx.compose.animation.core.Spring
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import dev.drosh.ui.keyboard.droshImePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.design.system.DroshDropdownMenu
import dev.drosh.design.system.DroshMenuItem
import dev.drosh.design.system.DroshMenuItemStyle
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import coil3.compose.AsyncImage
import dev.drosh.domain.session.DeviceIdentity
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.domain.session.SessionState
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.agent.components.IconAction
import kotlin.math.hypot

/**
 * Slide-in session drawer — a push/translate layout, so the terminal behind
 * slides by the same amount rather than being covered by a scrim.
 *
 * The colours were measured from a comparable app rather than chosen. Its
 * panel sits one step *above* the content behind it and the pills inside one
 * step above that; the previous version put the panel on the second-highest
 * surface, which washed the whole drawer out and made the pills vanish into
 * it. Rows are flat, with no card per session and no per-row buttons —
 * rename and delete live behind a long press, which is the platform idiom
 * and leaves the row clean.
 *
 * Pressed rows take a surface rather than only a ripple, because a ripple on
 * an already-dark row reads as nothing at all.
 */
@Composable
fun rememberSidebarPushState(isOpen: Boolean): SidebarPushState {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val sidebarWidth = remember(config) {
        val sw = config.screenWidthDp
        if (sw > 0) (sw * 0.78f).coerceIn(260f, 340f).dp else 300.dp
    }
    val sidebarWidthPx = with(density) { sidebarWidth.toPx() }
    val pushProgress by animateFloatAsState(
        targetValue = if (isOpen) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "sidebarPushProgress",
    )
    return remember(sidebarWidth) { SidebarPushState(sidebarWidth, sidebarWidthPx) }
        .also { it.progress = pushProgress }
}

class SidebarPushState internal constructor(
    val width: Dp,
    val widthPx: Float,
) {
    var progress by mutableFloatStateOf(0f)
        internal set
}

/** The translate the terminal applies to stay in step with the drawer. */
fun Modifier.sidebarPush(state: SidebarPushState): Modifier = this.graphicsLayer {
    translationX = state.progress * state.widthPx
}

@Composable
fun SessionSidebar(
    isOpen: Boolean,
    onOpenSettings: () -> Unit,
    onOpenAgent: () -> Unit = {},
    onOpenProjects: () -> Unit = {},
    onOpenSsh: () -> Unit = {},
    pushState: SidebarPushState? = null,
    /**
     * Opens [sessionId] in the second terminal pane.
     *
     * A callback rather than a ViewModel the sidebar reaches into, because the
     * sidebar lives in `:ui` and the pane state lives with the terminal screen
     * in `:app`. Null disables the affordance — which is the right behaviour
     * when there is only one session to split into.
     */
    onSplitSession: ((String) -> Unit)? = null,
    /** Opens this session straight into a floating window. */
    onFloatSession: ((String) -> Unit)? = null,
    /** True while a second pane is open, so the rows can show it. */
    isSplit: Boolean = false,
    /**
     * True while the second pane is a floating window rather than docked.
     *
     * The pair card draws a *seam*: a line across a single card, because that is
     * what a docked split looks like. A floating window is a window on top of
     * another terminal, and drawing a seam for it says the two halves are
     * stacked — which is exactly the wrong idea to give someone trying to work
     * out where their other session went.
     *
     * It also changes the card's action: closing a float is `closeSplit`, but
     * the pane comes *back* to where it was rather than the pair disappearing,
     * so the wording has to differ.
     */
    isFloatingPane: Boolean = false,
    /**
     * Ids of the two sessions sharing the screen, upper one first.
     *
     * Ids, not names. Swapping the panes changes which session is on top, and a
     * pair of names cannot express that — the sidebar would draw the card in the
     * old order while the terminal had already swapped, which is worse than not
     * showing the arrangement at all. The names are looked up from the session
     * list the sidebar already has.
     */
    splitSessions: Pair<String, String>? = null,
    /** Drops the second pane. */
    onCloseSplit: (() -> Unit)? = null,
    /** Exchanges which session is on top. */
    onSwapPanes: (() -> Unit)? = null,
    /** Turns the floating window back into a lower pane. */
    onDock: (() -> Unit)? = null,
    /**
     * Splits so that [draggedId] sits above [targetId].
     *
     * Both ids rather than a single session because a split is a *pair*: the
     * sidebar knows which row was held and which row it landed on, and that is
     * the whole of the user's intent. The terminal resolves the rest.
     */
    onDragSplit: ((draggedId: String, targetId: String) -> Unit)? = null,
) {
    val viewModel: SessionSwitcherViewModel = hiltViewModel()
    val deviceIdentityViewModel: DeviceIdentityViewModel = hiltViewModel()
    val state = pushState ?: rememberSidebarPushState(isOpen)

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(state.width)
            .graphicsLayer { translationX = (state.progress - 1f) * state.widthPx }
            .zIndex(1f)
            .background(DroshSurface)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
            ),
    ) {
        SidebarContent(
            viewModel = viewModel,
            deviceIdentityViewModel = deviceIdentityViewModel,
            onOpenSettings = onOpenSettings,
            onOpenAgent = onOpenAgent,
            onOpenProjects = onOpenProjects,
            onOpenSsh = onOpenSsh,
            onSplitSession = onSplitSession,
            onFloatSession = onFloatSession,
            isSplit = isSplit,
            isFloatingPane = isFloatingPane,
            splitSessions = splitSessions,
            onCloseSplit = onCloseSplit,
            onSwapPanes = onSwapPanes,
            onDock = onDock,
            onDragSplit = onDragSplit,
        )
    }
}

@Composable
private fun SidebarContent(
    viewModel: SessionSwitcherViewModel,
    deviceIdentityViewModel: DeviceIdentityViewModel,
    onOpenSettings: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenSsh: () -> Unit,
    onSplitSession: ((String) -> Unit)?,
    onFloatSession: ((String) -> Unit)?,
    isSplit: Boolean,
    isFloatingPane: Boolean,
    splitSessions: Pair<String, String>?,
    onCloseSplit: (() -> Unit)?,
    onSwapPanes: (() -> Unit)?,
    onDock: (() -> Unit)?,
    onDragSplit: ((String, String) -> Unit)?,
) {
    val sessions by viewModel.allSessions.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    val identity by deviceIdentityViewModel.identity.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var renamingSession by remember { mutableStateOf<SessionSnapshot?>(null) }

    /**
     * The session currently being held for a drop, if any.
     *
     * Plain state rather than a drag-and-drop implementation: this is a
     * press-and-hold on one row followed by a tap on another, and Compose has no
     * reliable cross-item drag that survives a LazyColumn's recycling — the item
     * that started the gesture may not be the one under the finger at the end.
     * A held id plus a per-row highlight gives the same outcome without
     * depending on which row survived.
     */
    var draggingSessionId by remember { mutableStateOf<String?>(null) }

    val filtered = remember(sessions, searchQuery) {
        if (searchQuery.isBlank()) sessions else sessions.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }
    // Most recently used first, so the session you were just in is the one at
    // the top rather than wherever it happened to be created.
    val live = filtered
        .filter { it.state != SessionState.Closed }
        .sortedByDescending { it.lastUsedAtMs }
    val ended = filtered.filter { it.state == SessionState.Closed }
    val active = filtered.firstOrNull { it.id == activeId }

    fun closeSearch() {
        searchOpen = false
        searchQuery = ""
    }


    // Background runs behind the system bar, content is pushed clear of it, so
    // the drawer does not read as having a strip of its own.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
            // Without this the keyboard covers the footer, taking the search
            // field and the close button with it while you are typing in it.
            .droshImePadding(),
    ) {
        SidebarHeader(
            identity = identity,
            onNewSession = { viewModel.createNew("shell") },
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            item(key = "nav_new") {
                PressableRow(DroshIcons.Plus, "New session") { viewModel.createNew("shell") }
            }
            item(key = "nav_agent") {
                PressableRow(DroshIcons.SquareTerminal, "Agent", onOpenAgent)
            }
            item(key = "nav_projects") {
                PressableRow(DroshIcons.Folder, "Projects", onOpenProjects)
                PressableRow(DroshIcons.Terminal, "SSH", onOpenSsh)
            }
            item(key = "nav_settings") {
                PressableRow(DroshIcons.Settings, "Settings", onOpenSettings)
            }

            item(key = "sessions_header") { SectionLabel("SESSIONS", null) }

            if (live.isEmpty()) {
                item(key = "live_empty") {
                    Text(
                        text = if (searchQuery.isBlank()) "No open sessions" else "No results",
                        color = DroshTextMuted,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            } else {
                // The two sessions sharing a split are drawn as one card, not as
                // two rows. They are not two sessions any more from the drawer's
                // point of view — they are one arrangement, and listing them one
                // under the other said so, while giving no way to act on them
                // together: closing one left a half-split behind, and swapping
                // them meant two taps on rows that look unrelated.
                //
                // So they collapse into a single card showing both names, either
                // side of the divider, in the order they are on screen. What
                // the user sees on the terminal is what the card shows.
                // The pair is lifted out of the row list and drawn first, as one
                // item. Doing it inside `items` would mean asking a row to emit
                // a sibling, which LazyListScope does not allow — one item, one
                // key — so the pairing is a pass over the list instead.
                /**
                 * Destructured once, so the pair is either fully present or
                 * absent.
                 *
                 * Reading `.first` off a nullable pair instead would leave the
                 * anchor nullable, and every use of it — `activate`, the name
                 * lookup, the active comparison — is a `String`. The id is a
                 * poor fallback for a name, so a session missing from the list
                 * is named by its id rather than by the previous row's name.
                 */
                val pairAnchor = splitSessions?.first
                val pairPartner = splitSessions?.second
                // Both, so a row can ask "am I already in a pane?" in one lookup.
                val pairOfSessions = setOfNotNull(pairAnchor, pairPartner)
                // Rank shifts by one for every session drawn inside a pair, so
                // the recency marks below stay honest.
                var rankOffset = 0

                live.forEachIndexed { index, snapshot ->
                    if (snapshot.id == pairAnchor && pairPartner != null) {
                        item(key = "splitpair_${snapshot.id}") {
                            SplitPairCard(
                                floating = isFloatingPane,
                                topName = snapshot.name,
                                bottomName = sessions.firstOrNull { it.id == pairPartner }
                                    ?.name ?: pairPartner,
                                isActiveTop = snapshot.id == activeId,
                                isActiveBottom = pairPartner == activeId,
                                onOpenTop = { viewModel.activate(snapshot.id) },
                                onOpenBottom = { viewModel.activate(pairPartner) },
                                onCloseSplit = onCloseSplit,
                                onSwapPanes = onSwapPanes,
                            )
                        }
                        rankOffset++
                    } else {
                        // `item`, not a bare call: this loop is not an `items {}`
                        // block, and a composable invoked straight from a
                        // LazyListScope has no slot to compose into. The error
                        // says only "a @Composable was invoked from the context
                        // of a @Composable function", which points at the caller
                        // rather than at the missing wrapper.
                        item(key = "live_${snapshot.id}") {
                            SessionRow(
                                snapshot = snapshot,
                                isActive = snapshot.id == activeId,
                                recencyRank = index - rankOffset,
                                onClick = { viewModel.activate(snapshot.id) },
                                onStartRename = { renamingSession = snapshot },
                                onDelete = { viewModel.delete(snapshot.id) },
                                onSplit = onSplitSession?.let { split -> { split(snapshot.id) } },
                                onFloat = onFloatSession?.let { float -> { float(snapshot.id) } },
                                // The drag target: hold this row and drop it on
                                // another to split. Both callbacks must exist, or
                                // a lone session shows a grip that lifts the row
                                // and then has nowhere to put it.
                                dragEnabled = onSplitSession != null && onDragSplit != null,
                                onDragStart = { draggingSessionId = snapshot.id },
                                isHeldForDrag = draggingSessionId == snapshot.id,
                                // The session already in a pane cannot be the
                                // held one: dropping it somewhere would move it,
                                // not start a split.
                                isSecondaryPane = snapshot.id in pairOfSessions,
                                floatingPane = isFloatingPane,
                                onDock = onDock,
                                isDropTarget = draggingSessionId != null &&
                                    draggingSessionId != snapshot.id,
                                onDrop = {
                                    val dragged = draggingSessionId
                                    draggingSessionId = null
                                    if (dragged != null && dragged != snapshot.id) {
                                        onDragSplit?.invoke(dragged, snapshot.id)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (ended.isNotEmpty()) {
                                    item(key = "ended_header") {
                                    SectionLabel("ENDED", null, "clear ${ended.size}") { viewModel.purgeEnded() }
                                    }
                                    items(ended.size, key = { "ended_${ended[it].id}" }) { index ->
                                    val snapshot = ended[index]
                                SessionRow(
                                    snapshot = snapshot,
                                    isActive = false,
                                    recencyRank = 0,
                                    onClick = {},
                                    onStartRename = { renamingSession = snapshot },
                                    onDelete = { viewModel.delete(snapshot.id) },
                                    )
                                    }
                                    }
                                    }
                                    
                                    renamingSession?.let { target ->
                                    RenameSessionDialog(
                                    initialValue = target.name,
                                    onConfirm = { newName ->
                                    viewModel.rename(target.id, newName)
                                    renamingSession = null
                                    },
                                    onDismiss = { renamingSession = null },
                                    )
                                    }
                                    
                                    SidebarFooter(
                                    searchOpen = searchOpen,
                                    query = searchQuery,
                                    onQueryChange = { searchQuery = it },
                                    onOpenSearch = { searchOpen = true },
                                    onCloseSearch = ::closeSearch,
                                    onOpenSettings = onOpenSettings,
                                    )
                                    }
                                    }
                                    
                                    @Composable
                                    private fun SidebarHeader(
                                    identity: DeviceIdentity?,
                                    onNewSession: () -> Unit,
                                    ) {
                                    val name = identity?.marketingName.orEmpty().ifBlank { "This device" }
                                    val subtitle = identity?.takeIf { it.marketingName != it.model }
                                    ?.let { it.manufacturer + " " + it.model }
                                    ?.takeIf { it.isNotBlank() }
                                    ?: identity?.model.orEmpty()
                                    
                                    Row(
                                    modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 20.dp, end = 14.dp)
                                    .padding(top = 12.dp, bottom = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                    DeviceBadge(
                                    imageUrl = identity?.visualUrl,
                                    fallbackLetter = name.firstOrNull()?.uppercase() ?: "?",
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                    text = name,
                                    color = DroshText,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    )
                                    if (subtitle.isNotBlank()) {
                                    Text(
                                    text = subtitle,
                                    color = DroshTextMuted,
                                    fontSize = 11.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    )
                                    }
                                    }
                                    CircleButton(
                                    onClick = onNewSession,
                                    contentDescription = "New session",
                                    icon = DroshIcons.Plus,
                                    )
                                    }
                                    }
                                    
                                    /**
                                    * The device in the circle: its product image once one has been looked up and
                                    * cached, a monogram until then. Wikimedia may simply have nothing for the
                                    * model, so the monogram is the resting state rather than a failure.
                                    *
                                    * Shared with the settings header, which asks the same question.
                                    */
                                    @Composable
                                    fun DeviceBadge(
                                    imageUrl: String?,
                                    fallbackLetter: String,
                                    size: Dp = 36.dp,
                                    ) {
                                    Box(
                                    modifier = Modifier
                                    .size(size)
                                    .clip(CircleShape)
                                    .background(
                                    // Tinted with the accent rather than a flat grey, so the
                                    // placeholder reads as deliberate instead of as a missing image.
                                    brush = Brush.linearGradient(
                                    listOf(
                                    DroshPrimary.copy(alpha = 0.22f),
                                    DroshPrimary.copy(alpha = 0.10f),
                                ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = fallbackLetter,
                color = DroshPrimary,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}


/**
 * A flat icon + label row. The whole 48dp band is the target, and holding it
 * raises a surface rather than only rippling — on a row this dark a ripple
 * alone is not perceivable.
 */
@Composable
private fun PressableRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // Needed inside `pointerInput`, whose block is not a composable scope: the
    // touch slop has to be in pixels, and the slop is a dp constant.
    val density = LocalDensity.current
    val surface by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "rowPress",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(DroshSurfaceVariant.copy(alpha = surface))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = DroshTextSecondary,
            modifier = Modifier.size(19.dp),
        )
        Text(
            text = label,
            color = DroshText,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionLabel(
    label: String,
    trailing: String?,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp)
            .padding(top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = DroshTextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            val surface by animateFloatAsState(
                targetValue = if (pressed) 1f else 0f,
                animationSpec = tween(durationMillis = 120),
                label = "actionPress",
            )
            Text(
                text = action,
                color = DroshPrimary,
                fontSize = 11.sp,
                modifier = Modifier
                    .background(DroshSurfaceVariant.copy(alpha = surface), RoundedCornerShape(6.dp))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onAction,
                    )
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        } else if (trailing != null) {
            Text(text = trailing, color = DroshTextMuted, fontSize = 11.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    snapshot: SessionSnapshot,
    isActive: Boolean,
    /** 0 is the session you were just in, higher means older. */
    recencyRank: Int,
    onClick: () -> Unit,
    onStartRename: () -> Unit,
    onDelete: () -> Unit,
    /** Opens this session in the second pane, or null when it cannot. */
    onSplit: (() -> Unit)? = null,
    /** Opens this session straight into a floating window. */
    onFloat: (() -> Unit)? = null,
    /**
     * Swaps the two panes.
     *
     * Exists because the row menu offers "Swap into split" on the lower pane's
     * row, and that entry did nothing: the label was chosen at render time and
     * `onItemClick` only matched "Split below". A menu entry that is drawn and
     * then ignored is worse than one that is absent, and this one was drawn on
     * exactly the row where a user would try it.
     */
    onSwapPanes: (() -> Unit)? = null,
    /**
     * Turns a floating window back into a lower pane.
     *
     * Only offered on a row that is already floating, and only wired when the
     * caller can do it. It was rendered before with nothing behind it, so "Dock
     * pane" was one of two entries that appeared and then did nothing.
     */
    onDock: (() -> Unit)? = null,
    /**
     * Whether holding this row arms it for a drop on another row.
     *
     * Only true when there is somewhere to drop it *and* something to drop it
     * into. A lone session shows no grip at all rather than one that lifts the
     * row and then has nowhere to put it.
     */
    dragEnabled: Boolean = false,
    /** Called when the row is picked up. */
    onDragStart: () -> Unit = {},
    /** True while some *other* row is held, so this one reads as a target. */
    isDropTarget: Boolean = false,
    /** Called when this row is tapped while another one is held. */
    onDrop: (String) -> Unit = {},
    /** True when *this* row is the one currently held. */
    isHeldForDrag: Boolean = false,
    /**
     * True when this row's session is the one in the second pane.
     *
     * The menu's wording depends on it. "Split below" on the session that is
     * *already* below describes something that is not what pressing it does —
     * it replaces the other session — and "Open in window" on the session already
     * floating offers an action whose result is already on screen.
     */
    isSecondaryPane: Boolean = false,
    /** True while the second pane is a floating window. */
    floatingPane: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }

    /** True while this row is the one being held for a drop. */
    val dragging = dragEnabled && isHeldForDrag

    val ended = snapshot.state == SessionState.Closed

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // Needed inside `pointerInput`, whose block is not a composable scope: the
    // touch slop has to be in pixels, and the slop is a dp constant.
    val density = LocalDensity.current
    // Stays raised while the menu is open, not only while a finger is down:
    // the row is still the thing the menu belongs to. Dismissing the menu —
    // including by tapping empty space — puts it back.
    val surface by animateFloatAsState(
        targetValue = if (pressed || menuOpen) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "sessionPress",
    )

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The active row tightens its left edge to make room for the
                // mark, so the name column stays in one place for every row.
                .padding(start = 22.dp, end = 12.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(DroshSurfaceVariant.copy(alpha = surface))
                // The drop target lights with the accent, not just a raised
                // surface. A held row and a target row are otherwise identical
                // apart from the recency mark, and the user has to see at a
                // glance which one their finger should land on.
                .then(
                    if (isDropTarget) {
                        Modifier.border(
                            width = 1.dp,
                            color = DroshPrimary,
                            shape = RoundedCornerShape(10.dp),
                        )
                    } else {
                        Modifier
                    },
                )
                /**
                 * A held row shrinks and lifts.
                 *
                 * Dimming alone was not enough of a signal: the row still looked
                 * like a row, so releasing the finger on another row was a guess.
                 * Shrinking to a chip is what says "you are carrying this, it is
                 * not in place any more" — and it is proportional to how long you
                 * hold, so a short press visibly returns to full size instead of
                 * snapping back, which is what tells you the menu was the first
                 * stage and not a failed drag.
                 */
                .then(
                    if (dragging) {
                        // Every property here is a Float in pixels, not a Dp:
                        // `graphicsLayer` is the draw-time layer, and it has no
                        // density to convert with. A Dp passed here is a type
                        // error, and a value silently scaled would be worse.
                        Modifier.graphicsLayer {
                            scaleX = HELD_SCALE
                            scaleY = HELD_SCALE
                            // Lifted off the surface. Without a shadow the shrink
                            // reads as the row being deleted rather than picked up.
                            shadowElevation = HELD_ELEVATION_PX
                            // `shape` and `clip` take Dp-geometry — `clip = true`
                            // alone would cut the shadow off at the bounds, which
                            // is the one thing the elevation is for.
                            shape = RoundedCornerShape(10.dp)
                            clip = false
                        }
                    } else {
                        Modifier
                    },
                )
                /**
                 * One gesture recogniser, because two would race.
                 *
                 * The row needs three outcomes from one press: open it, show its
                 * menu, or pick it up for a drop somewhere else. `combinedClickable`
                 * spends one long press and one double tap, which is not enough
                 * once dragging is added — and two `pointerInput` modifiers on the
                 * same row do not compose either, they compete for the pointer and
                 * whichever consumes first wins. That is the same trap the top bar
                 * recorded for its own split gestures.
                 *
                 * So the press is timed here, in one recogniser:
                 *
                 *  - released before [dragAfter]  → tap: open, or drop
                 *  - still down at [dragAfter]     → drag: shrink and pick up
                 *  - released between the two      → the menu, as it always was
                 *
                 * The middle window is what the request asked for and what
                 * `combinedClickable` cannot express: one gesture, two long-press
                 * thresholds, and the *short* one still belongs to the menu.
                 */
                .pointerInput(snapshot.id, dragEnabled, isDropTarget) {
                    val viewConfig = viewConfiguration
                    val touchSlop = with(density) { TOUCH_SLOP.toPx() }
                    val menuAfter = viewConfig.longPressTimeoutMillis
                    val dragAfter = (menuAfter * DRAG_HOLD_MULTIPLIER).toLong()
                    var dragStarted = false

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startedAt = System.currentTimeMillis()
                        var consumedMove = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break

                            if (change.changedToUpIgnoreConsumed()) {
                                change.consume()
                                val held = System.currentTimeMillis() - startedAt
                                when {
                                    dragStarted -> Unit
                                    isDropTarget -> onDrop(snapshot.id)
                                    held >= menuAfter -> Unit // menu is opened below, not a tap
                                    menuOpen -> menuOpen = false
                                    else -> onClick()
                                }
                                break
                            }

                            val held = System.currentTimeMillis() - startedAt

                            // Rolling the finger first means this is a scroll, and the
                            // gesture has to be returned — otherwise a finger resting on a
                            // row while the drawer moves would pick the row up.
                            val movement = change.positionChange()
                            val travelled = hypot(movement.x, movement.y)
                            if (travelled != 0f) {
                                if (dragStarted) {
                                    consumedMove = true
                                    change.consume()
                                } else if (travelled > touchSlop) {
                                    break
                                }
                            }

                            // A *longer* hold enters drag mode. The menu is a separate,
                            // short release; one gesture must not open a menu and also
                            // start a drag, so the drag path clears it.
                            if (!dragStarted && dragEnabled && held >= dragAfter) {
                                dragStarted = true
                                menuOpen = false
                                onDragStart()
                            }
                        }

                        // Release without ever starting the drag, but past the long-press
                        // threshold. That is "short hold": open the menu and leave it up.
                        if (!dragStarted && !consumedMove) {
                            val held = System.currentTimeMillis() - startedAt
                            if (held >= menuAfter && !menuOpen) {
                                menuOpen = true
                            }
                        }
                    }
                }
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The mark fades with recency, the way a taskbar does for windows
            // you touched recently and not at all for ones you have not. A
            // fixed dot for every session carried no information; so did
            // weight, which the first version used instead.
            val (markHeight, markAlpha) = when {
                isActive -> 20.dp to 1f
                recencyRank == 0 -> 14.dp to 0.55f
                recencyRank < 3 -> 10.dp to 0.3f
                else -> 6.dp to 0.18f
            }
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(markHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(DroshPrimary.copy(alpha = markAlpha)),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = snapshot.name,
                color = DroshText,
                fontSize = 15.sp,
                // Every name reads the same weight. Making the active one
                // heavier made the list look like it held two kinds of thing;
                // the active row carries a mark and a surface instead.
                fontWeight = FontWeight.SemiBold,
                textDecoration = if (ended) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            // The drag handle. Its own target rather than the row, because the
            // row's long press is already spoken for by the menu, and Compose
            // gives no reliable way to have one gesture mean two things — the
            // two detectors race on the same timeout and whichever consumes
            // first wins. A separate grip is unambiguous, and it doubles as the
            // affordance that says a row can be dragged at all.
            //
            // Hidden on a session that is already in a pane: pressing it there
            // would replace the pane's other session rather than start a split,
            // and a grip that means "do something to the other row" is worse than
            // no grip at all.
            if (onSplit != null && !ended && !isSecondaryPane) {
                SplitDragHandle(onSplit = onSplit)
            }
        }

        // The same menu the terminal's overflow button uses, so the two cannot
        // drift apart visually. Nudged to the row's content edge because left
        // alone it sits flush against the screen.
        DroshDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = DpOffset(20.dp, 0.dp),
            items = buildList {
                if (onSplit != null && !ended) {
                    // PanelLeft rather than a two-column glyph. The lucide
                    // artifact is not on this machine to check a name against,
                    // and an icon whose absence only shows up at runtime is not
                    // worth the two lines a custom vector would cost.
                    add(
                        DroshMenuItem(
                            // "Split below" reads as a claim about this session's
                            // position. Once it *is* the lower pane that claim is
                            // false — the action swaps the pair — so the entry says
                            // what it does.
                            label = if (isSecondaryPane) "Swap into split" else "Split below",
                            icon = DroshIcons.PanelLeft,
                        ),
                    )
                }
                if (onFloat != null && !ended) {
                    add(
                        DroshMenuItem(
                            // "Dock pane" only where it can be acted on: it is the
                            // inverse of "Open in window", so it belongs to a
                            // row that is already floating and to nothing else.
                            label = if (isSecondaryPane && floatingPane) {
                                "Dock pane"
                            } else {
                                "Open in window"
                            },
                            icon = if (isSecondaryPane && floatingPane) {
                                DroshIcons.PanelBottom
                            } else {
                                DroshIcons.Square
                            },
                        ),
                    )
                }
                add(DroshMenuItem(label = "Rename", icon = DroshIcons.Pencil))
                add(
                    DroshMenuItem(
                        label = "Delete",
                        icon = DroshIcons.Trash2,
                        style = DroshMenuItemStyle.Destructive,
                    ),
                )
            },
            onItemClick = { item ->
                when (item.label) {
                    "Split below" -> onSplit?.invoke()
                    // Both of these are reachable only on a row that is already
                    // in the split, and both used to fall through to nothing.
                    "Swap into split" -> onSwapPanes?.invoke()
                    "Dock pane" -> onDock?.invoke()
                    "Open in window" -> onFloat?.invoke()
                    "Rename" -> onStartRename()
                    "Delete" -> onDelete()
                }
            },
        )
    }
}


/**
 * Names the two sessions sharing the screen.
 *
 * A split drawn with nothing in the drawer explaining it reads as the app
 * having rendered the same terminal twice. Naming both halves — with the split
 * glyph between them, since that is what the icon means everywhere else here —
 * is the difference between a second terminal and a rendering bug.
 *
 * Both names are ellipsised from the middle rather than the end: sessions are
 * named after what they are for, and the distinguishing end of a name is the
 * one a line ending would cut off.
 */
/**
 * The two sessions sharing a split, drawn as one card.
 *
 * ## Why one card and not two rows
 *
 * They are not two sessions any more from the drawer's point of view — they are
 * one arrangement. Listed one under the other, the drawer said "two" while the
 * terminal said "one split", and every action on them acted on one at a time:
 * closing one left a half-split behind, and swapping them meant two taps on rows
 * that looked unrelated.
 *
 * ## Why the divider is drawn between them
 *
 * The card is a diagram of the screen, so it has the screen's shape: two names
 * either side of a seam, in the order they are on it. A vertical divider between
 * two stacked terminals is a line across the card; drawing anything else — an
 * arrow, a chevron — would be describing something the card does not look like.
 *
 * Both halves are independently tappable, because switching between the two is
 * the most common thing anyone does with a split.
 */
@Composable
private fun SplitPairCard(
    /**
     * True when the second pane is a window on top rather than a half below.
     *
     * It changes the shape of the card and not just a label: a docked pair is
     * two stacked terminals, so the card is two names either side of a seam. A
     * floating window is *over* one of them, so the card is a full-width row with
     * a small inset one marked as the floating one — drawing a seam for a window
     * on top is a diagram of something else.
     */
    floating: Boolean,
    topName: String,
    bottomName: String,
    isActiveTop: Boolean,
    isActiveBottom: Boolean,
    onOpenTop: () -> Unit,
    onOpenBottom: () -> Unit,
    onCloseSplit: (() -> Unit)?,
    onSwapPanes: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(DroshSurfaceVariant),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SplitPairHalf(
            name = topName,
            active = isActiveTop,
            onClick = onOpenTop,
            modifier = Modifier.weight(1f),
        )

        // The seam, for a docked pair only. Matches the divider on the terminal:
        // same colour, same weight, so the card reads as a miniature of what is
        // on screen.
        if (!floating) {
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(28.dp)
                    .background(DroshOutline.copy(alpha = 0.7f)),
            )
        }

        SplitPairHalf(
            name = bottomName,
            active = isActiveBottom,
            onClick = onOpenBottom,
            // Inset when floating: the window sits *over* the pane below it, so
            // it is drawn inside the card's width rather than beside it. Without
            // the inset the card says "two halves", which is what a docked split
            // means and not what a floating window means.
            modifier = if (floating) {
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DroshSurfaceHigh)
            } else {
                Modifier.weight(1f)
            },
        )

        // The same two things the divider and the floating window's chrome can
        // do, in the same order. A control that exists on the terminal but not on
        // its own diagram is a control the drawer does not admit exists.
        if (onSwapPanes != null) {
            IconAction(
                icon = DroshIcons.ArrowUpDown,
                contentDescription = "Paneleri degistir",
                onClick = onSwapPanes,
                modifier = Modifier.size(34.dp),
                tint = DroshTextMuted,
            )
        }
        if (onCloseSplit != null) {
            IconAction(
                icon = DroshIcons.X,
                contentDescription = "Bolmeyi kapat",
                onClick = onCloseSplit,
                modifier = Modifier.size(34.dp),
                tint = DroshTextMuted,
            )
        }
    }
}

/**
 * How much longer than a plain long press the drag threshold is.
 *
 * Two. Long enough that the menu can appear and the user can see it and keep
 * holding — the only cue that there is a second stage — and short enough that
 * "hold it a bit longer" is not a wait.
 */
private const val DRAG_HOLD_MULTIPLIER = 2

/** How far a finger may travel before a press becomes a drawer scroll. */
private val TOUCH_SLOP = 12.dp

/** How small a held row shrinks. Small enough to look carried, big enough to read. */
private const val HELD_SCALE = 0.82f

/** The lift a held row gets, in pixels. `graphicsLayer` takes a Float. */
private val HELD_ELEVATION_PX = 8f

/** One session's half of a split pair card. */
@Composable
private fun SplitPairHalf(
    name: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(if (active) 20.dp else 10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (active) DroshPrimary else DroshOutline),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            color = if (active) DroshText else DroshTextSecondary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

/**
 * The grip that drags a session into the second pane.
 *
 * A deliberate threshold rather than "any drag opens the split": a user who
 * brushes the grip while scrolling the list should not end up with two panes
 * they did not ask for, and one that opens only past half the row's width can
 * be cancelled by dragging back without lifting off.
 *
 * The progress is drawn on the grip itself rather than as an overlay across
 * the terminal. An overlay would be the clearer sign, but it has to be driven
 * from here and this row is inside a drawer that is itself translating — the
 * two would not agree about where the finger is.
 */
@Composable
private fun SplitDragHandle(
    onSplit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragged by remember { mutableFloatStateOf(0f) }

    // In dp, not pixels. This was a raw 96f, which on a 3x device is about 32dp
    // of travel — a brush, not a drag — and on a 1x device is 96dp, so the same
    // gesture needed a wildly different amount of finger on different phones.
    val thresholdPx = with(LocalDensity.current) { SPLIT_DRAG_THRESHOLD.toPx() }

    val progress = (dragged / thresholdPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val armed = progress >= 1f

    val barColor by animateColorAsState(
        targetValue = when {
            armed -> DroshPrimary
            progress > 0f -> DroshTextMuted
            else -> DroshOutline
        },
        label = "splitGripColor",
    )

    Box(
        modifier = modifier
            // 24dp wide and the full row height: a grip inside a 44dp row has
            // to be a decent target, and the row has no other vertical room.
            .width(24.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(6.dp))
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    dragged += delta
                    // Fires the moment the threshold is *crossed*, not on
                    // release, so the split appears while the finger is still
                    // down and the gesture reads as a cause. `dragged` resets at
                    // the same moment so a long drag past the threshold cannot
                    // re-fire it every frame.
                    if (dragged >= thresholdPx) {
                        dragged = 0f
                        onSplit()
                    } else if (dragged <= -thresholdPx) {
                        dragged = 0f
                    }
                },
                // The part that was missing, and the whole of the "it split on
                // its own". Without it the accumulator kept its value after the
                // finger lifted, so a drag stopped at 90% of the threshold armed
                // the grip for good: the next incidental brush opened a split the
                // user had abandoned a moment ago. Accumulated travel is a
                // property of one gesture and has to die with it, or the grip
                // stops being a deliberate act and becomes something that happens
                // to you.
                onDragStopped = { dragged = 0f },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Three bars that close up as the drag approaches the threshold, so the
        // grip itself reads as filling. An opacity fade was the first attempt
        // and it was invisible against the row's own surface at these sizes.
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(3) { index ->
                val inRange = progress > (index + 1) / 3f
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(if (inRange) 16.dp else 10.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (inRange) barColor else barColor.copy(alpha = 0.45f)),
                )
            }
        }
    }
}

/**
 * How far the grip must be dragged before the split opens.
 *
 * Deliberate rather than incidental. Past the row's own width would be too far
 * to be comfortable on a narrow phone, and short enough to be crossed by a
 * careless brush is not a threshold at all.
 */
private val SPLIT_DRAG_THRESHOLD = 64.dp

@Composable
private fun SidebarFooter(
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val focusManager = LocalFocusManager.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DroshSurface)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The pill and the field occupy the same slot and keep the same width,
        // so the row never jumps; what animates is the swap between them and
        // the button turning into the close control. Animating a weight for
        // this was the obvious approach and it is invalid — weight must be
        // greater than zero, so the collapsed state crashed the moment the
        // footer composed.
        Box(modifier = Modifier.weight(1f)) {
            AnimatedContent(
                targetState = searchOpen,
                transitionSpec = {
                    (fadeIn(tween(200)) + slideInHorizontally { it / 8 }) togetherWith
                        (fadeOut(tween(120)) + slideOutHorizontally { -it / 8 })
                },
                label = "searchSwap",
            ) { open ->
                if (open) {
                    SearchField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    // Collapsed it is a plain target, not a text field: a focused
                    // field here summons the keyboard, which resizes the drawer
                    // and fights the push animation.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                            .clip(RoundedCornerShape(50))
                            .background(DroshSurfaceVariant)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = onOpenSearch,
                            )
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Icon(
                            imageVector = DroshIcons.Search,
                            contentDescription = null,
                            tint = DroshTextMuted,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Search sessions",
                            color = DroshTextMuted,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(DroshSurfaceVariant.copy(alpha = 0.75f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {
                        focusManager.clearFocus()
                        if (searchOpen) onCloseSearch() else onOpenSettings()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = searchOpen,
                transitionSpec = {
                    (fadeIn(tween(160)) + scaleIn()) togetherWith
                        (fadeOut(tween(120)) + scaleOut())
                },
                label = "searchButton",
            ) { open ->
                Icon(
                    imageVector = if (open) DroshIcons.X else DroshIcons.Settings,
                    contentDescription = if (open) "Close search" else "Settings",
                    tint = DroshTextSecondary,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Row(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(50))
            .background(DroshSurfaceVariant)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = DroshIcons.Search,
            contentDescription = null,
            tint = DroshTextMuted,
            modifier = Modifier.size(16.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(text = "Search sessions", color = DroshTextMuted, fontSize = 14.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = DroshText, fontSize = 14.sp),
                cursorBrush = SolidColor(DroshPrimary),
                interactionSource = interactionSource,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }
    }
}

@Composable
private fun CircleButton(
    onClick: () -> Unit,
    contentDescription: String,
    icon: ImageVector,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // Needed inside `pointerInput`, whose block is not a composable scope: the
    // touch slop has to be in pixels, and the slop is a dp constant.
    val density = LocalDensity.current
    val surface by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "circlePress",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "circleScale",
    )
    Box(
        modifier = Modifier
            .size(42.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(DroshSurfaceVariant.copy(alpha = if (pressed) 1f else 0.75f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = DroshTextSecondary,
            modifier = Modifier.size(19.dp),
        )
    }
}
