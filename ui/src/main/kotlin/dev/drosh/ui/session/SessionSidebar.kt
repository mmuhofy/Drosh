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
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.design.system.DroshDropdownMenu
import dev.drosh.design.system.DroshMenuItem
import dev.drosh.ui.settings.SettingsViewModel
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
import dev.drosh.ui.R
import dev.drosh.domain.session.SessionState
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.agent.components.IconAction
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.map

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
    /**
     * Raises or lowers the terminal's keyboard, from the drawer's footer.
     *
     * A callback because the drawer lives in `:ui` and the keyboard is a
     * window the terminal screen owns. Null hides the button — which is what
     * a drawer opened somewhere without a terminal should do.
     */
    onToggleKeyboard: (() -> Unit)? = null,
    /**
     * Called when a held row is released over the terminal beside the drawer,
     * past its right edge.
     *
     * The drawer covers the terminal's left side and nothing else, so "past the
     * drawer" and "over the terminal" are the same place. A card carried there
     * and dropped means "split this with the session already on screen", and the
     * terminal is the only thing that can answer it — which is why this is a
     * callback and not state the drawer reaches into.
     */
    onDropOnTerminal: ((String) -> Unit)? = null,
    /**
     * Called with the session being carried, or null when none is.
     *
     * So the terminal can light itself up as the place a card dropped outside
     * the drawer will land. The drawer knows this and the terminal does not;
     * one callback says so once instead of both guessing.
     */
    onHoldChange: ((String?) -> Unit)? = null,
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
            isOpen = isOpen,
            isSplit = isSplit,
            isFloatingPane = isFloatingPane,
            splitSessions = splitSessions,
            onCloseSplit = onCloseSplit,
            onSwapPanes = onSwapPanes,
            onDock = onDock,
            onDragSplit = onDragSplit,
            onToggleKeyboard = onToggleKeyboard,
            onDropOnTerminal = onDropOnTerminal,
            onHoldChange = onHoldChange,
        )
    }
}

@Composable
private fun SidebarContent(
    viewModel: SessionSwitcherViewModel,
    deviceIdentityViewModel: DeviceIdentityViewModel,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    onOpenSettings: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenSsh: () -> Unit,
    onSplitSession: ((String) -> Unit)?,
    onFloatSession: ((String) -> Unit)?,
    /**
     * Whether the drawer is showing.
     *
     * Not the push progress, which animates through every value on the way out.
     * A hold is cancelled here so it cannot outlive the drawer, and cancelling it
     * on the progress would cancel it the moment the drawer started to move.
     */
    isOpen: Boolean,
    isSplit: Boolean,
    isFloatingPane: Boolean,
    pushState: SidebarPushState? = null,
    splitSessions: Pair<String, String>?,
    onCloseSplit: (() -> Unit)?,
    onSwapPanes: (() -> Unit)?,
    onDock: (() -> Unit)?,
    onToggleKeyboard: (() -> Unit)? = null,
    onDragSplit: ((String, String) -> Unit)?,
    onDropOnTerminal: ((String) -> Unit)? = null,
    onHoldChange: ((String?) -> Unit)? = null,
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
    // One holder for the whole drawer. The row being carried and the row
    // being aimed at are different rows; per-row state cannot see across.
    val dragState = remember { SidebarDragState() }

    // Whether the rows sit on a surface of their own. One read for the whole
    // drawer: the rows are the same shape or they are not.
    val sessionSurface by settingsViewModel.snapshot
        .map { it.appearance.sessionSurface }
        .collectAsStateWithLifecycle(initialValue = true)

    // A hold that outlives the drawer.
    //
    // The drawer stays composed while it is closed — it is translated off screen,
    // not disposed — so a session picked up and then closed over left its row
    // drawn as "held" and every other row outlined as a drop target. The next tap
    // on any row then dropped it and opened a split, long after the user had
    // stopped holding anything.
    //
    // `isOpen` rather than the push progress: the progress is an animation that
    // passes through every value on the way out, so clearing on it would also
    // cancel the hold the moment the drawer started to move.
    LaunchedEffect(isOpen) {
        if (!isOpen) {
            dragState.cancel()
            onHoldChange?.invoke(null)
        }
    }

    /**
     * The card, drawn in a window of its own.
     *
     * The row that is carried goes invisible and the card takes its place here:
     * a popup is a child of the window rather than of the list, so nothing clips
     * it and it can follow the finger past the drawer's edge — out of the drawer
     * and over whatever is beside it. Dragging something and having it stop at
     * the edge of the thing you are dragging it from is a drag that does not
     * really drag.
     *
     * Anchored at the row's own rectangle, moved by the gesture's offset, so the
     * card leaves under the finger exactly where the finger picked it up.
     */
    val heldRect = dragState.heldRect()
    val heldSession = sessions.firstOrNull { it.id == dragState.heldId }
    if (heldRect != null && heldSession != null) {
        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(
                (heldRect.left + dragState.offset.x).roundToInt(),
                (heldRect.top + dragState.offset.y).roundToInt(),
            ),
            // Not focusable, and it does not dismiss on an outside touch: the
            // gesture belongs to the row underneath it, and a popup that ate
            // the first event of the drag would end the drag it is drawing.
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        ) {
            SessionDragCard(
                name = heldSession.name,
                width = with(LocalDensity.current) { heldRect.width.toDp() },
            )
        }
    }

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
            // A gap between the rows only when they are tiles. Two lines that
            // touch read as a list; two tiles that touch read as one long
            // surface with seams in it.
            verticalArrangement = if (sessionSurface) {
                Arrangement.spacedBy(4.dp)
            } else {
                Arrangement.Top
            },
        ) {
            item(key = "nav_new") {
                PressableRow(DroshIcons.Plus, stringResource(R.string.sidebar_new_session)) { viewModel.createNew("shell") }
            }
            item(key = "nav_agent") {
                PressableRow(DroshIcons.SquareTerminal, stringResource(R.string.agent_title), onOpenAgent)
            }
            item(key = "nav_projects") {
                PressableRow(DroshIcons.Folder, stringResource(R.string.sidebar_projects), onOpenProjects)
                PressableRow(DroshIcons.Terminal, stringResource(R.string.sidebar_ssh), onOpenSsh)
            }
            item(key = "nav_settings") {
                PressableRow(DroshIcons.Settings, stringResource(R.string.sidebar_settings), onOpenSettings)
            }

            item(key = "sessions_header") { SectionLabel(stringResource(R.string.sidebar_sessions), null) }

            if (live.isEmpty()) {
                item(key = "live_empty") {
                    Text(
                        text = if (searchQuery.isBlank()) {
                            stringResource(R.string.sidebar_empty_sessions)
                        } else {
                            stringResource(R.string.sidebar_no_results)
                        },
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
                // Rank shifts by two for a pair, since the card stands in for both
                // sessions and only one of them is reached in this loop.
                var rankOffset = 0

                live.forEachIndexed { index, snapshot ->
                    if (snapshot.id == pairAnchor && pairPartner != null) {
                        item(key = "splitpair_${snapshot.id}") {
                            // The card is a picture of the two panes and nothing
                            // else: every action it used to carry sits in the
                            // three dots beside it, the same place a session row
                            // keeps its own. A card with buttons on it is two
                            // kinds of thing wearing one shape.
                            var pairMenuOpen by remember { mutableStateOf(false) }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SplitPairCard(
                                    floating = isFloatingPane,
                                    topName = snapshot.name,
                                    bottomName = sessions.firstOrNull { it.id == pairPartner }
                                        ?.name ?: pairPartner,
                                    isActiveTop = snapshot.id == activeId,
                                    isActiveBottom = pairPartner == activeId,
                                    onOpenTop = { viewModel.activate(snapshot.id) },
                                    onOpenBottom = { viewModel.activate(pairPartner) },
                                    modifier = Modifier.weight(1f),
                                )

                                IconButton(
                                    onClick = { pairMenuOpen = true },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        painter = rememberVectorPainter(DroshIcons.EllipsisVertical),
                                        contentDescription = stringResource(R.string.sidebar_menu),
                                        tint = DroshTextSecondary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }

                            DroshDropdownMenu(
                                expanded = pairMenuOpen,
                                onDismissRequest = { pairMenuOpen = false },
                                offset = DpOffset(0.dp, 4.dp),
                                items = buildList {
                                    if (onSwapPanes != null) {
                                        add(
                                            DroshMenuItem(
                                                label = stringResource(R.string.sidebar_swap_split),
                                                id = "swap",
                                                icon = DroshIcons.ArrowUpDown,
                                            ),
                                        )
                                    }
                                    if (onDock != null && isFloatingPane) {
                                        add(
                                            DroshMenuItem(
                                                label = stringResource(R.string.action_dock_pane),
                                                id = "dock",
                                                icon = DroshIcons.PanelBottom,
                                            ),
                                        )
                                    }
                                    if (onCloseSplit != null) {
                                        add(
                                            DroshMenuItem(
                                                label = stringResource(R.string.sidebar_close_split),
                                                id = "close",
                                                icon = DroshIcons.X,
                                            ),
                                        )
                                    }
                                },
                                onItemClick = { item ->
                                    pairMenuOpen = false
                                    when (item.id) {
                                        "swap" -> onSwapPanes?.invoke()
                                        "dock" -> onDock?.invoke()
                                        "close" -> onCloseSplit?.invoke()
                                    }
                                },
                            )
                        }
                        // Two, not one: only the anchor row was being replaced, so
                        // the partner fell through to the branch below and was
                        // drawn a second time as an ordinary row — the same session
                        // listed once inside the card and once under it, with its
                        // own menu, and both copies' recency mark off by one.
                        rankOffset += 2
                    } else if (snapshot.id != pairPartner) {
                        // The partner is already drawn inside the card above, so it
                        // gets no row of its own.
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
                                sessionSurface = sessionSurface,
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
                                dragState = dragState,
                                onOpenMenu = { renamingSession = snapshot },
                                // The session already in a pane cannot be the
                                // held one: dropping it somewhere would move it,
                                // not start a split.
                                isSecondaryPane = snapshot.id in pairOfSessions,
                                floatingPane = isFloatingPane,
                                onDock = onDock,
                                onDropOnTerminal = onDropOnTerminal?.let { drop ->
                                    { drop(snapshot.id) }
                                },
                                drawerWidthPx = pushState?.widthPx ?: 0f,
                                onHoldChange = onHoldChange,
                                onDrop = { target ->
                                    if (target != snapshot.id) {
                                        onDragSplit?.invoke(snapshot.id, target)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (ended.isNotEmpty()) {
                                    item(key = "ended_header") {
                                    SectionLabel(
                            stringResource(R.string.sidebar_ended),
                            null,
                            stringResource(R.string.sidebar_clear_count, ended.size),
                        ) { viewModel.purgeEnded() }
                                    }
                                    items(ended.size, key = { "ended_${ended[it].id}" }) { index ->
                                    val snapshot = ended[index]
                                SessionRow(
                                    snapshot = snapshot,
                                    isActive = false,
                                    recencyRank = 0,
                                    sessionSurface = sessionSurface,
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
                                    onToggleKeyboard = onToggleKeyboard,
                                    )
                                    }
                                    }
                                    
                                    @Composable
                                    private fun SidebarHeader(
                                    identity: DeviceIdentity?,
                                    onNewSession: () -> Unit,
                                    ) {
                                    val name = identity?.marketingName.orEmpty().ifBlank { stringResource(R.string.sidebar_this_device) }
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
                                    contentDescription = stringResource(R.string.sidebar_new_session),
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
    /**
     * The drawer's pick-up-and-drop state, or null when dragging is off.
     *
     * One object for every row: the row being carried and the row being
     * aimed at are different rows, and neither can hold the state the other
     * needs to see.
     */
    dragState: SidebarDragState? = null,
    /**
     * Whether the row sits on a surface of its own.
     *
     * Off, rows are lines on the drawer — only the press and the menu light
     * one up. On, each row is a tile, and a row that is picked up becomes the
     * same tile lifted off the list, which is what a drag should look like.
     */
    sessionSurface: Boolean = true,
    /** Opens this row's menu — where the long press used to open it. */
    onOpenMenu: () -> Unit = {},
    /** Called with the session a held row was released over. */
    onDrop: (String) -> Unit = {},
    /**
     * Called when a held row is released outside the drawer entirely, over
     * the terminal beside it.
     *
     * A drop that lands on no row is not necessarily a drop on nothing: the
     * card can be carried out of the drawer, and out there the only thing
     * under it is the terminal. That drop means "split this with what is
     * already on screen", and the row is where the decision is made because
     * the row is what has the finger.
     */
    onDropOnTerminal: (() -> Unit)? = null,
    /**
     * The drawer's right edge in root pixels, or 0 to disable the outside drop.
     *
     * The test is "is the card past the drawer": the drawer starts at the
     * window's left edge, so anything to the right of its width is over the
     * terminal and nothing else.
     */
    drawerWidthPx: Float = 0f,
    /**
     * Called with this row's session when it is picked up, and with null when it
     * is put down.
     *
     * The terminal shows a drop target while a card is in the air, and it is
     * told from here because the row is what has the finger. Without it the
     * target would have to be a guess at whether the user is holding something,
     * which is the one thing the drawer already knows.
     */
    onHoldChange: ((String?) -> Unit)? = null,
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

    // The row's rectangle is dropped when it leaves the list: a stale one
    // under the held row's centre would aim the drop at a row that is not
    // there any more.
    DisposableEffect(snapshot.id) {
        onDispose { dragState?.onRowDisposed(snapshot.id) }
    }

    /** True while this row is the one being carried. */
    val dragging = dragEnabled && dragState?.isHeld(snapshot.id) == true
    /** True while this row is the one being aimed at. */
    val isTarget = dragEnabled && dragState?.isTarget(snapshot.id) == true

    val ended = snapshot.state == SessionState.Closed

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // Needed inside `pointerInput`, whose block is not a composable scope: the
    // touch slop has to be in pixels, and the slop is a dp constant.
    val density = LocalDensity.current
    // the row is still the thing the menu belongs to. Dismissing the menu —
    // including by tapping empty space — puts it back.
    val surface by animateFloatAsState(
        targetValue = if (pressed || menuOpen) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "sessionPress",
    )

    // The row's surface in one number: a row being carried is fully opaque —
    // it is a card now, off the list — a row on a surface never goes fully
    // clear, and a row on lines is clear until it is touched.
    val rowSurfaceAlpha = when {
        dragging -> 1f
        sessionSurface -> 0.55f + 0.45f * surface
        else -> surface
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The active row tightens its left edge to make room for the
                // mark, so the name column stays in one place for every row.
                .padding(start = 22.dp, end = 12.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                // A tile or a line. Off, nothing shows until the row is
                // pressed or its menu is up — the drawer's old look. On, the
                // row is a surface of its own, and the press only deepens it.
                .background(DroshSurfaceVariant.copy(alpha = rowSurfaceAlpha))
                // The drop target lights with the accent, not just a raised
                // surface. A held row and a target row are otherwise identical
                // apart from the recency mark, and the user has to see at a
                // glance which one their finger should land on.
                .then(
                    if (isTarget) {
                        Modifier
                            .background(
                                DroshPrimary.copy(alpha = 0.12f),
                                RoundedCornerShape(10.dp),
                            )
                            .border(
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
                // The row records where it is, so the drag can ask which row
                // is under the finger.
                .onGloballyPositioned { coords ->
                    if (dragState != null) {
                        dragState.onRowPositioned(snapshot.id, coords.boundsInRoot())
                    }
                }
                .then(
                    if (dragging) {
                        // The row's slot stays — taking it out would collapse the
                        // list while the finger is still down — but the row itself
                        // is drawn by the drag card in a window of its own, outside
                        // the drawer, where nothing clips it. An invisible row
                        // still takes the gestures, which is what lets one row
                        // both carry and receive: the press that lifts a card is
                        // still the press that lands it.
                        Modifier.alpha(0f)
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
                 * One gesture recogniser, because two would race.
                 *
                 * The row needs three outcomes from one press — open it,
                 * pick it up, or get out of the way of a scroll — and
                 * `combinedClickable` cannot express all three once the
                 * pick-up follows the finger. So:
                 *
                 *  - released short            → tap: open
                 *  - held past the long press → pick up, and the row
                 *    follows the finger from there
                 *  - moved before either      → a scroll: the gesture is
                 *    returned to the list
                 *
                 * The menu left the long press when the pick-up took it:
                 * one gesture, one long press, and the menu now lives on the
                 * grip, which is where a menu icon belongs anyway.
                 */
                .pointerInput(snapshot.id, dragEnabled, dragState != null) {
                    if (!dragEnabled || dragState == null) {
                        // No dragging here: a plain tap-opens recogniser.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val up = waitForUpOrCancellation()
                            if (up != null && !menuOpen) onClick()
                        }
                        return@pointerInput
                    }
                    val touchSlop = with(density) { TOUCH_SLOP.toPx() }
                    val longPressMs = viewConfiguration.longPressTimeoutMillis

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startedAt = System.currentTimeMillis()
                        var dragging = false
                        var moved = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break

                            if (change.changedToUpIgnoreConsumed()) {
                                change.consume()
                                when {
                                    dragging -> {
                                        // `end` clears the held id and the
                                        // offset, so where the finger was is
                                        // read before it is answered — the
                                        // callback is told which session to
                                        // split and which to split it with.
                                        val dragged = snapshot.id
                                        val centre = dragState.heldCentre()
                                        val target = dragState.end()
                                        when (target) {
                                            null -> {
                                                // Over no row. Past the drawer's
                                                // edge that is over the terminal,
                                                // and a drop there is a split with
                                                // the session already on screen.
                                                if (onDropOnTerminal != null &&
                                                    centre != null &&
                                                    centre.x > drawerWidthPx
                                                ) {
                                                    onDropOnTerminal()
                                                }
                                            }
                                            else -> onDrop(target)
                                        }
                                    }
                                    moved -> Unit
                                    menuOpen -> menuOpen = false
                                    else -> onClick()
                                }
                                break
                            }

                            val movement = change.positionChange()
                            val travelled = hypot(movement.x, movement.y)
                            if (travelled != 0f) {
                                when {
                                    dragging -> {
                                        change.consume()
                                        dragState.drag(movement)
                                    }
                                    travelled > touchSlop -> {
                                        moved = true
                                        break
                                    }
                                }
                            }

                            if (!dragging && System.currentTimeMillis() - startedAt >= longPressMs) {
                                dragging = true
                                moved = true
                                menuOpen = false
                                dragState.begin(snapshot.id)
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
            // The preview: two stacked bars, the top one the current session
            // and the bottom one the one being carried. It says what a drop
            // here *makes*, which a border on its own never did.
            AnimatedVisibility(
                visible = isTarget,
                enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.6f),
                exit = fadeOut(tween(120)),
            ) {
                Row(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(width = 22.dp, height = 26.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, DroshPrimary, RoundedCornerShape(6.dp)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .background(DroshTextMuted),
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(3.dp)
                            .background(DroshPrimary),
                    )
                }
            }

            // The three dots: the row's menu, always reachable. The long
            // press used to open it and the pick-up took that over, so the
            // menu now has a door of its own — a dot is the smallest one
            // there is, and it sits where a menu belongs.
            if (!ended) {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        painter = rememberVectorPainter(DroshIcons.EllipsisVertical),
                        contentDescription = stringResource(R.string.sidebar_menu),
                        tint = DroshTextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (onSplit != null && !ended && !isSecondaryPane) {
                SplitDragHandle(
                    onTap = { menuOpen = true },
                    onPickUp = {
                        dragState?.begin(snapshot.id)
                        onHoldChange?.invoke(snapshot.id)
                    },
                    onDragBy = { delta -> dragState?.drag(delta) },
                    onRelease = {
                        onHoldChange?.invoke(null)
                        // Where the finger was is read before `end` answers:
                        // the drag state resets the offset and the held id, and
                        // "over no row" then has to be told apart from "over the
                        // terminal" while the answer is still there to read.
                        val centre = dragState?.heldCentre()
                        val target = dragState?.end()
                        if (target != null) {
                            onDrop(target)
                        } else if (
                            onDropOnTerminal != null &&
                            centre != null &&
                            centre.x > drawerWidthPx
                        ) {
                            onDropOnTerminal()
                        }
                    },
                    enabled = dragEnabled,
                )
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
                            label = stringResource(if (isSecondaryPane) R.string.sidebar_swap_split else R.string.sidebar_split_below),
                            id = if (isSecondaryPane) "swap" else "split",
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
                            label = stringResource(
                                if (isSecondaryPane && floatingPane) {
                                    R.string.action_dock_pane
                                } else {
                                    R.string.sidebar_open_window
                                },
                            ),
                            id = if (isSecondaryPane && floatingPane) "dock" else "float",
                            icon = if (isSecondaryPane && floatingPane) {
                                DroshIcons.PanelBottom
                            } else {
                                DroshIcons.Square
                            },
                        ),
                    )
                }
                add(DroshMenuItem(label = stringResource(R.string.action_rename), icon = DroshIcons.Pencil, id = "rename"))
                add(
                    DroshMenuItem(
                        label = stringResource(R.string.action_delete),
                        icon = DroshIcons.Trash2,
                        style = DroshMenuItemStyle.Destructive,
                        id = "delete",
                    ),
                )
            },
            onItemClick = { item ->
                when (item.id) {
                    "split" -> onSplit?.invoke()
                    // Both of these are reachable only on a row that is already
                    // in the split, and both used to fall through to nothing.
                    "swap" -> onSwapPanes?.invoke()
                    "dock" -> onDock?.invoke()
                    "float" -> onFloat?.invoke()
                    "rename" -> onStartRename()
                    "delete" -> onDelete()
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
    floating: Boolean,
    topName: String,
    bottomName: String,
    isActiveTop: Boolean,
    isActiveBottom: Boolean,
    onOpenTop: () -> Unit,
    onOpenBottom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(horizontal = 10.dp)
            .height(58.dp)
            .shadow(6.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(DroshSurfaceVariant),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SplitPairHalf(
            name = topName,
            active = isActiveTop,
            onClick = onOpenTop,
            modifier = Modifier.weight(1f),
        )

        // The seam, for a docked pair only — the divider's own hairline, at the
        // same weight, so the card reads as a miniature of the split on screen.
        if (!floating) {
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(30.dp)
                    .background(DroshOutline.copy(alpha = 0.7f)),
            )
        }

        SplitPairHalf(
            name = bottomName,
            active = isActiveBottom,
            onClick = onOpenBottom,
            modifier = if (floating) {
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp, bottom = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DroshSurfaceHigh)
            } else {
                Modifier.weight(1f)
            },
        )
    }
}

/**
 * One session's half of the card: a mark, a name, and a two-line sketch of a
 * terminal so the card reads as panes rather than as two rows of text.
 */
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
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // Two stacked hairlines: a terminal's worth of output, in the smallest
        // space that still says "screen". The active half's are lit.
        Column(
            modifier = Modifier.width(14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            repeat(2) { row ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (row == 0) 1f else 0.62f)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            if (active) DroshPrimary else DroshTextMuted.copy(alpha = 0.5f),
                        ),
                )
            }
        }
        Text(
            text = name,
            color = if (active) DroshText else DroshTextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * How much longer than a plain long press the drag threshold is.
 *
 * The pick-up takes the standard long press now. It used to need twice it,
 * because the menu owned the first threshold and the drag the second — one
 * gesture with two long presses, which nobody found. The menu moved to the
 * grip, and the long press is the pick-up.
 */

/** How far a finger may travel before a press becomes a drawer scroll. */
private val TOUCH_SLOP = 12.dp
private const val HELD_SCALE = 1.04f

/** The lift a held row gets, in pixels. `graphicsLayer` takes a Float. */
private val HELD_ELEVATION_PX = 8f

/**
 * The card a row turns into while it is carried.
 *
 * Its own surface, its own shadow, its own window — so it reads as an object
 * off the list and not as a row that changed colour. The two stacked bars say
 * what releasing it will make, the same way the row it lands on does.
 */
@Composable
private fun SessionDragCard(
    name: String,
    width: Dp,
) {
    Row(
        modifier = Modifier
            .width(width)
            .height(44.dp)
            .shadow(12.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(DroshSurfaceHigh)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier.width(14.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            repeat(2) { row ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (row == 0) 1f else 0.62f)
                        .height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(DroshPrimary),
                )
            }
        }
        Text(
            text = name,
            color = DroshText,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = rememberVectorPainter(DroshIcons.EllipsisVertical),
            contentDescription = null,
            tint = DroshTextMuted,
            modifier = Modifier.size(16.dp),
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
 */
@Composable
private fun SplitDragHandle(
    onTap: () -> Unit,
    onPickUp: () -> Unit,
    onDragBy: (Offset) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var armed by remember { mutableFloatStateOf(0f) }
    var holding by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (holding) 0.86f else 1f,
        animationSpec = tween(90),
        label = "gripScale",
    )

    Box(
        modifier = modifier
            .size(30.dp)
            .scale(scale)
            .clip(CircleShape)
            .pointerInput(enabled) {
                if (!enabled) {
                    // Still a button: the menu is the only thing the row's
                    // own long press no longer opens.
                    detectTapGestures { onTap() }
                    return@pointerInput
                }
                val slopPx = viewConfiguration.touchSlop
                val holdMs = viewConfiguration.longPressTimeoutMillis
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startedAt = System.currentTimeMillis()
                    var picked = false
                    var moved = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUpIgnoreConsumed()) {
                            change.consume()
                            when {
                                picked -> onRelease()
                                moved -> Unit
                                else -> onTap()
                            }
                            break
                        }
                        val movement = change.positionChange()
                        val travelled = hypot(movement.x, movement.y)
                        if (travelled != 0f) {
                            when {
                                picked -> { change.consume(); onDragBy(movement) }
                                travelled > slopPx -> { moved = true; break }
                            }
                        }
                        if (!picked && System.currentTimeMillis() - startedAt >= holdMs) {
                            picked = true
                            holding = true
                            onPickUp()
                        }
                    }
                    holding = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Six dots, three rows of two: the universal "this can be moved".
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(2) {
                        Box(
                            modifier = Modifier
                                .size(3.dp)
                                .clip(CircleShape)
                                .background(DroshTextMuted),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarFooter(
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleKeyboard: (() -> Unit)?,
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

        // The keyboard, beside the settings. The selection menu used to carry
        // it and the drawer is where a keyboard belongs: it is the one control
        // the terminal needs most often and the drawer is the one place that
        // is always reachable while typing.
        if (onToggleKeyboard != null) {
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
                            onToggleKeyboard()
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = DroshIcons.Keyboard,
                    contentDescription = "Keyboard",
                    tint = DroshTextSecondary,
                    modifier = Modifier.size(19.dp),
                )
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
