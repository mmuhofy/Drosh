package dev.drosh.ui.session

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
 * The screen's chrome: header, empty state, floating bar and close question.
 *
 * All of it is stateless apart from the header's own dropdown, so it lives
 * beside the grid instead of inside it. The grid itself decides what a card is
 * and what a gesture means; these only say where the controls are.
 */

/**
 * The header. The whole title — label AND chevron — is one button that opens the
 * two-way filter. The ⋮ at the end holds the only destructive action left up here.
 */
@Composable
internal fun SessionScreenHeader(
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
                // id, never the translated label. The active entry is marked by
                // the chip on the right of the title instead of by a tab.
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        if (tab == SessionTab.Groups) {
                            R.string.session_screen_tab_groups
                        } else {
                            R.string.session_screen_tab_singles
                        },
                    ),
                    color = DroshTextMuted,
                    fontSize = 12.sp,
                )
                Text(
                    text = " · " + stringResource(R.string.session_screen_count, count),
                    color = DroshTextMuted,
                    fontSize = 12.sp,
                )
            }
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
internal fun SessionScreenEmpty(
    tab: SessionTab,
    hasQuery: Boolean,
    hiddenByFilter: Boolean,
    onClearQuery: () -> Unit,
    onShowGroups: () -> Unit,
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
                    hiddenByFilter -> R.string.session_screen_empty_filtered_title
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
                    hiddenByFilter -> R.string.session_screen_empty_filtered_body
                    tab == SessionTab.Groups -> R.string.session_screen_empty_groups_body
                    else -> R.string.session_screen_empty_body
                },
            ),
            color = DroshTextMuted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
        )
        if (hiddenByFilter) {
            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onShowGroups,
                    )
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.session_screen_tab_groups),
                    color = DroshTextMuted,
                    fontSize = 13.sp,
                )
            }
        }
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
private val BOTTOM_BAR_LIFT = 24.dp

/** Grid inset: lift (24) + control height (40) + breathing room (32). */
internal val BOTTOM_BAR_CLEARANCE = 96.dp

/** Moderate pill width — the bar stays centred rather than filling the row. */
private val SEARCH_PILL_WIDTH = 240.dp
