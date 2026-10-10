package dev.drosh.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshBorderSubtle
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.ui.R

/**
 * The two dialogs the new rows open, in the language of the settings file
 * they edit: one line, or one item per line.
 *
 * Localised, unlike the two dialogs that came before them — the settings
 * screen picks its language from a row on the same screen, and hard-coded
 * English inside it was the one place that choice did not reach.
 */
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
        containerColor = DroshSurfaceVariant,
        shape = RoundedCornerShape(20.dp),
        title = { Text(title, color = DroshText, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = singleLine,
                placeholder = {
                    if (placeholder.isNotEmpty()) {
                        Text(placeholder, color = DroshTextMuted)
                    }
                },
                textStyle = TextStyle(color = DroshText, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DroshPrimary,
                    unfocusedBorderColor = DroshBorderSubtle,
                    cursorColor = DroshPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }) {
                Text(stringResource(R.string.settings_dialog_save), color = DroshPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_dialog_cancel), color = DroshTextSecondary)
            }
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
        containerColor = DroshSurfaceVariant,
        shape = RoundedCornerShape(20.dp),
        title = { Text(title, color = DroshText, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = false,
                minLines = 4,
                maxLines = 8,
                placeholder = {
                    if (placeholder.isNotEmpty()) {
                        Text(placeholder, color = DroshTextMuted)
                    }
                },
                textStyle = TextStyle(color = DroshText, fontSize = 14.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DroshPrimary,
                    unfocusedBorderColor = DroshBorderSubtle,
                    cursorColor = DroshPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    text.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toList(),
                )
            }) {
                Text(stringResource(R.string.settings_dialog_save), color = DroshPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_dialog_cancel), color = DroshTextSecondary)
            }
        },
    )
}
