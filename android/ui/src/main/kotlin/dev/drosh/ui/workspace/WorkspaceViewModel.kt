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
import dev.drosh.ui.R
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Projects screen: workspaces, and the sessions filed under each.
 *
 * ## What this screen promises, and what it does not
 *
 * Grouping plus metadata, and nothing else. A workspace row is a name, a note,
 * a colour and a count. Tapping a session opens that session — the same session
 * the sidebar would open, with the same rules about which sessions can be
 * brought back.
 *
 * It deliberately does **not** offer to "start the workspace". There is no such
 * thing here to start: a project is not a shell, and the moment a workspace began
 * implying a live process, a dead session inside it would look like a failure
 * rather than the normal state of a phone that closed an hour ago.
 *
 * ## Why there is no working directory here
 *
 * The directory a session starts in is a choice about *that session*, and it is
 * made where the session is made — the agent sheet. This screen used to carry a
 * folder field on every project, which read as "this is where your shells run",
 * and it never was: `Workspace.rootPath` is a label the domain documents as one,
 * never `cd`-ed into on the user's behalf. A field that promises something the
 * app does not do is worse than no field, so the editor asks for a name, a note
 * and a colour, and nothing else.
 */
@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val workspaces: WorkspaceRepository,
    private val sessions: SessionRepository,
    private val activeSession: ObserveActiveSessionUseCase,
) : ViewModel() {

    /**
     * The screen's whole state, with [ProjectsUiState.loading] honest about the
     * first frame.
     *
     * The flows come from Room and are live, so the only question a loading
     * state answers is "has the first read landed yet" — and that question
     * matters, because an empty list and a list that has not arrived look
     * identical. Rendering the empty state while the query is still in flight
     * is how a screen tells someone they have nothing and then takes it back.
     */
    val uiState: StateFlow<ProjectsUiState> =
        combine(
            workspaces.observeAll(),
            sessions.observeAll(),
            workspaces.observeArchived(),
        ) { list, snapshotList, archived ->
            ProjectsUiState(
                loading = false,
                board = WorkspaceGrouping.board(list, snapshotList),
                archived = archived,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            ProjectsUiState(),
        )

    val activeSessionId: StateFlow<String?> =
        activeSession.activeId()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _editing = MutableStateFlow<EditorState?>(null)

    /** Non-null while the create/edit sheet is up. */
    val editing: StateFlow<EditorState?> = _editing.asStateFlow()

    private val _assigningSessionId = MutableStateFlow<String?>(null)

    /** The session whose "move to project" sheet is up, if any. */
    val assigningSessionId: StateFlow<String?> = _assigningSessionId.asStateFlow()

    /**
     * What went wrong, and what to run again.
     *
     * Every write here is a Room call that can fail — the process can be killed
     * mid-write, the disk can fill — and the old code closed the sheet first and
     * wrote second, so a failure left the screen claiming a change that had not
     * happened. Now the sheet closes only on success, and a failure surfaces as
     * a message with the action that failed still attached to Retry.
     *
     * [ProjectsNotice.attempt] is a counter rather than the failure itself so
     * that the same thing failing twice is two notices, not one: a `LaunchedEffect`
     * keyed on the value would not restart for a second identical failure and the
     * second one would be silent.
     */
    private val _notice = MutableStateFlow<ProjectsNotice?>(null)
    val notice: StateFlow<ProjectsNotice?> = _notice.asStateFlow()

    /** The action the last failed [write] ran, offered again by Retry. */
    private var lastFailed: (() -> Unit)? = null

    // ---------------------------------------------------------------- editor

    fun startCreate() {
        _editing.value = EditorState(workspaceId = null, draft = WorkspaceEdit())
    }

    fun startEdit(workspace: Workspace) {
        _editing.value = EditorState(
            workspaceId = workspace.id,
            draft = WorkspaceEdit(
                name = workspace.name,
                // Carried through untouched rather than reset. The editor no
                // longer asks for a folder — a session starts where its own
                // shell starts, not in a project's directory — but the row
                // still has one, and an update that dropped it would quietly
                // rewrite a field this screen has stopped showing.
                rootPath = workspace.rootPath,
                description = workspace.description,
                colorSeed = workspace.colorSeed,
            ),
            isArchived = workspace.archived,
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
    fun saveEditor() = write(R.string.projects_error_save) {
        val state = _editing.value ?: return@write
        if (state.draft.name.isBlank()) return@write
        if (state.workspaceId == null) {
            workspaces.create(state.draft)
        } else {
            workspaces.update(state.workspaceId, state.draft)
        }
        _editing.value = null
    }

    /** Delete the workspace. Its sessions survive, ungrouped. */
    fun deleteEditorTarget() = write(R.string.projects_error_delete) {
        val id = _editing.value?.workspaceId ?: return@write
        workspaces.delete(id)
        _editing.value = null
    }

    /**
     * Archive, or unarchive, whatever the sheet is editing.
     *
     * Toggles rather than taking a flag, because the sheet already knows which
     * state it is in — passing one in would let the caller disagree with the row
     * it is editing.
     */
    fun toggleArchiveEditorTarget() = write(R.string.projects_error_archive) {
        val state = _editing.value ?: return@write
        val id = state.workspaceId ?: return@write
        workspaces.setArchived(id, !state.isArchived)
        _editing.value = null
    }

    /** Put an archived project back in the list. */
    fun restore(id: String) = write(R.string.projects_error_restore) {
        workspaces.setArchived(id, false)
    }

    fun dismissNotice() {
        _notice.value = null
        lastFailed = null
    }

    /** Run the write that failed, again. No-op when nothing has failed. */
    fun retry() {
        lastFailed?.invoke()
    }

    // ------------------------------------------------------------ assignment

    fun startAssigning(sessionId: String) {
        _assigningSessionId.value = sessionId
    }

    fun dismissAssigning() {
        _assigningSessionId.value = null
    }

    fun assign(sessionId: String, workspaceId: String?) =
        write(R.string.projects_error_assign) {
            sessions.assignToWorkspace(sessionId, workspaceId)
            _assigningSessionId.value = null
        }

    // --------------------------------------------------------------- opening

    /**
     * Create a session filed under this project, and make it active.
     *
     * The action the whole screen was missing. Without it a project is a filing
     * cabinet you can only put things into from somewhere else: open a session in
     * the sidebar, come back, long-press it, pick the project. Three screens to
     * do what one tap should.
     *
     * The name is the same `shell` the sidebar uses for a new session, so a
     * project filled from here and one filled from there look the same.
     *
     * Grouping is written straight onto the new row and nothing else about it
     * changes — state stays `Idle`, and the PTY arrives the same way it does for
     * any other new session, through `SessionManagerAdapter.reconcile` noticing
     * the row. A project still spawns nothing itself.
     */
    fun createSessionIn(workspace: Workspace) = write(R.string.projects_error_new_session) {
        sessions.create(DEFAULT_SESSION_NAME, workspace.id)
        workspaces.touch(workspace.id)
    }

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

    /**
     * Run a write, and say so when it fails.
     *
     * The write is kept, so Retry can run the same one again rather than a
     * second guess at what the user pressed: the state the block reads is read
     * when it runs, so a sheet that has since been dismissed makes the retry a
     * no-op instead of a write about a row nobody asked for.
     *
     * The coroutine's own cancellation is rethrown rather than reported: a
     * failure notice for a write that was cancelled because the ViewModel
     * cleared is a notice about nothing.
     */
    private fun write(messageRes: Int, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lastFailed = { write(messageRes, block) }
                _notice.value = ProjectsNotice(
                    attempt = (_notice.value?.attempt ?: 0L) + 1L,
                    messageRes = messageRes,
                )
            }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Matches the sidebar's name for a new session, so the two look alike. */
        private const val DEFAULT_SESSION_NAME = "shell"
    }

    /**
     * Which workspace the sheet is editing, and what it currently holds.
     *
     * [workspaceId] null means "creating". The draft lives in the ViewModel rather
     * than in composable state so that rotating the device, or the sheet being
     * dismissed and reopened by a configuration change, does not lose a
     * half-typed name.
     *
     * [isArchived] is carried alongside the draft rather than looked up when the
     * sheet renders, so the archive/unarchive button says which of the two it is
     * about to do. A sheet that guessed would be a sheet that lies to the user
     * about the consequence of the button they are pressing.
     */
    data class EditorState(
        val workspaceId: String?,
        val draft: WorkspaceEdit,
        val isArchived: Boolean = false,
    ) {
        val isNew: Boolean get() = workspaceId == null
    }
}

/** Everything the screen draws, plus whether the first read has landed. */
data class ProjectsUiState(
    val loading: Boolean = true,
    val board: WorkspaceBoard = WorkspaceBoard(),
    val archived: List<Workspace> = emptyList(),
)

/**
 * A write that did not happen.
 *
 * [attempt] increments per failure so a repeated failure is a new notice; see
 * the note on the field.
 */
data class ProjectsNotice(
    val attempt: Long,
    val messageRes: Int,
)
