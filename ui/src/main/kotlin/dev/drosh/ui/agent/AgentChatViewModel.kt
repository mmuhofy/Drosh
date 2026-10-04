package dev.drosh.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.drosh.domain.agent.AgentChat
import dev.drosh.domain.agent.AgentChatRepository
import dev.drosh.domain.agent.AgentEvent
import dev.drosh.domain.agent.AgentRequest
import dev.drosh.domain.agent.AgentRunState
import dev.drosh.domain.agent.AgentSession
import dev.drosh.domain.agent.ApprovalDecision
import dev.drosh.domain.agent.ChatMessage
import dev.drosh.domain.agent.ChatStatus
import dev.drosh.domain.agent.LlmModel
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.LlmProviderRepository
import dev.drosh.domain.agent.RunOutcome
import dev.drosh.domain.agent.TranscriptBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One agent chat: its transcript, and the run driving it.
 *
 * ## Why the transcript is not persisted
 *
 * The conversation belongs to a running agent. Restoring it from disk would mean
 * handing the model a history it has no memory of, and the first turn after a
 * restart would be answered against something invisible to it. So the transcript is
 * folded from the run's events and starts empty when the process does — the chat
 * record survives, the conversation does not.
 *
 * ## Why the builder outlives a run
 *
 * A second prompt in the same chat continues the same transcript, so the builder
 * is a field rather than a local. [TranscriptBuilder.startRun] is what closes a
 * message left streaming by the previous run, so a new answer can never be
 * appended to a stale one.
 */
@HiltViewModel
class AgentChatViewModel @Inject constructor(
    private val agentSession: AgentSession,
    private val chats: AgentChatRepository,
    private val providers: LlmProviderRepository,
) : ViewModel() {

    private val builder = TranscriptBuilder()

    private val _chat = MutableStateFlow<AgentChat?>(null)
    val chat: StateFlow<AgentChat?> = _chat.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _runState = MutableStateFlow<AgentRunState>(AgentRunState.Idle)
    val runState: StateFlow<AgentRunState> = _runState.asStateFlow()

    private val _providerState = MutableStateFlow(ProviderState())
    val providerState: StateFlow<ProviderState> = _providerState.asStateFlow()

    private var runJob: Job? = null
    private var chatId: String? = null
    private var onChatCreatedCallback: (String) -> Unit = {}

    data class ProviderState(
        val provider: LlmProvider? = null,
        val selectedModelId: String = "",
        val models: List<LlmModel> = emptyList(),
        val hasKey: Boolean = false,
        val loadingModels: Boolean = false,
        /** Non-null while the key is being written or the models fetched. */
        val error: String? = null,
    )

    val isRunning: Boolean get() = runJob?.isActive == true

    /** (approvalId, chatId) pairs the user has not answered yet. */
    private val _pendingApproval = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val pendingApprovals: StateFlow<List<Pair<String, String>>> = _pendingApproval.asStateFlow()

    /**
     * Bind to an existing chat.
     *
     * Idempotent: recomposition and configuration changes re-run this, and
     * re-subscribing each time would duplicate the metadata collection.
     */
    fun attach(id: String, onCreated: (String) -> Unit = {}) {
        if (chatId == id) return

        if (id == NEW_CHAT_ID) {
            // No chat exists yet. Nothing is written until the first prompt, so a
            // user who opens Agent Home, types nothing and backs out leaves no
            // empty row behind.
            chatId = null
            onChatCreatedCallback = onCreated
            loadProvider()
            return
        }

        chatId = id
        viewModelScope.launch {
            chats.observe(id).collect { _chat.value = it }
        }
        loadProvider()
    }

    /** Create a chat from the first prompt and report its id back to the caller. */
    fun createFrom(firstPrompt: String, directory: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val created = chats.create(
                name = firstPrompt.take(NAME_LIMIT),
                workingDirectory = directory,
            )
            chatId = created.id
            _chat.value = created
            loadProvider()
            onCreated(created.id)
        }
    }

    fun rename(name: String) {
        val id = chatId ?: return
        if (name.isBlank()) return
        viewModelScope.launch { chats.rename(id, name) }
    }

    fun send(prompt: String) {
        val text = prompt.trim()
        if (text.isEmpty() || isRunning) return

        if (chatId == null) {
            // First prompt of a new chat: persist it now, then run. Named after the
            // prompt so the Agent Home list is readable without opening anything.
            createFrom(text, DEFAULT_DIRECTORY) { createdId ->
                onChatCreatedCallback(createdId)
            }
            return
        }
        val id = chatId ?: return

        val providerState = _providerState.value
        val provider = providerState.provider
        if (provider == null) {
            _providerState.value = providerState.copy(error = "No provider configured")
            return
        }
        if (!providerState.hasKey) {
            _providerState.value = providerState.copy(
                error = "Add your OpenRouter API key before running the agent",
            )
            return
        }
        val model = providerState.selectedModelId
            .ifEmpty { providerState.models.firstOrNull()?.id.orEmpty() }
        if (model.isEmpty()) {
            _providerState.value = providerState.copy(error = "Pick a model first")
            return
        }

        builder.startRun()
        _messages.value = builder.snapshot()
        _providerState.value = providerState.copy(error = null)

        runJob = viewModelScope.launch {
            chats.touch(id)
            agentSession.send(
                AgentRequest(
                    chatId = id,
                    providerId = provider.id,
                    modelId = model,
                    prompt = text,
                    workingDirectory = _chat.value?.workingDirectory ?: DEFAULT_DIRECTORY,
                ),
            ).collect { event ->
                builder.accept(event)
                _messages.value = builder.snapshot()

                when (event) {
                    is AgentEvent.ApprovalRequired -> _pendingApproval.value =
                        _pendingApproval.value + (event.approval.id to event.approval.chatId)

                    is AgentEvent.RunFinished -> {
                        _pendingApproval.value = emptyList()
                        chats.updateStatus(id, event.outcome.toChatStatus())
                    }

                    else -> Unit
                }
            }
            runJob = null
        }
    }

    fun stop() {
        val id = chatId ?: return
        agentSession.cancel(id)
        _pendingApproval.value = emptyList()
    }

    fun answer(approvalId: String, decision: ApprovalDecision) {
        if (agentSession.answerApproval(approvalId, decision)) {
            _pendingApproval.value = _pendingApproval.value.filterNot { it.first == approvalId }
        }
    }

    // ── provider ─────────────────────────────────────────────────────────

    fun loadProvider() {
        viewModelScope.launch {
            val provider = providers.provider(DEFAULT_PROVIDER_ID) ?: run {
                _providerState.value = _providerState.value.copy(error = "OpenRouter is not configured")
                return@launch
            }
            val hasKey = providers.credential(provider.id) != null
            val selected = providers.selectedModel(provider.id).orEmpty()
            _providerState.value = _providerState.value.copy(
                provider = provider,
                hasKey = hasKey,
                selectedModelId = selected,
            )
        }
    }

    /**
     * Store the API key, then load the model list.
     *
     * Fetching afterwards is what makes saving feel like it did something: the
     * user sees their key take effect immediately instead of having to reopen the
     * sheet and find out.
     */
    fun saveApiKey(key: String) {
        val provider = _providerState.value.provider ?: return
        viewModelScope.launch {
            providers.setCredential(provider.id, key.trim())
            _providerState.value = _providerState.value.copy(
                hasKey = key.isNotBlank(),
                error = null,
            )
            if (key.isNotBlank()) fetchModels()
        }
    }

    fun fetchModels() {
        val provider = _providerState.value.provider ?: return
        viewModelScope.launch {
            _providerState.value = _providerState.value.copy(loadingModels = true, error = null)
            val models = providers.fetchModels(provider.id)
            _providerState.value = _providerState.value.copy(
                models = models,
                loadingModels = false,
                // A provider with no reachable model list is not a broken screen —
                // the key can still be typed into by hand.
                error = if (models.isEmpty()) "Could not load the model list" else null,
            )
        }
    }

    fun selectModel(modelId: String) {
        val provider = _providerState.value.provider ?: return
        _providerState.value = _providerState.value.copy(selectedModelId = modelId)
        viewModelScope.launch { providers.setSelectedModel(provider.id, modelId) }
    }

    fun dismissError() {
        _providerState.value = _providerState.value.copy(error = null)
    }

    override fun onCleared() {
        super.onCleared()
        // The run is in this ViewModel's scope so it dies with it anyway. Cancelling
        // also declines anything the run is parked on, so a tool awaiting consent is
        // not left holding a deferred nobody will ever answer.
        chatId?.let { agentSession.cancel(it) }
    }

    private fun RunOutcome.toChatStatus(): ChatStatus = when (this) {
        is RunOutcome.Completed -> ChatStatus.Done
        is RunOutcome.Failed -> ChatStatus.Failed
        else -> ChatStatus.Idle
    }

    companion object {
        const val DEFAULT_PROVIDER_ID = "openrouter"
        const val DEFAULT_DIRECTORY = "/home"

        /** Route placeholder for a chat that does not exist yet. */
        const val NEW_CHAT_ID = "new"
        private const val NAME_LIMIT = 48
    }
}
