package dev.drosh.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.ui.DroshDropdownMenu
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.DroshMenuItem
import dev.drosh.ui.DroshMenuItemStyle
import dev.drosh.ui.R

/**
 * The card's ⋮ button and its dropdown, in one box so the menu anchors to the
 * button and not to the whole card (which spans half the grid).
 *
 * `Split with…` opens the existing [SplitWithDialog] partner picker directly —
 * this dropdown dismisses before it calls `onSplitWith`, so no second menu is
 * ever stacked on top of it. Pin and Persistent are toggles, and both show a
 * chip on the card when on. Persistent starts off — it is never switched on
 * implicitly.
 */
@Composable
internal fun SessionCardActionsButton(
    sessionName: String,
    isPinned: Boolean,
    isPersistent: Boolean,
    onSplitWith: () -> Unit,
    onTogglePin: () -> Unit,
    onTogglePersistent: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
    onCloseAll: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = DroshIcons.EllipsisVertical,
                contentDescription = stringResource(R.string.session_screen_overflow),
                tint = DroshTextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }

        DroshDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            title = sessionName,
            offset = DpOffset(0.dp, 0.dp),
            items = listOf(
                DroshMenuItem(
                    id = "split",
                    label = stringResource(R.string.session_screen_menu_split),
                    icon = DroshIcons.PanelLeft,
                ),
                DroshMenuItem(
                    id = "pin",
                    label = stringResource(
                        if (isPinned) R.string.session_screen_menu_unpin
                        else R.string.session_screen_menu_pin,
                    ),
                    icon = DroshIcons.Pin,
                ),
                DroshMenuItem(
                    id = "persistent",
                    label = stringResource(R.string.session_screen_menu_persistent),
                    icon = DroshIcons.Timer,
                ),
                DroshMenuItem(
                    id = "rename",
                    label = stringResource(R.string.session_screen_menu_rename),
                    icon = DroshIcons.Pencil,
                ),
                DroshMenuItem(
                    id = "close",
                    label = stringResource(R.string.session_screen_menu_close),
                    icon = DroshIcons.X,
                    style = DroshMenuItemStyle.Destructive,
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
                when (item.id) {
                    "split" -> onSplitWith()
                    "pin" -> onTogglePin()
                    "persistent" -> onTogglePersistent()
                    "rename" -> onRename()
                    "close" -> onClose()
                    "close_all" -> onCloseAll()
                }
            },
        )
    }
}

/**
 * The header's ⋮ menu. New-session now lives only in the bottom bar beside the
 * search pill, so the one action left up here is close-all — a destructive
 * action kept behind a confirmation rather than a guessable icon.
 */
@Composable
internal fun HeaderMenuButton(
    onCloseAll: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = DroshIcons.EllipsisVertical,
                contentDescription = stringResource(R.string.session_screen_header_menu),
                tint = DroshTextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }

        DroshDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            items = listOf(
                DroshMenuItem(
                    id = "close_all",
                    label = stringResource(R.string.session_screen_menu_close_all),
                    icon = DroshIcons.Trash2,
                    style = DroshMenuItemStyle.Destructive,
                ),
            ),
            onItemClick = { item ->
                if (item.id == "close_all") onCloseAll()
            },
        )
    }
}
