package dev.drosh.agent.runtime

import dev.drosh.agent.provider.ProviderErrorClassifier
import dev.drosh.agent.provider.ProviderRegistry
import dev.drosh.agent.tool.ToolRegistry
import dev.drosh.domain.agent.AgentEvent
import dev.drosh.domain.agent.AgentLimits
import dev.drosh.domain.agent.AgentRequest
import dev.drosh.domain.agent.AgentRunState
import dev.drosh.domain.agent.AgentSession
import dev.drosh.domain.agent.ApprovalDecision
import dev.drosh.domain.agent.ChatAdapter
import dev.drosh.domain.agent.LlmCredential
import dev.drosh.domain.agent.LlmMessage
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.LlmProviderRepository
import dev.drosh.domain.agent.LlmRequest
import dev.drosh.domain.agent.LlmStreamEvent
import dev.drosh.domain.agent.LlmToolCall
import dev.drosh.domain.agent.RunOutcome
import dev.drosh.domain.agent.ToolDefinition
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The agent loop.
 *
 * ```text
 * send(prompt) → ┌─ build request from history ──→ stream ──┐
 *                 │                                         │
 *                 │  no tool calls?  → Completed            │
 *                 │  tool calls?     → execute each ────────┤
 *                 │                    fold results into     │
 *                 │                    history ──────────────┘
 *                 └─ repeat until max steps / no tools / cancelled
 * ```
 *
 * ## Chats are independent
 *
 * Each chat gets its own [ChatRuntime] and its own coroutine, so two chats run at
 * once. Nothing is shared but the tool and provider singletons, which are
 * stateless. The only cross-chat state is [state], an aggregate for the top-bar
 * indicator.
 *
 * ## Collecting the returned flow is what runs the loop
 *
 * `send` returns a cold flow: collecting it performs the run, and cancelling the
 * collecting coroutine cancels the provider request and any running tool.
 * [cancel] covers the case where the stop comes from somewhere other than the
 * collector — a stop button, or the app shutting down — by cancelling the job
 * the collector registered.
 *
 * ## Tool rows belong to the executor
 *
 * The loop forwards the provider's stream but does not emit `ToolCallStarted` or
 * `ToolCallCompleted`. The executor is the only place that knows a tool's
 * one-line summary and its terminal state, and emitting from both places would
 * put two rows in the transcript for one call. Tools also run sequentially, in
 * the order the model asked for them.
 */
@Singleton
class AgentLoop @Inject constructor(
    private val providers: LlmProviderRepository,
    private val registry: ProviderRegistry,
    private val toolRegistry: ToolRegistry,
) : AgentSession {

    private val chats = ConcurrentHashMap<String, ChatRuntime>()
    private val pending = PendingRequests()
    private val executor = ToolCallExecutor(toolRegistry, pending)

    private val stateLock = Any()
    private val runningChatIds = LinkedHashSet<String>()
    private val _state = MutableStateFlow<AgentRunState>(AgentRunState.Idle)

    override val state: StateFlow<AgentRunState> = _state.asStateFlow()

    override fun send(request: AgentRequest): Flow<AgentEvent> = flow {
        val runtime = chats.getOrPut(request.chatId) { ChatRuntime(request.chatId) }

        // Fails fast rather than interleaving two conversations over one history,
        // which would leave a transcript neither run can explain.
        if (!runtime.tryClaim()) {
            throw IllegalStateException(
                "Agent chat '${request.chatId}' already has a run in progress",
            )
        }

        runtime.job = currentCoroutineContext().job
        markRunning(request.chatId)

        try {
            runtime.remember(LlmMessage.User(request.prompt))

            emit(AgentEvent.RunFinished(runLoop(request, runtime, this)))
        } finally {
            runtime.release()
            markStopped(request.chatId)
        }
    }

    override fun answerApproval(approvalId: String, decision: ApprovalDecision): Boolean {
        val answered = pending.answer(approvalId, decision)
        // The badge has to come off the moment the last card is answered, and the
        // aggregate is recomputed rather than patched because two chats can be
        // parked on different cards at once.
        refreshState()
        return answered
    }

    override fun cancel(chatId: String) {
        // Decline whatever the run is parked on first, so the suspended tool
        // resumes and unwinds instead of being torn down mid-await.
        pending.declineAll(chatId, "run cancelled")
        chats[chatId]?.job?.cancel()
        refreshState()
    }

    override fun cancelAll() {
        chats.keys.toList().forEach { cancel(it) }
    }

    /** Approval ids still awaiting an answer. Exposed for diagnostics and tests. */
    internal fun pendingApprovalIds(): Set<String> = pending.pendingIds()

    // ── the loop ──────────────────────────────────────────────────────────

    private suspend fun runLoop(
        request: AgentRequest,
        runtime: ChatRuntime,
        emit: FlowCollector<AgentEvent>,
    ): RunOutcome {
        val provider = providers.provider(request.providerId)
            ?: return RunOutcome.Failed("Unknown provider '${request.providerId}'")

        val credential = providers.credential(request.providerId)
            ?: return RunOutcome.Failed(
                "No API key for ${provider.label}. Add one in Settings before running the agent.",
            )

        if (toolRegistry.isEmpty) {
            return RunOutcome.Failed(
                "No tools are registered, so this agent cannot do anything yet.",
            )
        }

        val adapter = registry.adapterFor(provider)
        val declarations = toolRegistry.all().map { tool ->
            ToolDefinition(tool.name, tool.description, tool.parameters)
        }

        val maxSteps = AgentLimits.MAX_STEPS
        var step = 0
        var lastSignature: String? = null
        var repeats = 0

        while (step < maxSteps) {
            step++
            emit(AgentEvent.TurnStarted(step, maxSteps))

            val turn = try {
                collectTurn(provider, adapter, runtime, request, declarations, credential, emit)
            } catch (failure: AgentRunFailure) {
                return RunOutcome.Failed(failure.message ?: "The provider request failed")
            }

            if (turn.toolCalls.isEmpty()) {
                runtime.remember(LlmMessage.Assistant(turn.text))
                return RunOutcome.Completed(turn.text)
            }

            // Recorded before the tools run: the model must be able to see that it
            // asked for them, otherwise the tool results reference calls that were
            // never announced.
            runtime.remember(LlmMessage.Assistant(turn.text, turn.toolCalls))

            for (call in turn.toolCalls) {
                currentCoroutineContext().ensureActive()

                val signature = "${call.name}:${call.arguments}"
                if (signature == lastSignature) {
                    repeats++
                    if (repeats >= AgentLimits.REPEATED_TOOL_CALL_LIMIT) {
                        return RunOutcome.RepetitiveLoop(call.name, repeats)
                    }
                } else {
                    repeats = 0
                    lastSignature = signature
                }

                val responseText = executor.execute(
                    call = call,
                    chatId = request.chatId,
                    workingDirectory = request.workingDirectory,
                    step = step,
                    // A run that parks on the user has to show as waiting, not as
                    // running — a stop button is the wrong affordance for a yes/no
                    // question. Relaying through here means the executor needs no
                    // knowledge of run state.
                    emit = approvalAwareRelay(emit),
                )

                runtime.remember(LlmMessage.ToolResultMessage(call.id, call.name, responseText))
            }

            emit(AgentEvent.TurnCompleted(step))
        }

        return RunOutcome.StepLimitReached(step)
    }

    // ── one provider turn, with retry ──────────────────────────────────────

    /** A turn that cannot be recovered from; the message is shown to the user. */
    private class AgentRunFailure(message: String) : Exception(message)

    private class Turn(
        val text: String,
        val toolCalls: List<LlmToolCall>,
    )

    private suspend fun collectTurn(
        provider: LlmProvider,
        adapter: ChatAdapter,
        runtime: ChatRuntime,
        request: AgentRequest,
        tools: List<ToolDefinition>,
        credential: LlmCredential,
        emit: FlowCollector<AgentEvent>,
    ): Turn {
        var attempt = 1

        while (true) {
            val text = StringBuilder()
            val calls = mutableListOf<LlmToolCall>()
            var finishReasonSeen = false
            var failure: LlmStreamEvent.Failed? = null

            val llmRequest = LlmRequest(
                model = request.modelId,
                messages = runtime.snapshot(),
                tools = tools,
                systemPrompt = SYSTEM_PROMPT,
            )

            adapter.stream(provider, llmRequest, credential).collect { event ->
                when (event) {
                    is LlmStreamEvent.TextDelta -> {
                        text.append(event.text)
                        emit(AgentEvent.TextDelta(event.text))
                    }

                    is LlmStreamEvent.ReasoningDelta -> emit(AgentEvent.ReasoningDelta(event.text))

                    is LlmStreamEvent.ReasoningCompleted ->
                        emit(AgentEvent.ReasoningCompleted(event.text))

                    is LlmStreamEvent.ToolCallCompleted ->
                        calls += LlmToolCall(event.callId, event.name, event.arguments)

                    is LlmStreamEvent.Finished -> {
                        finishReasonSeen = true
                        event.usage?.let { emit(AgentEvent.UsageUpdated(it)) }
                    }

                    is LlmStreamEvent.Failed -> failure = event
                    // The adapter announces a call as soon as it is identifiable so
                    // a long argument stream is not silent. The transcript row is
                    // the executor's to open, so nothing is forwarded here.
                    is LlmStreamEvent.ToolCallStarted -> Unit
                }
            }

            val problem = failure
            if (problem == null) {
                if (!finishReasonSeen) {
                    throw AgentRunFailure("The provider closed the stream without finishing the turn")
                }
                return Turn(text.toString(), calls)
            }

            // A turn that already produced text cannot be retried: the transcript
            // would show the same text twice and the user has already read it. So
            // retry happens only when nothing was emitted for this turn — which is
            // where rate limits and rejected keys actually surface.
            val emittedAnything = text.isNotEmpty() || calls.isNotEmpty()
            val attemptsLeft = attempt < AgentLimits.MAX_PROVIDER_RETRIES

            if (!problem.retryable || !attemptsLeft || emittedAnything) {
                throw AgentRunFailure(problem.message)
            }

            val backoffMs = ProviderErrorClassifier.backoffMs(
                attempt = attempt,
                baseMs = AgentLimits.RETRY_BASE_DELAY_MS,
                maxMs = AgentLimits.RETRY_MAX_DELAY_MS,
            )
            emit(
                AgentEvent.Retrying(
                    attempt = attempt,
                    maxAttempts = AgentLimits.MAX_PROVIDER_RETRIES,
                    delayMs = backoffMs,
                    reason = problem.message,
                ),
            )
            delay(backoffMs)
            attempt++
        }
    }

    /**
     * Wraps a collector so an approval request refreshes the aggregate state.
     *
     * Done by interception rather than by handing the executor a callback: the
     * executor's job is to run a tool, and knowing that the badge needs updating
     * is a concern of the run, not of the tool.
     */
    private fun approvalAwareRelay(downstream: FlowCollector<AgentEvent>) =
        object : FlowCollector<AgentEvent> {
            override suspend fun emit(value: AgentEvent) {
                if (value is AgentEvent.ApprovalRequired) refreshState()
                downstream.emit(value)
            }
        }

    // ── aggregate state ───────────────────────────────────────────────────

    /**
     * Recomputed from scratch rather than patched.
     *
     * Two chats can be running, paused on different cards, or any combination, and
     * a patch-based update has to know which case it is in. Deriving the whole
     * aggregate makes that unnecessary.
     */
    private fun refreshState() = synchronized(stateLock) { refreshStateLocked() }

    private fun markRunning(chatId: String) = synchronized(stateLock) {
        runningChatIds += chatId
        refreshStateLocked()
    }

    private fun markStopped(chatId: String) = synchronized(stateLock) {
        runningChatIds -= chatId
        refreshStateLocked()
    }

    /** Must be called while holding [stateLock]. */
    private fun refreshStateLocked() {
        val ids = runningChatIds.toSet()
        _state.value = when {
            ids.isEmpty() -> AgentRunState.Idle
            pending.pendingCount() > 0 -> AgentRunState.WaitingApproval(ids)
            else -> AgentRunState.Running(ids)
        }
    }

    private companion object {
        /**
         * Sent with every request.
         *
         * Written for a phone driving a real shell rather than a sandbox: the
         * model is told the working directory is real, that output is long and may
         * be truncated, and that a failing command is information rather than an
         * obstacle to work around by guessing.
         */
        const val SYSTEM_PROMPT =
            "You are the agent inside Drosh, an Android terminal app. You act on a " +
                "real Linux environment running under PRoot. Commands you run have " +
                "real effects on real files.\n" +
                "Rules:\n" +
                "- Work inside the given working directory. Do not guess at paths you " +
                "have not seen; list or read first.\n" +
                "- Read before you write. Use read_file to see a file's current " +
                "contents rather than assuming what is in it.\n" +
                "- A command that fails is information. Read the output, form a new " +
                "hypothesis, and try something different. Never repeat a failing " +
                "command unchanged.\n" +
                "- Command output is long and may be truncated. If output was cut, " +
                "narrow the command — pipe through head or grep, or list fewer paths " +
                "— instead of re-running the same thing.\n" +
                "- Prefer one command that does the whole job over several that each " +
                "do part of it.\n" +
                "- If a decision is needed that only the user can make, call ask_user " +
                "instead of guessing.\n" +
                "- When the task is done, say so plainly and say what you changed."
    }
}
