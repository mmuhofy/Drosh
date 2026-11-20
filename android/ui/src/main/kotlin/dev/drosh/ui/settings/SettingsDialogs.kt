package dev.drosh.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshOnPrimary
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.DroshTile
import dev.drosh.theme.DroshTrack
import dev.drosh.ui.R

/**
 * The dialogs the settings rows open, in the same language as the rows: a
 * tile-toned panel with a wide corner, a recessed pill-cornered field, and one
 * filled pill for the action that matters.
 *
 * Localised, unlike the two dialogs that came before them — the settings
 * screen picks its language from a row on the same screen, and hard-coded
 * English inside it was the one place that choice did not reach.
 *
 * The pieces are `internal` so the older dialogs in `SettingsComponents.kt`
 * and the theme store wear the same panel instead of a second copy of it.
 */
internal val SettingsDialogShape = RoundedCornerShape(28.dp)
private val SettingsFieldShape = RoundedCornerShape(16.dp)

/** A text field that sits in the panel rather than outlined on it. */
@Composable
internal fun settingsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = DroshPrimary,
    unfocusedBorderColor = Color.Transparent,
    focusedContainerColor = DroshTrack,
    unfocusedContainerColor = DroshTrack,
    cursorColor = DroshPrimary,
    focusedTextColor = DroshText,
    unfocusedTextColor = DroshText,
)

internal val SettingsFieldCorner get() = SettingsFieldShape

/** The dialog's one primary action: a filled pill. */
@Composable
internal fun SettingsDialogConfirmButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = DroshPrimary,
            contentColor = DroshOnPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** The way out: quiet text, no fill. */
@Composable
internal fun SettingsDialogDismissButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, color = DroshTextSecondary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

@Composable
fun SettingsTextInputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = DroshTile,
        shape = SettingsDialogShape,
        title = { Text(title, color = DroshText, fontWeight = FontWeight.SemiBold, fontSize = 20.sp) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = singleLine,
                shape = SettingsFieldShape,
                placeholder = {
                    if (placeholder.isNotEmpty()) {
                        Text(placeholder, color = DroshTextMuted)
                    }
                },
                textStyle = TextStyle(color = DroshText, fontSize = 15.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = settingsFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            SettingsDialogConfirmButton(stringResource(R.string.settings_dialog_save)) {
                onConfirm(text.trim())
            }
        },
        dismissButton = {
            SettingsDialogDismissButton(stringResource(R.string.settings_dialog_cancel), onDismiss)
        },
    )
}

/**
 * Edits a list of strings, one per line.
 *
 * Blank lines are dropped rather than kept: an empty package name is a typo
 * the shell would choke on, and silently discarding it here costs nothing.
 * The order is the user's, because it is the order the list will be written
 * to the file in.
 */
@Composable
fun SettingsListInputDialog(
    title: String,
    initial: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    var text by rememberSaveable { mutableStateOf(initial.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = DroshTile,
        shape = SettingsDialogShape,
        title = { Text(title, color = DroshText, fontWeight = FontWeight.SemiBold, fontSize = 20.sp) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = false,
                minLines = 4,
                maxLines = 8,
                shape = SettingsFieldShape,
                placeholder = {
                    if (placeholder.isNotEmpty()) {
                        Text(placeholder, color = DroshTextMuted)
                    }
                },
                textStyle = TextStyle(color = DroshText, fontSize = 15.sp),
                colors = settingsFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            SettingsDialogConfirmButton(stringResource(R.string.settings_dialog_save)) {
                onConfirm(
                    text.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toList(),
                )
            }
        },
        dismissButton = {
            SettingsDialogDismissButton(stringResource(R.string.settings_dialog_cancel), onDismiss)
        },
    )
}

/**
 * A question with two answers, one of them destructive.
 *
 * Same surface and the same buttons as [SettingsTextInputDialog] — a dialog
 * that looked like something else would make an irreversible act look like a
 * typo.
 */
@Composable
fun SettingsConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = DroshTile,
        shape = SettingsDialogShape,
        title = { Text(title, color = DroshText, fontWeight = FontWeight.SemiBold, fontSize = 20.sp) },
        text = { Text(body, color = DroshTextSecondary, fontSize = 15.sp) },
        confirmButton = { SettingsDialogConfirmButton(confirmLabel, onConfirm) },
        dismissButton = {
            SettingsDialogDismissButton(stringResource(R.string.settings_dialog_cancel), onDismiss)
        },
    )
}
