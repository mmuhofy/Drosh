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
    private val providers: LlmProviderRepository,
    agentSession: AgentSession,
) : ViewModel() {

    /** Live from the loop, so a run started from any screen lights up here. */
    val runState: StateFlow<AgentRunState> = agentSession.state

    private val _hasKey = MutableStateFlow<Boolean?>(null)
    val hasKey: StateFlow<Boolean?> = _hasKey.asStateFlow()

    private val _directory = MutableStateFlow(DEFAULT_DIRECTORY)
    val directory: StateFlow<String> = _directory.asStateFlow()

    val grouped: StateFlow<GroupedChats> = chats.observeAll()
        .map { list -> list.toGrouped() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), GroupedChats())

    init {
        reconcileStaleStatuses()
        viewModelScope.launch {
            _hasKey.value = providers.credential(DEFAULT_PROVIDER_ID) != null
        }
    }

    fun setDirectory(path: String) {
        val trimmed = path.trim()
        if (trimmed.isNotEmpty()) _directory.value = trimmed
    }

    fun delete(id: String) {
        viewModelScope.launch { chats.delete(id) }
    }

    fun create(directory: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val chat = chats.create(name = DEFAULT_NAME, workingDirectory = directory)
            onCreated(chat.id)
        }
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
    }

    companion object {
        const val DEFAULT_PROVIDER_ID = "openrouter"

        /** The guest home, which is where `ProotRunner` sets HOME. */
        const val DEFAULT_DIRECTORY = "/home"
        private const val DEFAULT_NAME = "New chat"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
