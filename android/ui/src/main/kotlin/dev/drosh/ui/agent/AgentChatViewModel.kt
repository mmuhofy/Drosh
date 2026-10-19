package dev.drosh.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.net.Uri
import dev.drosh.domain.agent.AgentChat
import dev.drosh.domain.agent.AgentChatRepository
import dev.drosh.domain.agent.AgentEvent
import dev.drosh.domain.agent.AgentRequest
import dev.drosh.domain.agent.AgentRunState
import dev.drosh.domain.agent.AgentSession
import dev.drosh.domain.agent.AgentWarning
import dev.drosh.domain.agent.ApprovalDecision
import dev.drosh.domain.agent.AttachmentLimits
import dev.drosh.domain.agent.CatalogState
import dev.drosh.domain.agent.ChatMessage
import dev.drosh.domain.agent.ChatNaming
import dev.drosh.domain.agent.ChatStatus
import dev.drosh.domain.agent.ChatTitle
import dev.drosh.domain.agent.ChatTitleRequest
import dev.drosh.domain.agent.ChatTitleSuggester
import dev.drosh.domain.agent.LlmModel
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.LlmProviderRepository
import dev.drosh.domain.agent.MessageAttachment
import dev.drosh.domain.agent.ModelInputCapabilities
import dev.drosh.domain.agent.AgentSessionScope
import dev.drosh.domain.agent.TokenUsage
import dev.drosh.domain.agent.Tool
import dev.drosh.domain.agent.ToolPermission
import dev.drosh.domain.agent.ToolPermissions
import dev.drosh.domain.agent.TranscriptStore
import dev.drosh.domain.agent.RunOutcome
import dev.drosh.domain.agent.TranscriptBuilder
import dev.drosh.domain.agent.attachmentWarning
import dev.drosh.domain.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    /**
     * Also read for the language the user picked.
     *
     * A refusal is written into a transcript row from here rather than from a
     * composable, and the application context answers in the system language —
     * so a user who chose Turkish on an English phone would read their refusal
     * in English. The store is the only thing in `:ui` that knows which
     * language that is.
     */
    private val settingsStore: SettingsStore,
    private val chats: AgentChatRepository,
    private val providers: LlmProviderRepository,
    private val transcripts: TranscriptStore,
    private val titles: ChatTitleSuggester,
    private val pendingPrompts: AgentPendingPrompt,
    /**
     * Only for reading a picked document and for the one string a refusal
     * needs.
     *
     * Injected rather than taken from `LocalContext` because both happen with
     * no composition in scope: the picker hands a `Uri` to a ViewModel method,
     * and a refusal is written into a transcript row rather than a composable.
     */
    @ApplicationContext private val appContext: Context,
    /**
     * Every tool a run may call, from the same multibinding set the registry
     * reads.
     *
     * Injected rather than listed: the permission surface is derived from these
     * values, so the registry, the loop and the sheet that switches tools off all
     * see the same list and a tool added next month switches itself in. It also
     * keeps `:ui` out of `:agent` — the set is bound in the app-wide component,
     * and the interface it is declared with lives in `:domain`.
     */
    private val tools: Set<@JvmSuppressWildcards Tool>,
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

    /**
     * Whether a run is in flight, as observable state.
     *
     * A plain `get()` here meant Compose read it once while composing and never
     * again: the run ends, no state changed, so nothing recomposed and the stop
     * button stayed on screen under a finished transcript. Kept as a
     * [StateFlow] so the screen observes it like every other piece of state.
     */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** True while a run is in flight. */
    val isRunning: Boolean get() = _running.value

    private var runJob: Job? = null
    private var chatId: String? = null

    /** Guards the single catalog collector, which must outlive one [attach]. */
    private var watchingCatalog = false

    /** The chat-metadata collector, cancelled when switching chats. */
    private var observeJob: Job? = null
    private var onChatCreatedCallback: (String) -> Unit = {}

    /**
     * The prompt that started the failed run.
     *
     * Retrying re-sends it. Resuming a failed turn by re-typing the prompt is
     * friction exactly when the user least wants it, and the prompt is not
     * sensitive enough to keep out of memory.
     */
    private var lastPrompt: String = ""

    /**
     * The media that went with [lastPrompt].
     *
     * Kept beside it because a retry has to re-send the turn, not half of it:
     * re-sending the words of a question about a screenshot produces an answer
     * to a question that was never asked.
     */
    private var lastAttachments: List<MessageAttachment> = emptyList()

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

    // ── session scope ─────────────────────────────────────────────────────

    /**
     * The tools, sorted once.
     *
     * Hilt hands over a `Set` whose iteration order depends on its binding graph
     * and changes between launches, and the permission surface is read as a
     * list — so the order is fixed here rather than at every derive. The schema
     * sorts again, which is the point: it does not have to trust this.
     */
    private val toolList: List<Tool> = tools.sortedBy { it.name }

    /**
     * What this session may reach, and from where.
     *
     * Two things travel in one value because they answer one question from the
     * user's side, and because the sheet that edits one is the sheet that edits
     * the other. Everything starts granted — see [AgentSessionScope] on why a
     * default-off switch would make a fresh session useless.
     */
    private val _sessionScope = MutableStateFlow(AgentSessionScope.DEFAULT)
    val sessionScope: StateFlow<AgentSessionScope> = _sessionScope.asStateFlow()

    /** The directory the next run works in. */
    val workingDirectory: StateFlow<String> = _sessionScope
        .map { it.workingDirectory }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            _sessionScope.value.workingDirectory,
        )

    /**
     * One switch per registered tool, name-ordered.
     *
     * Derived from the tools that exist rather than from a list of names here,
     * so a tool added later appears in the surface with no edit to this file.
     * The revocation list is pruned against the same set, so a name taken out of
     * the registry does not leave a hole in the scope forever.
     */
    val toolPermissions: StateFlow<List<ToolPermission>> = _sessionScope
        .map { scope ->
            ToolPermissions.schema(toolList, ToolPermissions.prune(scope, toolList))
        }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ToolPermissions.schema(toolList, _sessionScope.value),
        )

    /**
     * Whether the working directory has been taken off the chat record yet.
     *
     * A move made in the sheet is this session's, and the record is only read to
     * answer "where does this chat live" the first time. Without the flag a
     * chat collector that emits again after a move would drag the session back
     * to the directory the user just left.
     */
    private var directorySeeded = false

    /**
     * Move the session to another working directory.
     *
     * Absolute paths only. A relative one is resolved by the guest against a cwd
     * the app cannot see, and a tool pointed at the wrong directory writes in the
     * wrong place — so an unusable path is dropped rather than half-accepted.
     * Blank means the guest home, which is where a session starts.
     */
    fun setWorkingDirectory(path: String) {
        val trimmed = path.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("/")) return
        directorySeeded = true
        _sessionScope.value = _sessionScope.value.moveTo(
            trimmed.ifEmpty { AgentSessionScope.DEFAULT_WORKING_DIRECTORY },
        )
    }

    /**
     * Turn one tool on or off for this session.
     *
     * A switch in a sheet, one key at a time: the surface says which key governs
     * which tool, and the approval layer is what still stands between a writing
     * tool and the filesystem whether it is granted here or not.
     */
    fun setToolPermission(key: String, granted: Boolean) {
        _sessionScope.value = if (granted) {
            _sessionScope.value.grant(key)
        } else {
            _sessionScope.value.revoke(key)
        }
    }

    // ── media ─────────────────────────────────────────────────────────────

    /**
     * Media waiting on the composer, oldest first.
     *
     * Held here rather than in the draft text on purpose: a photo is a few
     * hundred kilobytes of base64, and a `BasicTextField` whose value carries
     * that re-lays out and recomposes on every keystroke. So the draft stays the
     * words and the files are values beside it.
     */
    private val _attachments = MutableStateFlow<List<MessageAttachment>>(emptyList())
    val attachments: StateFlow<List<MessageAttachment>> = _attachments.asStateFlow()

    /** True while a picked document is being read and shrunk. */
    private val _attaching = MutableStateFlow(false)
    val attaching: StateFlow<Boolean> = _attaching.asStateFlow()

    /**
     * Why the last attachment or send was refused, if one was.
     *
     * A value rather than a sentence: the composer needs to know *which*
     * refusal this is to render it, and the sentence itself is built here from
     * a string so a translator sees it.
     */
    private val _mediaWarning = MutableStateFlow<AgentWarning?>(null)
    val mediaWarning: StateFlow<AgentWarning?> = _mediaWarning.asStateFlow()

    /**
     * The draft text of a send that was refused.
     *
     * Non-null only when the prompt was not sent. The composer owns its text,
     * so this is how the text survives a refusal: the screen restores it rather
     * than the ViewModel holding a second copy of a draft nobody is editing.
     */
    private val _blockedDraft = MutableStateFlow<String?>(null)
    val blockedDraft: StateFlow<String?> = _blockedDraft.asStateFlow()

    /** What the selected model takes, as far as the catalog says. */
    fun capabilities(): ModelInputCapabilities {
        val state = _providerState.value
        val providerId = state.provider?.id ?: return ModelInputCapabilities.UNKNOWN
        val modelId = state.selectedModelId.ifEmpty { state.models.firstOrNull()?.id.orEmpty() }
        if (modelId.isEmpty()) return ModelInputCapabilities.UNKNOWN
        return ModelInputCapabilities.fromCatalog(catalogState, providerId, modelId)
    }

    /** The latest catalog, kept so [capabilities] can answer without a second collector. */
    private var catalogState: CatalogState = CatalogState.Loading

    /** The name Drosh chose for this chat; null once the user has renamed it. */
    private var autoName: String? = null

    /**
     * The first prompt of a chat created moments ago, waiting for the provider.
     *
     * Taken from [AgentPendingPrompt] in [attach] and sent from
     * [resolveProvider], because the navigation that follows the creation
     * destroys the ViewModel that was asked to send — see that class for the
     * whole story.
     */
    private var queuedFirstPrompt: AgentPendingPrompt.Pending? = null

    /** Ids minted for composer attachments; a counter rather than a UUID. */
    private var attachmentSeq = 0

    /**
     * Attach a picked document to the next prompt.
     *
     * Reads it off the main thread, shrinks it into
     * [dev.drosh.domain.agent.AttachmentLimits] and refuses it — with a reason
     * — when the selected model cannot take it. The refusal happens here, at
     * the gesture, rather than at submit: the user who attached a screenshot to
     * a text-only model is told before they have written the question.
     */
    fun attach(uri: Uri) {
        if (_attaching.value) return
        val current = _attachments.value
        if (current.size >= AttachmentLimits.MAX_ATTACHMENTS_PER_MESSAGE) {
            refuse(AgentWarning.TooManyAttachments(AttachmentLimits.MAX_ATTACHMENTS_PER_MESSAGE))
            return
        }

        viewModelScope.launch {
            _attaching.value = true
            _mediaWarning.value = null
            val id = "att${attachmentSeq++}"
            when (val outcome = AgentAttachmentReader.read(appContext, uri, id)) {
                is AgentAttachmentReader.Outcome.Loaded -> {
                    val next = current + outcome.attachment
                    val refusal = attachmentWarning(
                        attachments = next,
                        capabilities = capabilities(),
                        modelId = _providerState.value.selectedModelId,
                    )
                    if (refusal == null) {
                        _attachments.value = next
                    } else {
                        // Refused after being read, so the file name is known
                        // and the sentence can name it.
                        refuse(refusal)
                    }
                }

                is AgentAttachmentReader.Outcome.Refused -> refuse(outcome.warning)
            }
            _attaching.value = false
        }
    }

    /** Take one attachment off the composer. */
    fun removeAttachment(id: String) {
        _attachments.value = _attachments.value.filterNot { it.id == id }
    }

    /** Take every attachment off the composer. */
    fun clearAttachments() {
        _attachments.value = emptyList()
    }

    /**
     * Put a refusal where the user will see it twice.
     *
     * Once in the transcript, which is where they will look after the fact, and
     * once as [mediaWarning], which is what a composer can render inline. The
     * text is resolved here rather than in the screen because the transcript row
     * is written from this class and there is no composition at that point.
     */
    private fun refuse(warning: AgentWarning, draft: String? = null) {
        _mediaWarning.value = warning
        _blockedDraft.value = draft
        viewModelScope.launch {
            builder.warn(
                warning.describe(
                    context = appContext,
                    languageTag = settingsStore.settings.value.appearance.language,
                ),
            )
            _messages.value = builder.snapshot()
        }
    }

    /** Dismiss the inline composer warning. */
    fun dismissMediaWarning() {
        _mediaWarning.value = null
    }

    /**
     * Bind to an existing chat.
     *
     * Switching chats stops the previous one. A run holds its chat id to append
     * tool results to, so leaving it running would keep writing into a
     * transcript nobody is looking at, and the loop's history would diverge from
     * what the screen shows. The run's own output is already in the transcript
     * and in storage, so stopping loses nothing the user has not seen.
     */
    fun attach(id: String, onCreated: (String) -> Unit = {}) {
        if (chatId == id) return

        if (id != NEW_CHAT_ID) {
            stopRun()
        }

        // A different chat is a different session: neither its directory nor its
        // revocations carry over, and the seed below fills the working directory
        // back in from the record as soon as the collector emits.
        _sessionScope.value = AgentSessionScope.DEFAULT
        directorySeeded = false

        // One collector for the screen's lifetime, so a catalog that lands after
        // the screen is already up still resolves the provider.
        if (!watchingCatalog) {
            watchingCatalog = true
            viewModelScope.launch {
                providers.observeCatalogState().collect { state ->
                    // Kept, not just consumed: what a model accepts is a
                    // catalog question, and the composer asks it on every
                    // attach and every send.
                    catalogState = state
                    resolveProvider()
                }
            }
        }

        if (id == NEW_CHAT_ID) {
            // No chat exists yet. Nothing is written until the first prompt, so a
            // user who opens Agent Home, types nothing and backs out leaves no
            // empty row behind.
            chatId = null
            onChatCreatedCallback = onCreated
            queuedFirstPrompt = null
            loadProvider()
            return
        }

        chatId = id
        // The prompt that created this chat, if this ViewModel is the one that
        // was navigated to right after it was created. Taken here and sent from
        // [resolveProvider] once a provider and a key are known.
        queuedFirstPrompt = pendingPrompts.take(id)
        // The chat already carries the name `createFrom` gave it, and it is
        // Drosh's name — so a title that arrives later is allowed to replace
        // it, which is the whole point of asking. [renameFromModel] still
        // refuses once the user has renamed it themselves.
        if (queuedFirstPrompt != null) {
            autoName = ChatTitle.derivedFrom(queuedFirstPrompt!!.prompt)
        }
        _attachments.value = emptyList()
        _mediaWarning.value = null
        _blockedDraft.value = null
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            // The transcript first, so the first frame shows the conversation
            // rather than an empty screen that fills in a moment later.
            val stored = transcripts.load(id)
            if (stored.isNotEmpty()) {
                builder.restore(stored)
            } else {
                // Nothing stored yet: clear whatever the previous chat left.
                builder.restore(emptyList())
            }
            _messages.value = builder.snapshot()
            chats.observe(id).collect { chat ->
                _chat.value = chat
                // The record is where the directory lives. Seeded once, because a
                // move made in the sheet is the session's own from then on and a
                // second emission must not drag it back.
                if (!directorySeeded && chat != null && chat.workingDirectory.isNotBlank()) {
                    directorySeeded = true
                    _sessionScope.value = _sessionScope.value.moveTo(chat.workingDirectory)
                }
            }
        }
        loadProvider()
    }

    /** The windowed publish, when one is not already waiting. */
    private var transcriptPublishJob: Job? = null

    /**
     * Publishes the transcript once, after a short pause.
     *
     * The pause is what makes it a window: every token that arrives while it is
     * pending is folded into one publish when it fires, so the work scales with
     * how fast anyone can read rather than with how fast tokens arrive.
     */
    private fun scheduleTranscriptPublish() {
        if (transcriptPublishJob?.isActive == true) return
        transcriptPublishJob = viewModelScope.launch {
            delay(TRANSCRIPT_PUBLISH_INTERVAL_MS)
            transcriptPublishJob = null
            _messages.value = builder.snapshot()
        }
    }

    /** Publishes straight away, for the events a person should see at once. */
    private fun publishTranscriptNow() {
        transcriptPublishJob?.cancel()
        transcriptPublishJob = null
        _messages.value = builder.snapshot()
    }

    /**
     * Cancel the run in flight, if any, and clear the running flag.
     *
     * Separate from [stop] only in that it does not touch the loop's own
     * cancellation path — callers here are switching chats or clearing the
     * ViewModel, where the run is being abandoned rather than asked to stop.
     */
    private fun stopRun() {
        val job = runJob
        runJob = null
        _running.value = false
        chatId?.let { agentSession.cancel(it) }
        job?.cancel()
    }

    /**
     * Create a chat from the first prompt and report its id back to the caller.
     *
     * The prompt is handed to [AgentPendingPrompt] rather than run from here:
     * the caller's next move is a navigation that pops this destination, and the
     * ViewModel this coroutine lives in goes with it. The ViewModel on the far
     * side picks the prompt up in [attach].
     *
     * The chat is named after the prompt first, exactly as before, so the Agent
     * Home list is readable during the run that is about to start — and so a
     * naming call that never answers leaves a name rather than nothing.
     */
    fun createFrom(
        firstPrompt: String,
        directory: String,
        onCreated: (String) -> Unit,
        attachments: List<MessageAttachment> = emptyList(),
    ) {
        viewModelScope.launch {
            val derived = ChatTitle.derivedFrom(firstPrompt)
            val created = chats.create(name = derived, workingDirectory = directory)
            chatId = created.id
            autoName = derived
            _chat.value = created
            // The chat exists from here on, so its directory is the session's and
            // the record no longer has anything to say that this scope would not.
            directorySeeded = true
            _sessionScope.value = _sessionScope.value.moveTo(created.workingDirectory)
            pendingPrompts.offer(created.id, firstPrompt, attachments)
            loadProvider()
            onCreated(created.id)
        }
    }

    /**
     * Rename this chat from what the user typed.
     *
     * Clears [autoName]: from here on the name is the user's, and a model
     * answer arriving late must not overwrite it.
     */
    fun rename(name: String) {
        val id = chatId ?: return
        if (name.isBlank()) return
        autoName = null
        viewModelScope.launch { chats.rename(id, name) }
    }

    /**
     * Rename this chat from what the model called it.
     *
     * Applied only over the name Drosh picked itself. A chat the user renamed
     * while the naming call was still in flight keeps the name the user gave
     * it — an answer that arrives late is not an instruction, and a title that
     * overwrites a deliberate rename is worse than no title.
     */
    fun renameFromModel(title: String) {
        val id = chatId ?: return
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        val own = autoName ?: return
        if (_chat.value?.name != own) return
        autoName = trimmed
        viewModelScope.launch { chats.rename(id, trimmed) }
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

    /**
     * Send what the composer holds.
     *
     * Returns whether the run started. `false` means the prompt is still worth
     * sending and [blockedDraft] carries it — which is what a caller needs in
     * order to leave the text in the box rather than clearing it on a refusal
     * the user has to read.
     */
    fun send(prompt: String): Boolean = send(prompt, _attachments.value)

    /**
     * Send a prompt with the media that goes with it.
     *
     * The prompt is sent as typed. Nothing here summarises it, trims its
     * meaning, or rewrites it for the model's benefit — the attachments ride in
     * the same turn, which is the only shape any of the four protocols accepts
     * for a picture and the sentence about it.
     */
    fun send(prompt: String, attachments: List<MessageAttachment>): Boolean {
        val text = prompt.trim()
        // A screenshot with nothing written under it is a question, so media
        // alone is a sendable turn. Only an entirely empty composer is not.
        if ((text.isEmpty() && attachments.isEmpty()) || isRunning) return false

        // Checked before anything is created or written, so a refused turn
        // leaves no chat row, no name and no transcript entry behind.
        val providerState = _providerState.value
        val capabilities = capabilities()
        val modelIdForCheck = providerState.selectedModelId
            .ifEmpty { providerState.models.firstOrNull()?.id.orEmpty() }
        val refusal = attachmentWarning(
            attachments = attachments,
            capabilities = capabilities,
            modelId = modelIdForCheck,
        )
        if (refusal != null) {
            refuse(refusal, draft = text)
            return false
        }

        if (chatId == null) {
            // First prompt of a new chat: persist it now, then run. Named after the
            // prompt so the Agent Home list is readable without opening anything.
            createFrom(text, DEFAULT_DIRECTORY, { createdId ->
                onChatCreatedCallback(createdId)
            }, attachments)
            return true
        }
        val id = chatId ?: return false

        // First prompt of a chat the user opened empty from Home: it is still
        // carrying the placeholder name, so name it after what was asked. Later
        // prompts leave the name alone — the user may have renamed it, and
        // overwriting that with a follow-up question would be worse.
        val currentName = _chat.value?.name.orEmpty()
        if (currentName.isBlank() || currentName == PLACEHOLDER_NAME) {
            val derived = ChatTitle.derivedFrom(text)
            autoName = derived
            viewModelScope.launch { chats.rename(id, derived) }
        }

        val provider = providerState.provider
        if (provider == null) {
            _providerState.value = providerState.copy(error = "No provider configured")
            return false
        }
        if (!providerState.hasKey) {
            _providerState.value = providerState.copy(
                error = "Add your ${provider.label} API key before running the agent",
            )
            return false
        }
        val model = modelIdForCheck
        if (model.isEmpty()) {
            _providerState.value = providerState.copy(error = "Pick a model first")
            return false
        }
        // Read here rather than inside the loop so a change made in Settings
        // mid-run applies to the next prompt instead of the one in flight.
        val effort = providerState.selectedEffort

        // A conversation that has not answered anything yet is a conversation
        // worth naming. Recorded before the run starts, because the first
        // assistant row is what the check is really asking about.
        val firstTurn = _messages.value.none { it is ChatMessage.Assistant }

        lastPrompt = text
        lastAttachments = attachments
        _blockedDraft.value = null
        _mediaWarning.value = null
        builder.startRun()
        _messages.value = builder.snapshot()
        _providerState.value = providerState.copy(error = null)

        _running.value = true

        // The answer and the name go out together, and neither waits for the
        // other. The naming call is launched first because it is the one nobody
        // is waiting on: it suspends on its own request, and only then does the
        // run's flow start collecting — so the title request is in flight before
        // the first answer token can arrive, not after the last one.
        //
        // They are siblings in this scope rather than parent and child: a naming
        // call that fails cannot touch the run, and cancelling the run does not
        // take the name with it. The name itself lands in place later — the chat
        // already carries a usable one, and a title that never arrives leaves it.
        val naming = ChatNaming.request(
            firstMessage = firstTurn,
            prompt = text,
            sideJobModel = settingsStore.settings.value.agentTools.sideJobModel,
            catalog = catalogState,
            mainProviderId = provider.id,
            mainModelId = model,
        )
        if (naming != null) {
            viewModelScope.launch { requestTitle(id, naming) }
        }

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
                    workingDirectory = runDirectory(),
                    reasoningEffort = effort,
                    attachments = attachments,
                ),
            ).collect { event ->
                builder.accept(event)
                // Text arrives a token at a time, far faster than a frame. Each
                // publish rebuilt the message text and re-ran the transcript
                // projection over every row, so a long answer paid for its own
                // length dozens of times per second. One publish per window is
                // all a reader can use; everything structural — a tool call
                // opening, an approval, the run ending — publishes at once, so
                // rows appear with the event that caused them.
                if (event is AgentEvent.TextDelta || event is AgentEvent.ReasoningDelta) {
                    scheduleTranscriptPublish()
                } else {
                    publishTranscriptNow()
                }

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
            _running.value = false
        }

        return true
    }

    /**
     * The directory the next run works in.
     *
     * The session scope's, except before the chat record has been read: the
     * scope starts at the guest home, and a chat that lives somewhere else has
     * to run where it lives — so the record answers until the scope has been
     * seeded from it. Both are absolute guest paths; see
     * [setWorkingDirectory] on why a relative one is not accepted.
     */
    private fun runDirectory(): String {
        if (!directorySeeded) {
            val fromChat = _chat.value?.workingDirectory?.takeIf { it.isNotBlank() }
            if (fromChat != null) return fromChat
        }
        return _sessionScope.value.workingDirectory
    }

    /**
     * Ask the side job what to call this conversation.
     *
     * Fire and forget beside the run that is already streaming: nothing in the
     * chat waits on it, and a title that never arrives leaves the name the chat
     * already has — which is the name derived from the prompt.
     *
     * Null from the suggester means "keep the name you have" — a first message
     * too short to describe, no key, or a model that answered with prose. All
     * three are normal and none of them is an error to show.
     */
    private fun requestTitle(id: String, request: ChatTitleRequest) {
        viewModelScope.launch {
            val suggested = try {
                titles.suggest(request)
            } catch (cancelled: CancellationException) {
                // A cancelled scope is not a failure of any kind: the naming call
                // dies with the ViewModel, which is exactly what it is for.
                throw cancelled
            } catch (_: Throwable) {
                // Never the answer's problem. The suggester's contract is null
                // for every way this can go wrong; this is the belt for the case
                // the contract does not cover, and the cost of wearing it is the
                // derived name the chat already had.
                null
            } ?: return@launch
            // The chat may have been switched while the model was thinking.
            if (chatId != id) return@launch
            renameFromModel(suggested)
        }
    }

    /** Re-send the prompt of the run that just failed. */
    fun retry() {
        if (isRunning || lastPrompt.isBlank()) return
        send(lastPrompt, lastAttachments)
    }

    fun dismissFailure() {
        _failure.value = null
    }

    fun stop() {
        val id = chatId ?: return
        agentSession.cancel(id)
        _pendingApproval.value = emptyList()
        // The loop's cancellation is what stops it, but the flag has to move now
        // or the button stays lit until the flow actually finishes.
        runJob?.cancel()
        runJob = null
        _running.value = false
    }

    fun answer(approvalId: String, decision: ApprovalDecision) {
        // "Always allow" writes the rule before it is delivered: the rule is the
        // whole content of the answer, and a session restart must not lose it.
        if (decision is ApprovalDecision.AlwaysAllow) {
            val current = settingsStore.settings.value.agent
            if (current.alwaysAllow.none { it.pattern == decision.rule.pattern }) {
                // The write is dispatched rather than awaited: this runs on the
                // main thread from a click, and holding the answer until the
                // file lands would freeze the approval card.
                viewModelScope.launch {
                    settingsStore.update { snap ->
                        snap.copy(
                            agent = snap.agent.copy(
                                alwaysAllow = snap.agent.alwaysAllow + decision.rule,
                            ),
                        )
                    }
                }
            }
        }
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
        if (_providerState.value.provider?.id == provider.id) {
            flushQueuedPrompt()
            return
        }

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
        flushQueuedPrompt()
    }

    /**
     * Run the first prompt of a chat that was created one navigation ago.
     *
     * Called from [resolveProvider] rather than from [attach] because a send
     * needs a provider, a key and a model, and none of them are known at the
     * moment a screen binds to a freshly created chat — the catalog fetch that
     * supplies them usually finishes later.
     *
     * Kept queued until a send actually starts, so a chat opened before the
     * user's key was readable waits for the key rather than dropping the
     * prompt.
     */
    private suspend fun flushQueuedPrompt() {
        val queued = queuedFirstPrompt ?: return
        val id = queued.chatId
        if (chatId != id) return

        // The working directory comes off the chat record, and the record is
        // read by a collector that has not necessarily emitted yet on a screen
        // that has just bound. Waiting for it here is cheaper than running the
        // agent in the wrong directory.
        if (_chat.value == null) {
            val loaded = withTimeoutOrNull(CHAT_LOAD_TIMEOUT_MS) {
                chats.observe(id).firstOrNull { it != null }
            }
            if (loaded == null) return
            _chat.value = loaded
        }

        // Seeded here as well as from the collector: this is the one prompt
        // whose chat record was read a moment ago, and the collector that owns
        // the seeding may not have emitted yet when [send] is called straight
        // after. Without it the first run of a new chat would go to the guest
        // home instead of wherever the chat was created.
        if (!directorySeeded && _chat.value != null) {
            val directory = _chat.value?.workingDirectory.orEmpty()
            if (directory.isNotBlank()) {
                directorySeeded = true
                _sessionScope.value = _sessionScope.value.moveTo(directory)
            }
        }

        if (send(queued.prompt, queued.attachments)) queuedFirstPrompt = null
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
        observeJob?.cancel()
        chatId?.let { agentSession.cancel(it) }
        _running.value = false
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

        /**
         * How long the first prompt of a new chat waits for its chat record.
         *
         * Room answers this in microseconds; the bound exists so a database that
         * is genuinely stuck does not hold the prompt forever with no way to
         * retry.
         */
        private const val CHAT_LOAD_TIMEOUT_MS = 2_000L

        /**
         * How long streamed text is allowed to wait for the next token.
         *
         * A window, not a debounce that resets: a steady stream keeps
         * republishing every window, and a stream that pauses publishes when it
         * pauses. Twenty times a second is past the point where anyone can read
         * a growing paragraph, and it is where the per-token work stops being
         * visible.
         */
        private const val TRANSCRIPT_PUBLISH_INTERVAL_MS = 50L

        /**
         * The name a chat carries before its first prompt.
         *
         * Must match `AgentHomeViewModel.DEFAULT_NAME`. Kept as the same literal
         * in both places rather than one referencing the other, so neither
         * ViewModel has to know about the other.
         */
        private const val PLACEHOLDER_NAME = "New chat"
    }
}
