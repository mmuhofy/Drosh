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
import dev.drosh.domain.agent.TokenUsage
import dev.drosh.domain.agent.TranscriptStore
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
    private val transcripts: TranscriptStore,
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

    /** Guards the single catalog collector, which must outlive one [attach]. */
    private var watchingCatalog = false
    private var onChatCreatedCallback: (String) -> Unit = {}

    /**
     * The prompt that started the failed run.
     *
     * Retrying re-sends it. Resuming a failed turn by re-typing the prompt is
     * friction exactly when the user least wants it, and the prompt is not
     * sensitive enough to keep out of memory.
     */
    private var lastPrompt: String = ""

    data class ProviderState(
        val provider: LlmProvider? = null,
        val selectedModelId: String = "",
        val models: List<LlmModel> = emptyList(),
        val hasKey: Boolean = false,
        val loadingModels: Boolean = false,
        /**
         * Effort stored for this model, or null for the model's own default.
         *
         * Carried here so the composer's effort pill can show what is in effect
         * without the chat screen reading preferences, and so [send] can put it
         * on the request.
         */
        val selectedEffort: String? = null,
        /** Non-null while the key is being written or the models fetched. */
        val error: String? = null,
    )

    val isRunning: Boolean get() = runJob?.isActive == true

    /** (approvalId, chatId) pairs the user has not answered yet. */
    private val _pendingApproval = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val pendingApprovals: StateFlow<List<Pair<String, String>>> = _pendingApproval.asStateFlow()

    /**
     * Token usage for the run in progress.
     *
     * Accumulated rather than replaced: a run spans many turns and the provider
     * reports per-request counts, so showing the last turn alone would make a long
     * run look cheaper than a short one.
     */
    private val _usage = MutableStateFlow(TokenUsage())
    val usage: StateFlow<TokenUsage> = _usage.asStateFlow()

    /** A transient provider failure the loop is working through, if any. */
    private val _retrying = MutableStateFlow<RetryNotice?>(null)
    val retrying: StateFlow<RetryNotice?> = _retrying.asStateFlow()

    /** The last run's failure, so the screen can offer a retry rather than a shrug. */
    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    data class RetryNotice(val attempt: Int, val maxAttempts: Int, val reason: String)

    /**
     * Bind to an existing chat.
     *
     * Idempotent: recomposition and configuration changes re-run this, and
     * re-subscribing each time would duplicate the metadata collection.
     */
    fun attach(id: String, onCreated: (String) -> Unit = {}) {
        if (chatId == id) return

        // One collector for the screen's lifetime, so a catalog that lands after
        // the screen is already up still resolves the provider.
        if (!watchingCatalog) {
            watchingCatalog = true
            viewModelScope.launch {
                providers.observeCatalogState().collect { resolveProvider() }
            }
        }

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
            // The transcript first, so the first frame shows the conversation
            // rather than an empty screen that fills in a moment later.
            val stored = transcripts.load(id)
            if (stored.isNotEmpty()) {
                builder.restore(stored)
                _messages.value = builder.snapshot()
            }
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

    /**
     * Delete this chat, stopping anything still running against it.
     *
     * The stop is not optional. A run holds the chat id to append its tool results
     * to, so deleting the record while a run is in flight leaves the loop writing
     * to a row that no longer exists — and the user, who asked for the chat to go
     * away, watches output keep appearing on a screen they already dismissed.
     */
    fun delete(onDeleted: () -> Unit) {
        val id = chatId ?: return
        stop()
        viewModelScope.launch {
            chats.delete(id)
            onDeleted()
        }
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
                error = "Add your ${provider.label} API key before running the agent",
            )
            return
        }
        val model = providerState.selectedModelId
            .ifEmpty { providerState.models.firstOrNull()?.id.orEmpty() }
        if (model.isEmpty()) {
            _providerState.value = providerState.copy(error = "Pick a model first")
            return
        }
        // Read here rather than inside the loop so a change made in Settings
        // mid-run applies to the next prompt instead of the one in flight.
        val effort = providerState.selectedEffort

        lastPrompt = text
        builder.startRun()
        _messages.value = builder.snapshot()
        _providerState.value = providerState.copy(error = null)

        runJob = viewModelScope.launch {
            chats.touch(id)
            _retrying.value = null
            _failure.value = null
            _usage.value = TokenUsage()

            agentSession.send(
                AgentRequest(
                    chatId = id,
                    providerId = provider.id,
                    modelId = model,
                    prompt = text,
                    workingDirectory = _chat.value?.workingDirectory ?: DEFAULT_DIRECTORY,
                    reasoningEffort = effort,
                ),
            ).collect { event ->
                builder.accept(event)
                _messages.value = builder.snapshot()

                when (event) {
                    is AgentEvent.ApprovalRequired -> _pendingApproval.value =
                        _pendingApproval.value + (event.approval.id to event.approval.chatId)

                    is AgentEvent.UsageUpdated -> _usage.value = _usage.value.plus(event.usage)

                    is AgentEvent.Retrying -> _retrying.value = RetryNotice(
                        attempt = event.attempt,
                        maxAttempts = event.maxAttempts,
                        reason = event.reason,
                    )

                    is AgentEvent.RunFinished -> {
                        _pendingApproval.value = emptyList()
                        _retrying.value = null
                        _failure.value = (event.outcome as? RunOutcome.Failed)?.message
                        chats.updateStatus(id, event.outcome.toChatStatus())
                        // Written here rather than per event: this is the user's
                        // view of the run, and a turn only becomes a row worth
                        // keeping once it has finished. A run that is killed
                        // mid-flight leaves the model-facing rows, which are enough
                        // to read.
                        transcripts.save(id, builder.snapshot())
                    }

                    else -> Unit
                }
            }
            runJob = null
        }
    }

    /** Re-send the prompt of the run that just failed. */
    fun retry() {
        if (isRunning || lastPrompt.isBlank()) return
        send(lastPrompt)
    }

    fun dismissFailure() {
        _failure.value = null
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
            resolveProvider()
        }
    }

    /**
     * Resolve the selected provider, once the catalog can answer.
     *
     * Runs on every catalog state change rather than once on open. A chat
     * opened before the catalog landed would otherwise report "could not be
     * loaded" for a provider that is simply not there yet — and the catalog
     * starts its fetch in the application scope, so it usually lands after the
     * screen is already up.
     */
    private suspend fun resolveProvider() {
        // The provider the user chose last, not a hardcoded one. With 225 to
        // choose from there is no default that is right, and asking on every
        // chat would make the first prompt of every session a detour through
        // settings.
        val providerId = providers.selectedProvider() ?: DEFAULT_PROVIDER_ID
        val provider = providers.provider(providerId) ?: return
        if (_providerState.value.provider?.id == provider.id) return

        val hasKey = providers.credential(provider.id) != null
        val selected = providers.selectedModel(provider.id).orEmpty()
        _providerState.value = _providerState.value.copy(
            provider = provider,
            hasKey = hasKey,
            selectedModelId = selected,
            error = null,
        )
        // The list, not just the id.
        //
        // This only ever set the selected id, so `models` stayed empty until the
        // key was re-saved from settings — which meant the model picker on a chat
        // opened empty for anyone whose key was already stored, and the pill fell
        // back to its "first model or model seç" placeholder with nothing behind
        // it. The catalogue has to be loaded wherever the id is read.
        if (hasKey) fetchModels()
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
        _providerState.value = _providerState.value.copy(
            selectedModelId = modelId,
            // A different model may offer different effort values, and carrying
            // the old one forward would show a level the new model rejects.
            selectedEffort = null,
        )
        viewModelScope.launch {
            providers.setSelectedModel(provider.id, modelId)
            _providerState.value = _providerState.value.copy(
                selectedEffort = providers.reasoningEffort(provider.id, modelId),
            )
        }
    }

    /** Choose a reasoning-effort level for the model in use. */
    fun selectEffort(effort: String?) {
        val provider = _providerState.value.provider ?: return
        val model = _providerState.value.selectedModelId
        if (model.isEmpty()) return
        _providerState.value = _providerState.value.copy(selectedEffort = effort)
        viewModelScope.launch { providers.setReasoningEffort(provider.id, model, effort) }
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
