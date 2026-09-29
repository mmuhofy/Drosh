package dev.drosh.ui.session

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import coil3.compose.AsyncImage
import dev.drosh.domain.session.DeviceIdentity
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.domain.session.SessionState
import dev.drosh.ui.DroshIcons

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
    pushState: SidebarPushState? = null,
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
        )
    }
}

@Composable
private fun SidebarContent(
    viewModel: SessionSwitcherViewModel,
    deviceIdentityViewModel: DeviceIdentityViewModel,
    onOpenSettings: () -> Unit,
    onOpenAgent: () -> Unit,
) {
    val sessions by viewModel.allSessions.collectAsStateWithLifecycle()
    val activeId by viewModel.activeId.collectAsStateWithLifecycle()
    val identity by deviceIdentityViewModel.identity.collectAsStateWithLifecycle()

    var searchQuery by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var renamingSession by remember { mutableStateOf<SessionSnapshot?>(null) }

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


    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            // Without this the keyboard covers the footer, taking the search
            // field and the close button with it while you are typing in it.
            .imePadding(),
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
                items(live.size, key = { "live_${live[it].id}" }) { index ->
                    val snapshot = live[index]
                    SessionRow(
                        snapshot = snapshot,
                        isActive = snapshot.id == activeId,
                        onClick = { viewModel.activate(snapshot.id) },
                        onStartRename = { renamingSession = snapshot },
                        onDelete = { viewModel.delete(snapshot.id) },
                    )
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
        DeviceAvatar(
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
 */
@Composable
private fun DeviceAvatar(imageUrl: String?, fallbackLetter: String) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(DroshSurfaceVariant),
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
                color = DroshTextSecondary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
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
    val surface by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "rowPress",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (isActive) 3.dp else 12.dp, end = 12.dp)
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
    onClick: () -> Unit,
    onStartRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val ended = snapshot.state == SessionState.Closed

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
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
                .padding(start = if (isActive) 3.dp else 12.dp, end = 12.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(DroshSurfaceVariant.copy(alpha = surface))
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                )
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Marks the active session. Weight and a status dot were both tried
            // and both read as decoration, and a dot sat exactly where this
            // column wanted to stay quiet.
            if (isActive) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(DroshPrimary),
                )
            }
            // Indent so a name lines up with the navigation labels above it.
            Spacer(Modifier.width(30.dp))
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
        }

        // The same menu the terminal's overflow button uses, so the two cannot
        // drift apart visually. Nudged to the row's content edge because left
        // alone it sits flush against the screen.
        DroshDropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = DpOffset(20.dp, 0.dp),
            items = listOf(
                DroshMenuItem(label = "Rename", icon = DroshIcons.Pencil),
                DroshMenuItem(
                    label = "Delete",
                    icon = DroshIcons.Trash2,
                    style = DroshMenuItemStyle.Destructive,
                ),
            ),
            onItemClick = { item ->
                when (item.label) {
                    "Rename" -> onStartRename()
                    "Delete" -> onDelete()
                }
            },
        )
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
        // The pill grows into the button's space and the button turns into the
        // close control, so the row's width never jumps. A hard swap looked
        // like the footer had been replaced rather than switched.
        val pillWeight by animateFloatAsState(
            targetValue = if (searchOpen) 1f else 0f,
            animationSpec = tween(durationMillis = 220, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            label = "searchWidth",
        )

        if (searchOpen) {
            SearchField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(pillWeight),
            )
            CircleButton(
                onClick = {
                    focusManager.clearFocus()
                    onCloseSearch()
                },
                contentDescription = "Close search",
                icon = DroshIcons.X,
            )
        } else {
            // Collapsed it is a plain target, not a text field: a focused field
            // here summons the keyboard, which resizes the drawer and fights
            // the push animation.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(50))
                    .background(DroshSurfaceVariant)
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onOpenSearch,
                    )
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
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
                        text = if (query.isBlank()) "Search sessions" else query,
                        color = if (query.isBlank()) DroshTextMuted else DroshText,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            CircleButton(
                onClick = onOpenSettings,
                contentDescription = "Settings",
                icon = DroshIcons.Settings,
            )
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
