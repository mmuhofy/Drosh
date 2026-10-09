package dev.drosh.domain.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The agent, as the UI sees it.
 *
 * One instance is bound app-wide; it multiplexes every open agent chat, keyed
 * by [AgentRequest.chatId]. An agent chat is a first-class thing that owns its
 * own terminal session — the loop does not care where that terminal lives, it
 * only needs the working directory.
 *
 * ## Credentials never cross this boundary
 *
 * [AgentRequest] carries a provider *id* and a model id, not an API key or a
 * base URL. The previous implementation passed both through this interface,
 * which meant the UI layer held the user's key in a ViewModel field and lost it
 * on every recreation. Resolving credentials is the agent module's job, via
 * its vault.
 *
 * ## History is not passed in
 *
 * The loop keeps per-chat conversation history internally and the UI never
 * replays it. That keeps token assembly in one place and means the UI cannot
 * desynchronise the loop's view of the conversation.
 */
interface AgentSession {

    /** Aggregate run state, for the top-bar indicator and the home list. */
    val state: StateFlow<AgentRunState>

    /**
     * Send a prompt and run the loop until it stops.
     *
     * The returned flow completes after [AgentEvent.RunFinished]. Collecting
     * this flow is what drives the run — cancelling the collecting coroutine
     * cancels the loop, the in-flight provider request and any running tool.
     * There is no separate `abort` call to keep in sync with it.
     *
     * Only one run may be active per chat; a second call for a chat that is
     * already running fails fast rather than interleaving two loops over the
     * same history.
     */
    fun send(request: AgentRequest): Flow<AgentEvent>

    /**
     * Answer a parked [AgentEvent.ApprovalRequired].
     *
     * Safe to call from anywhere; the decision is matched to the pending call by
     * [AgentApproval.id] and the parked tool resumes. Returns false when no such
     * request is pending, which happens if the run was already cancelled.
     */
    fun answerApproval(approvalId: String, decision: ApprovalDecision): Boolean

    /** Cancel the run for one chat, leaving its transcript intact. */
    fun cancel(chatId: String)

    /** Cancel every run. Called when the process is going away. */
    fun cancelAll()
}

/**
 * A prompt plus the minimum the loop needs to route it.
 *
 * @param chatId which agent chat this belongs to; also selects the terminal
 * @param providerId key into the provider registry, e.g. `openrouter`
 * @param modelId provider-native model string, e.g. `anthropic/claude-sonnet-4`
 * @param prompt the user's message
 * @param workingDirectory absolute path inside the guest filesystem the agent
 *                         is scoped to for this run
 * @param reasoningEffort the user's stored effort choice for this model, or
 *        null for the model's own default. See `LlmRequest.reasoningEffort`.
 */
data class AgentRequest(
    val chatId: String,
    val providerId: String,
    val modelId: String,
    val prompt: String,
    val workingDirectory: String,
    val reasoningEffort: String? = null,
)

/** Aggregate run state across all chats. */
sealed interface AgentRunState {

    /** Nothing running anywhere. */
    data object Idle : AgentRunState

    /** At least one chat is streaming or running tools. */
    data class Running(val chatIds: Set<String>) : AgentRunState

    /**
     * At least one chat is parked on the user; others may still be running.
     *
     * Distinct from [Running] because it needs a different affordance: a stop
     * button is wrong when the run is waiting for a yes or no.
     */
    data class WaitingApproval(val chatIds: Set<String>) : AgentRunState
}
