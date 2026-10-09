package dev.drosh.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.drosh.domain.agent.AgentChat
import dev.drosh.domain.agent.AgentChatRepository
import dev.drosh.domain.agent.AgentRunState
import dev.drosh.domain.agent.AgentSession
import dev.drosh.domain.agent.ChatStatus
import dev.drosh.domain.agent.LlmProviderRepository
import dev.drosh.domain.agent.TranscriptStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Agent Home list.
 *
 * ## Why the list is grouped
 *
 * A flat list of chats forces the user to read every row to answer "is anything
 * of mine still going?". Grouping puts the two rows that need attention — a run in
 * progress and a run waiting on them — at the top, and buries the rest.
 *
 * ## Why a stopped run still shows as such
 *
 * [ChatStatus.Running] is persisted, but a process death leaves rows claiming to
 * be running. [reconcileStaleStatuses] rewrites those to Done on load, because a
 * badge that lies forever stops being information.
 */
@HiltViewModel
class AgentHomeViewModel @Inject constructor(
    private val chats: AgentChatRepository,
    private val transcripts: TranscriptStore,
    private val providers: LlmProviderRepository,
    agentSession: AgentSession,
) : ViewModel() {

    /** Live from the loop, so a run started from any screen lights up here. */
    val runState: StateFlow<AgentRunState> = agentSession.state

    private val _hasKey = MutableStateFlow<Boolean?>(null)
    val hasKey: StateFlow<Boolean?> = _hasKey.asStateFlow()

    private val _directory = MutableStateFlow(DEFAULT_DIRECTORY)
    val directory: StateFlow<String> = _directory.asStateFlow()

    /** Set while a rename or a delete is being confirmed, so the row can react. */
    private val _busyChatId = MutableStateFlow<String?>(null)
    val busyChatId: StateFlow<String?> = _busyChatId.asStateFlow()

    val grouped: StateFlow<GroupedChats> = chats.observeAll()
        .map { list -> list.toGrouped() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GroupedChats())

    init {
        reconcileStaleStatuses()
        viewModelScope.launch {
            // Whichever provider was chosen last, not a fixed one. With 225 to
            // pick from there is no default that is right for everyone, and this
            // drives whether the "start a chat" affordance is offered at all.
            //
            // Re-read whenever the catalog changes: the key itself is stored per
            // provider id, and a provider that has not resolved yet would
            // otherwise read as one with no key, hiding a setup that is already
            // done.
            providers.observeCatalogState().collect {
                val providerId = providers.selectedProvider() ?: DEFAULT_PROVIDER_ID
                _hasKey.value = providers.credential(providerId) != null
            }
        }
    }

    /**
     * The directory new chats start in.
     *
     * Kept across the screen so a user who keeps working in `~/zsh` does not set it
     * again for every task. Each chat records its own directory, so changing this
     * only affects chats created from now on.
     */
    fun setDirectory(path: String) {
        val trimmed = path.trim().removeSuffix("/")
        if (trimmed.isNotEmpty()) _directory.value = trimmed
    }

    fun create(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val chat = chats.create(name = DEFAULT_NAME, workingDirectory = _directory.value)
            onCreated(chat.id)
        }
    }

    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        // A rename to nothing is a no-op rather than a delete: the row would
        // otherwise disappear out from under the dialog that asked for it.
        if (trimmed.isEmpty()) return
        viewModelScope.launch { chats.rename(id, trimmed) }
    }

    /**
     * Delete a chat and its transcript.
     *
     * The transcript goes with it. Leaving rows behind for a chat that can no
     * longer be opened is dead weight, and the next chat that reused the id would
     * inherit them.
     */
    fun delete(id: String) {
        viewModelScope.launch {
            chats.delete(id)
            transcripts.clear(id)
        }
    }

    fun markBusy(id: String?) {
        _busyChatId.value = id
    }

    /**
     * Rewrite rows that claim to be running.
     *
     * A run cannot outlive the process, so a persisted Running or WaitingApproval
     * status is always stale by the time the app is next opened.
     */
    private fun reconcileStaleStatuses() {
        viewModelScope.launch {
            chats.observeAll().collect { list ->
                list.forEach { chat ->
                    if (chat.status == ChatStatus.Running ||
                        chat.status == ChatStatus.WaitingApproval
                    ) {
                        chats.updateStatus(chat.id, ChatStatus.Done)
                    }
                }
            }
        }
    }

    private fun List<AgentChat>.toGrouped(): GroupedChats = GroupedChats(
        running = filter { it.status == ChatStatus.Running },
        waiting = filter { it.status == ChatStatus.WaitingApproval },
        recent = filter {
            it.status != ChatStatus.Running && it.status != ChatStatus.WaitingApproval
        },
    )

    data class GroupedChats(
        val running: List<AgentChat> = emptyList(),
        val waiting: List<AgentChat> = emptyList(),
        val recent: List<AgentChat> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = running.isEmpty() && waiting.isEmpty() && recent.isEmpty()

        /**
         * Every chat, in section order.
         *
         * The screen needs this to resolve a long-pressed id back to a chat once
         * the grouping has already been applied.
         */
        fun all(): List<AgentChat> = running + waiting + recent
    }

    companion object {
        const val DEFAULT_PROVIDER_ID = "openrouter"

        /** The guest home, which is where `ProotRunner` sets HOME. */
        const val DEFAULT_DIRECTORY = "/home"
        private const val DEFAULT_NAME = "New chat"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
