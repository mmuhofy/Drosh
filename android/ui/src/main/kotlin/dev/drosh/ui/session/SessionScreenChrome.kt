package dev.drosh.ui.session

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.drosh.theme.DroshBorderSubtle
import dev.drosh.theme.DroshError
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurface
import dev.drosh.theme.DroshSurfaceHigh
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.ui.DroshDropdownMenu
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.DroshMenuItem
import dev.drosh.ui.DroshMenuItemStyle
import dev.drosh.ui.R

/**
 * The screen's chrome: header, empty state, split pieces and the floating bar.
 *
 * All of it is stateless apart from the header's own dropdown, so it lives
 * beside the grid instead of inside it.
 */

/**
 * The header: the view's name as the dropdown door, what the view holds, and
 * one X that leaves the grid.
 *
 * Close-all moved into that dropdown. Three controls on a line that short was
 * two too many, and a destructive action behind a menu is where it belongs.
 */
@Composable
internal fun SessionScreenHeader(
    count: Int,
    groupCount: Int,
    tab: SessionTab,
    onSelectTab: (SessionTab) -> Unit,
    onCloseAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var filterExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .height(46.dp)
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
                        text = stringResource(
                            if (tab == SessionTab.Groups) {
                                R.string.session_screen_tab_groups
                            } else {
                                R.string.session_screen_title
                            },
                        ),
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
                DroshDropdownMenu(
                    expanded = filterExpanded,
                    onDismissRequest = { filterExpanded = false },
                    items = listOf(
                        DroshMenuItem(
                            id = "view_sessions",
                            label = stringResource(R.string.session_screen_tab_singles),
                            icon = DroshIcons.Square,
                        ),
                        DroshMenuItem(
                            id = "view_groups",
                            label = stringResource(R.string.session_screen_tab_groups),
                            icon = DroshIcons.SessionGrid,
                        ),
                        DroshMenuItem(
                            id = "close_all",
                            label = stringResource(R.string.session_screen_menu_close_all),
                            icon = DroshIcons.Trash2,
                            style = DroshMenuItemStyle.Destructive,
                            dividerBefore = true,
                        ),
                    ),
                    onItemClick = { item ->
                        filterExpanded = false
                        when (item.id) {
                            "view_sessions" -> onSelectTab(SessionTab.Sessions)
                            "view_groups" -> onSelectTab(SessionTab.Groups)
                            "close_all" -> onCloseAll()
                        }
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.session_screen_count, count),
                    color = DroshTextMuted,
                    fontSize = 12.sp,
                )
                if (groupCount > 0) {
                    Text(
                        text = " · " + stringResource(R.string.session_screen_group_count, groupCount),
                        color = DroshTextMuted,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector = DroshIcons.X,
                contentDescription = stringResource(R.string.session_screen_close_screen),
                tint = DroshTextMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The grid with nothing it can show.
 *
 * Two different nothings, and the copy has to tell them apart: no sessions at
 * all (the only real empty state) and no groups yet (the view is a filter, and
 * switching back is the way out).
 */
@Composable
internal fun SessionScreenEmpty(
    tab: SessionTab,
    hasSessions: Boolean,
    hasQuery: Boolean,
    onClearQuery: () -> Unit,
    onNewSession: () -> Unit,
    onShowSessions: () -> Unit,
) {
    val groupsView = tab == SessionTab.Groups
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(DroshSurfaceVariant)
                .border(1.dp, DroshBorderSubtle, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = when {
                    hasQuery -> DroshIcons.Search
                    groupsView -> DroshIcons.SessionGrid
                    else -> DroshIcons.Terminal
                },
                contentDescription = null,
                tint = DroshTextMuted,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(
                when {
                    hasQuery -> R.string.session_screen_empty_search_title
                    groupsView && hasSessions -> R.string.session_screen_empty_groups_title
                    else -> R.string.session_screen_empty_title
                },
            ),
            color = DroshText,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                when {
                    hasQuery -> R.string.session_screen_empty_search_body
                    groupsView && hasSessions -> R.string.session_screen_empty_groups_body
                    else -> R.string.session_screen_empty_body
                },
            ),
            color = DroshTextMuted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        when {
            hasQuery -> PillButton(
                label = stringResource(R.string.session_screen_clear_search),
                filled = false,
                onClick = onClearQuery,
            )
            groupsView && hasSessions -> PillButton(
                label = stringResource(R.string.session_screen_show_sessions),
                filled = false,
                onClick = onShowSessions,
            )
            else -> PillButton(
                label = stringResource(R.string.session_screen_new),
                filled = true,
                onClick = onNewSession,
            )
        }
    }
}

/** A 44dp capsule: the empty state's one call to action. */
@Composable
private fun PillButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (filled) DroshPrimary else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (filled) Color.Transparent else DroshBorderSubtle,
                shape = RoundedCornerShape(percent = 50),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (filled) DroshSurface else DroshText,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * The selection-mode banner.
 *
 * A split is two taps on this screen now: the first names the source, and this
 * strip says what the second tap means. Without it, a tap on a card in this
 * mode looked like a broken open.
 */
@Composable
internal fun SplitModeBanner(
    sourceName: String,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(DroshPrimary.copy(alpha = 0.12f))
            .border(1.dp, DroshPrimary.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(
            imageVector = DroshIcons.PanelLeft,
            contentDescription = null,
            tint = DroshPrimary,
            modifier = Modifier.size(16.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.session_screen_split_mode_title),
                color = DroshText,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.session_screen_split_mode_body, sourceName),
                color = DroshTextMuted,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = DroshIcons.X,
                contentDescription = stringResource(R.string.session_screen_split_cancel),
                tint = DroshTextMuted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * The split's confirmation: the two real frames side by side, the pair they
 * would make, and one button. The preview is the point — a split is a visual
 * arrangement, and a list of names cannot say what it will look like.
 */
@Composable
internal fun SplitPreviewBar(
    sourceName: String,
    sourcePreview: ImageBitmap?,
    partnerName: String,
    partnerPreview: ImageBitmap?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(bottom = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(DroshSurfaceVariant)
                .border(1.dp, DroshBorderSubtle, RoundedCornerShape(20.dp))
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SplitPanePreview(sourceName, sourcePreview, Modifier.weight(1f))
            Icon(
                imageVector = DroshIcons.Plus,
                contentDescription = null,
                tint = DroshTextMuted,
                modifier = Modifier.size(16.dp),
            )
            SplitPanePreview(partnerName, partnerPreview, Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, DroshBorderSubtle, RoundedCornerShape(14.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onCancel,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.session_screen_split_cancel),
                    color = DroshTextMuted,
                    fontSize = 13.5.sp,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1.2f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(DroshPrimary)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onConfirm,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.session_screen_split_confirm),
                    color = DroshSurface,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SplitPanePreview(
    name: String,
    preview: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(DroshSurface),
            contentAlignment = Alignment.Center,
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = stringResource(R.string.session_screen_thumbnail, name),
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopStart,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = DroshIcons.SquareTerminal,
                    contentDescription = null,
                    tint = DroshTextMuted.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            text = name,
            color = DroshText,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

/**
 * A centred, floating control: a moderate-width glass search pill and one glass
 * `+` beside it. Not full width, lifted off the bottom, pushed above the IME.
 */
@Composable
internal fun SessionBottomBar(
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
            horizontalArrangement = Arrangement.spacedBy(10.dp),
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

/** The close question: the one place a destructive close is confirmed. */
@Composable
internal fun CloseSessionDialog(
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
                DialogButton(
                    stringResource(R.string.session_screen_close_cancel),
                    DroshTextMuted,
                    onDismiss,
                )
                DialogButton(
                    stringResource(R.string.session_screen_close_confirm),
                    DroshError,
                    onConfirm,
                )
            }
        }
    }
}

/** A dialog action, sized to the 48dp floor and coloured by its caller. */
@Composable
private fun DialogButton(
    label: String,
    tint: Color,
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
private val BOTTOM_BAR_LIFT = 22.dp

/** Grid inset: lift (22) + control height (48) + breathing room (38). */
internal val BOTTOM_BAR_CLEARANCE = 108.dp

/** Moderate pill width — the bar stays centred rather than filling the row. */
private val SEARCH_PILL_WIDTH = 240.dp
