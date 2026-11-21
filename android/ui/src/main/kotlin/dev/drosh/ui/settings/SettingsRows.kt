package dev.drosh.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.drosh.theme.DroshOutline
import dev.drosh.theme.DroshTileSelected
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTile
import dev.drosh.theme.DroshTrack
import dev.drosh.ui.DroshIcons
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.drosh.ui.R

/**
 * Rows the settings screen grew when the settings did.
 *
 * The tile primitives in `SettingsYou.kt` cover switches, sliders and
 * segmented controls. These three cover what a settings file actually holds
 * and a phone can still edit: a value chosen from a list, a line of text,
 * and a list of strings. They are built on [SettingsTile] with `stacked`
 * off, so they sit in a group exactly like the rows beside them.
 */

/**
 * A row that picks one value from a list, through a dropdown.
 *
 * A segmented control is the better control for two or three choices — it
 * shows them all — and a poor one for six: the labels stop fitting and the
 * row stops being readable. Auto-lock timeouts and package profiles live
 * here for that reason.
 */
@Composable
fun <T> SettingsSelectRow(
    cap: TileCap,
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    supporting: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    SettingsTile(
        cap = cap,
        title = title,
        icon = icon,
        supporting = supporting,
        modifier = modifier,
        onClick = { expanded = true },
    ) {
        // The menu hangs off the pill, not off the row: the pill is what
        // the person touched and what the menu is a bigger version of.
        Box {
            SettingsValuePill(
                text = options.firstOrNull { it.first == selected }?.second.orEmpty(),
                open = expanded,
            )
            SettingsDropdown(
                expanded = expanded,
                onDismiss = { expanded = false },
                options = options,
                selected = selected,
                onSelect = { value ->
                    expanded = false
                    onSelect(value)
                },
            )
        }
    }
}

/**
 * The menu a select row opens: a small floating panel, not the platform menu.
 *
 * Positioned by hand. The platform menu anchors to the row's top-left and
 * lands wherever that puts it; this one right-aligns to the pill it grew
 * from, opens below it, and flips above when the screen has no room below.
 * Either way it stays inside the window by [EDGE] on every side.
 */
@Composable
fun <T> SettingsDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    if (!expanded) return
    val density = LocalDensity.current
    val gap = with(density) { 6.dp.roundToPx() }
    val edge = with(density) { 12.dp.roundToPx() }
    val provider = remember(gap, edge) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val maxX = (windowSize.width - popupContentSize.width - edge).coerceAtLeast(edge)
                val x = (anchorBounds.right - popupContentSize.width).coerceIn(edge, maxX)
                val below = anchorBounds.bottom + gap
                val fitsBelow = below + popupContentSize.height <= windowSize.height - edge
                val y = if (fitsBelow) {
                    below
                } else {
                    (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(edge)
                }
                return IntOffset(x, y)
            }
        }
    }
    val shape = RoundedCornerShape(14.dp)
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        val visible = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(tween(120)) + scaleIn(
                initialScale = 0.92f,
                transformOrigin = TransformOrigin(1f, 0f),
                animationSpec = tween(140),
            ),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 168.dp, max = 280.dp)
                    .heightIn(max = 320.dp)
                    .shadow(12.dp, shape)
                    .clip(shape)
                    .background(DroshTile)
                    .border(1.dp, DroshOutline, shape)
                    .verticalScroll(rememberScrollState())
                    .padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                options.forEach { (value, label) ->
                    val isOn = value == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isOn) DroshTileSelected else Color.Transparent)
                            .clickable { onSelect(value) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = label,
                            color = DroshText,
                            fontSize = 15.sp,
                            fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        if (isOn) SettingsCheck()
                    }
                }
            }
        }
    }
}

/**
 * The current value, as a pill with a downward arrow, at the end of a row.
 *
 * It says "this row is a choice" and shows the choice in the same place, so the
 * row needs no second line of text for it. The arrow is the right-pointing
 * chevron turned a quarter-turn; there is one chevron glyph in the icon set and
 * it is not worth a second for this.
 */
@Composable
fun SettingsValuePill(
    text: String,
    modifier: Modifier = Modifier,
    open: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(DroshTrack)
            .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = DroshText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = DroshIcons.ChevronRight,
            contentDescription = null,
            tint = DroshTextMuted,
            modifier = Modifier.size(16.dp).rotate(if (open) -90f else 90f),
        )
    }
}

/**
 * A row that edits one line of text in a dialog.
 *
 * The dialog rather than an inline field: the screen scrolls, and a keyboard
 * that opens under a scrolling list moves the row the user is editing.
 */
@Composable
fun SettingsTextFieldRow(
    cap: TileCap,
    title: String,
    value: String,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    supporting: String? = null,
    placeholder: String = "",
    supportingWhenBlank: String? = null,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    SettingsTile(
        cap = cap,
        title = title,
        modifier = modifier,
        icon = icon,
        supporting = supporting ?: value.ifBlank { supportingWhenBlank ?: placeholder },
        onClick = { showDialog = true },
    ) {
        SettingsChevron()
    }

    if (showDialog) {
        SettingsTextInputDialog(
            title = title,
            initial = value,
            placeholder = placeholder,
            onDismiss = { showDialog = false },
            onConfirm = { text ->
                showDialog = false
                onConfirm(text)
            },
        )
    }
}

/**
 * A row that edits a list of strings in a dialog.
 *
 * One item per line: a comma breaks a package name that contains one, and a
 * newline is what the shell's own tooling already uses for lists.
 */
@Composable
fun SettingsListRow(
    cap: TileCap,
    title: String,
    values: List<String>,
    onConfirm: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    supporting: String? = null,
    placeholder: String = "",
    emptyText: String = stringResource(R.string.settings_list_empty),
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val summary = supporting ?: when {
        values.isEmpty() -> emptyText
        values.size == 1 -> values.first()
        else -> stringResource(R.string.settings_list_count, values.size)
    }
    SettingsTile(
        cap = cap,
        title = title,
        modifier = modifier,
        icon = icon,
        supporting = summary,
        onClick = { showDialog = true },
    ) {
        SettingsChevron()
    }

    if (showDialog) {
        SettingsListInputDialog(
            title = title,
            initial = values,
            placeholder = placeholder,
            onDismiss = { showDialog = false },
            onConfirm = { list ->
                showDialog = false
                onConfirm(list)
            },
        )
    }
}
