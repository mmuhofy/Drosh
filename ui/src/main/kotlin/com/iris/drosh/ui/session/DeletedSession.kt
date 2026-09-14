package com.iris.irisshell.ui.session

import com.iris.irisshell.domain.session.SessionSnapshot

/**
 * Snapshot of a session that was just deleted, kept briefly so the user
 * can hit "Undo" in the Snackbar.
 */
data class DeletedSession(
    val snapshot: SessionSnapshot,
    val wasActive: Boolean,
    val fallbackName: String?,
)
