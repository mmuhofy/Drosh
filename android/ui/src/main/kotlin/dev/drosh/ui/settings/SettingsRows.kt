package dev.drosh.ui.settings

import androidx.compose.foundation.layout.Box
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
    Box(modifier) {
        SettingsTile(
            cap = cap,
            title = title,
            icon = icon,
            supporting = supporting ?: options.firstOrNull { it.first == selected }?.second,
            onClick = { expanded = true },
        ) {
            SettingsChevron()
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = if (value == selected) {
                        { SettingsCheck() }
                    } else {
                        null
                    },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
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
