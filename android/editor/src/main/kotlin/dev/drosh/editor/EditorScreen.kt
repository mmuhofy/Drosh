package dev.drosh.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.theme.DroshBackground
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurface
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.editor.R

/**
 * The editor screen, opened by `editor <path>`.
 *
 * Deliberately full-screen and singular: one file at a time, no tabs. The whole
 * feature is "type `editor somefile`, the file opens", and a tab strip is one
 * more piece of chrome between the user and the text.
 *
 * [onClose] is a callback rather than a `popBackStack()` call so this composable
 * stays free of the navigation API, like the rest of the screens here.
 */
@Composable
fun EditorScreen(
    guestPath: String,
    onClose: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    val handle = rememberSoraEditorHandle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDiscard by remember { mutableStateOf(false) }

    LaunchedEffect(guestPath) { viewModel.load(guestPath) }

    // Unsaved edits are the one thing this screen can lose, so leaving is gated
    // on them. Two handlers rather than one with a branch: the system back
    // gesture must keep working untouched when there is nothing to lose.
    BackHandler(enabled = handle.dirty) { confirmDiscard = true }
    BackHandler(enabled = !handle.dirty) {
        viewModel.onClosed()
        onClose()
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.editor_discard_title), color = DroshText) },
            text = {
                Text(
                    stringResource(R.string.editor_unsaved_message),
                    color = DroshTextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    viewModel.onClosed()
                    onClose()
                }) { Text(stringResource(R.string.editor_discard_confirm), color = DroshPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.editor_keep_editing), color = DroshTextSecondary)
                }
            },
            containerColor = DroshSurface,
        )
    }

    Scaffold(
        containerColor = DroshBackground,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(DroshBackground),
        ) {
            when (val current = state) {
                is EditorUiState.Loading -> LoadingIndicator()

                is EditorUiState.Failed -> FailedMessage(current.message)

                is EditorUiState.Open -> key(current.guestPath) {
                    // Composed only once the file is loaded, so `initialText`
                    // is the real content on the widget's first composition. The
                    // widget reads it once and keeps its own buffer from then on.
                    SoraCodeEditor(
                        initialText = current.text,
                        modifier = Modifier.fillMaxSize(),
                        fileName = current.fileName,
                        handle = handle,
                    )
                    DocumentHeader(
                        title = current.fileName,
                        dirty = handle.dirty,
                        onSave = {
                            viewModel.save(handle.currentText()) { handle.dirty = false }
                        },
                    )
                }
            }
        }
    }

    // Save feedback is one-shot. Read as a value keyed in the effect rather than
    // collected, so the same "Saved" does not reappear on the next recomposition.
    val saveMessage = saveState.message
    LaunchedEffect(saveMessage) {
        if (saveMessage != null) snackbarHostState.showSnackbar(saveMessage)
    }
}

/**
 * Filename and save affordance, floating over the document.
 *
 * An overlay rather than a Scaffold `topBar` because sora-editor draws its line
 * numbers flush to the top edge; a bar above it would push the first line of
 * code out of alignment with the gutter.
 */
@Composable
private fun DocumentHeader(
    title: String,
    dirty: Boolean,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DroshBackground.copy(alpha = 0.92f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A dirty dot reads at a glance without spending the word "unsaved" on it.
        if (dirty) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(DroshPrimary),
            )
        }
        Text(
            text = title,
            color = DroshText,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (dirty) 10.dp else 0.dp),
        )
        TextButton(onClick = onSave, enabled = dirty) {
            Text(
                text = "Save",
                color = if (dirty) DroshPrimary else DroshTextMuted,
            )
        }
    }
}

@Composable
private fun LoadingIndicator() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = DroshPrimary)
    }
}

@Composable
private fun FailedMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = DroshTextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
