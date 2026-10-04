/*
 * Copyright (C) 2026 Drosh contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package dev.drosh.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drosh.domain.file.FileFailure
import dev.drosh.domain.file.FileWriteResult
import dev.drosh.domain.file.GuestFileException
import dev.drosh.domain.file.GuestFileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the editor screen is showing. */
sealed interface EditorUiState {

    /** Reading the file. */
    data object Loading : EditorUiState

    /**
     * The file is open.
     *
     * [text] is handed to the editor widget exactly once, at creation. It is
     * not the source of truth afterwards — the widget's buffer is, and the
     * screen reads it on save. Holding a second copy here and pushing it into
     * the widget on every recomposition is how cursor position gets reset
     * halfway through typing a word.
     */
    data class Open(
        val guestPath: String,
        val fileName: String,
        val text: String,
        /** False when the path did not exist and the first save will create it. */
        val existedOnDisk: Boolean,
    ) : EditorUiState

    /** The file could not be opened, and there is nothing to edit. */
    data class Failed(val message: String) : EditorUiState
}

/** Transient save feedback, shown as a snackbar. */
data class EditorSaveState(
    val inFlight: Boolean = false,
    val message: String? = null,
)

/**
 * Loads and saves one file for the editor screen.
 *
 * Scoped to a single open path rather than a list of open editors: the feature
 * is `editor <path>` opening one document, and a tabbed editor is a different
 * feature with a different lifecycle. [guestPath] is passed in because the
 * screen is created from a navigation argument — there is no "current document"
 * concept to query.
 */
@HiltViewModel
class EditorViewModel @Inject constructor(
    private val files: GuestFileRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<EditorUiState>(EditorUiState.Loading)
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _saveState = MutableStateFlow(EditorSaveState())
    val saveState: StateFlow<EditorSaveState> = _saveState.asStateFlow()

    private var loaded: String? = null

    fun load(guestPath: String) {
        // Guard against the recomposition that follows a configuration change
        // re-reading the file and, worse, overwriting edits in progress.
        if (loaded == guestPath) return
        loaded = guestPath

        viewModelScope.launch {
            files.openForEditing(guestPath).fold(
                onSuccess = { file ->
                    _state.value = EditorUiState.Open(
                        guestPath = file.guestPath,
                        fileName = file.fileName,
                        text = file.text,
                        existedOnDisk = file.exists,
                    )
                },
                onFailure = { error ->
                    _state.value = EditorUiState.Failed(error.toFileFailure().toMessage(guestPath))
                },
            )
        }
    }

    /**
     * Writes [text] to the open file.
     *
     * [onSaved] runs after a successful write so the screen can clear its dirty
     * flag; it is a callback rather than state because the dirty flag belongs to
     * the editor widget, not to this ViewModel.
     */
    fun save(text: String, onSaved: () -> Unit) {
        val path = loaded ?: return
        if (_saveState.value.inFlight) return

        _saveState.value = EditorSaveState(inFlight = true)
        viewModelScope.launch {
            when (val outcome = files.write(path, text)) {
                is FileWriteResult.Success -> {
                    _saveState.value = EditorSaveState(message = "Saved")
                    onSaved()
                }
                is FileWriteResult.Failure -> {
                    _saveState.value = EditorSaveState(message = outcome.reason.toMessage(path))
                }
            }
        }
    }

    /** Called when the editor screen leaves, so the next visit re-reads the file. */
    fun onClosed() {
        loaded = null
    }
}

/**
 * Turns a [FileFailure] into something worth putting in front of a user.
 *
 * Switched over rather than matched on message text, so a wording change here
 * cannot silently break the mapping. The path is only included where it helps
 * identify the file; for the common cases the filename is already on screen.
 *
 * `OutsideRootfs` is the one worth naming explicitly: it means the path was
 * outside the rootfs, not that the file is missing, and the two need different
 * reactions from the user.
 */
private fun FileFailure.toMessage(guestPath: String): String = when (this) {
    is FileFailure.NotFound -> "No such file"
    is FileFailure.IsDirectory -> "That is a directory"
    is FileFailure.OutsideRootfs -> "Only files inside the Ubuntu filesystem can be edited"
    is FileFailure.Io -> "Could not read $guestPath"
    is FileFailure.TooLarge -> "File is too large to edit (${sizeBytes / 1024} KB)"
}

/** Unwraps a failed [Result] back into the [FileFailure] it was built from. */
private fun Throwable.toFileFailure(): FileFailure =
    (this as? GuestFileException)?.reason
        ?: FileFailure.Io("unknown", message ?: "unknown failure")
