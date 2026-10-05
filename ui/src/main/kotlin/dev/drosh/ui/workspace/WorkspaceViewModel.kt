package dev.drosh.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.drosh.domain.session.ObserveActiveSessionUseCase
import dev.drosh.domain.session.SessionRepository
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.domain.session.SessionState
import dev.drosh.domain.workspace.Workspace
import dev.drosh.domain.workspace.WorkspaceBoard
import dev.drosh.domain.workspace.WorkspaceEdit
import dev.drosh.domain.workspace.WorkspaceGrouping
import dev.drosh.domain.workspace.WorkspaceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Projects screen: workspaces, and the sessions filed under each.
 *
 * ## What this screen promises, and what it does not
 *
 * Grouping plus metadata, and nothing else. A workspace row is a name, a
 * directory, a colour and a count. Tapping a session opens that session — the
 * same session the sidebar would open, with the same rules about which sessions
 * can be brought back.
 *
 * It deliberately does **not** offer to "start the workspace". There is no such
 * thing here to start: a project is not a shell, and the moment a workspace began
 * implying a live process, a dead session inside it would look like a failure
 * rather than the normal state of a phone that closed an hour ago.
 */
@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val workspaces: WorkspaceRepository,
    private val sessions: SessionRepository,
    private val activeSession: ObserveActiveSessionUseCase,
) : ViewModel() {

    /**
     * Workspaces and sessions are two independent Room tables, so the board is
     * derived rather than stored.
     *
     * `combine` re-emits whenever either side changes, which is exactly right:
     * renaming a session moves its row between buckets, and creating a workspace
     * gives every session filed under it a header.
     */
    val board: StateFlow<WorkspaceBoard> =
        combine(
            workspaces.observeAll(),
            sessions.observeAll(),
        ) { list, snapshotList -> WorkspaceGrouping.board(list, snapshotList) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WorkspaceBoard())

    val activeSessionId: StateFlow<String?> =
        activeSession.activeId()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _editing = MutableStateFlow<EditorState?>(null)

    /** Non-null while the create/edit sheet is up. */
    val editing: StateFlow<EditorState?> = _editing.asStateFlow()

    private val _assigningSessionId = MutableStateFlow<String?>(null)

    /** The session whose "move to project" sheet is up, if any. */
    val assigningSessionId: StateFlow<String?> = _assigningSessionId.asStateFlow()

    // ---------------------------------------------------------------- editor

    fun startCreate() {
        _editing.value = EditorState(workspaceId = null, draft = WorkspaceEdit())
    }

    fun startEdit(workspace: Workspace) {
        _editing.value = EditorState(
            workspaceId = workspace.id,
            draft = WorkspaceEdit(
                name = workspace.name,
                rootPath = workspace.rootPath,
                description = workspace.description,
                colorSeed = workspace.colorSeed,
            ),
        )
    }

    fun dismissEditor() {
        _editing.value = null
    }

    fun updateDraft(transform: (WorkspaceEdit) -> WorkspaceEdit) {
        _editing.update { state -> state?.copy(draft = transform(state.draft)) }
    }

    /**
     * Persist the draft.
     *
     * The button is disabled on a blank name, so this is the backstop rather than
     * the first line of defence: a nameless row would render as an unidentifiable
     * card with no way to tell two of them apart.
     */
    fun saveEditor() {
        val state = _editing.value ?: return
        if (state.draft.name.isBlank()) return
        viewModelScope.launch {
            if (state.workspaceId == null) {
                workspaces.create(state.draft)
            } else {
                workspaces.update(state.workspaceId, state.draft)
            }
            _editing.value = null
        }
    }

    /** Delete the workspace. Its sessions survive, ungrouped. */
    fun deleteEditorTarget() {
        val id = _editing.value?.workspaceId ?: return
        viewModelScope.launch {
            workspaces.delete(id)
            _editing.value = null
        }
    }

    fun setArchived(workspace: Workspace, archived: Boolean) {
        viewModelScope.launch { workspaces.setArchived(workspace.id, archived) }
    }

    // ------------------------------------------------------------ assignment

    fun startAssigning(sessionId: String) {
        _assigningSessionId.value = sessionId
    }

    fun dismissAssigning() {
        _assigningSessionId.value = null
    }

    fun assign(sessionId: String, workspaceId: String?) {
        viewModelScope.launch {
            sessions.assignToWorkspace(sessionId, workspaceId)
            _assigningSessionId.value = null
        }
    }

    // --------------------------------------------------------------- opening

    /**
     * Open a session from this screen.
     *
     * A session that has ended is *restored* rather than just activated. Plain
     * activation would be a no-op — the terminal switches by id, and there is no
     * process behind a `Closed` row to switch to — so the user would land back on
     * whatever they had, having been told nothing. Restoring resets the row to
     * `Idle`, which is the signal `SessionManagerAdapter.reconcile` already
     * watches for to spawn a PTY.
     *
     * This is the same rule the sidebar follows, for the same reason: an ended
     * session is a record you resume into, not a live shell you return to.
     */
    fun openSession(session: SessionSnapshot, workspaceId: String?) {
        viewModelScope.launch {
            if (session.state == SessionState.Closed) {
                sessions.restoreSession(session, activate = true)
            } else {
                activeSession.setActive(session.id)
            }
            workspaceId?.let { workspaces.touch(it) }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }

    /**
     * Which workspace the sheet is editing, and what it currently holds.
     *
     * [workspaceId] null means "creating". The draft lives in the ViewModel rather
     * than in composable state so that rotating the device, or the sheet being
     * dismissed and reopened by a configuration change, does not lose a
     * half-typed name.
     */
    data class EditorState(
        val workspaceId: String?,
        val draft: WorkspaceEdit,
    ) {
        val isNew: Boolean get() = workspaceId == null
    }
}