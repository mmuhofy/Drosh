package dev.drosh.domain.agent

import kotlinx.serialization.json.JsonObject

/**
 * Events the agent loop emits while a run is in progress.
 *
 * This is the UI-facing counterpart of [LlmStreamEvent]: the adapter layer
 * normalizes provider wire formats, the loop normalizes *agent* progress, and
 * the UI only ever sees this type. Everything here is a fact about what
 * happened, not a request for the UI to do something.
 */
sealed interface AgentEvent {

    /** A loop iteration is about to call the provider. */
    data class TurnStarted(val step: Int, val maxSteps: Int) : AgentEvent

    /** Visible assistant text. */
    data class TextDelta(val text: String) : AgentEvent

    /** Hidden reasoning, streamed so the UI can show the model is working. */
    data class ReasoningDelta(val text: String) : AgentEvent

    /** Reasoning finished; clears the thinking indicator. */
    data class ReasoningCompleted(val text: String) : AgentEvent

    /**
     * A tool call is about to run.
     *
     * @param summary one-line label from `Tool.summarize`, for the collapsed row
     */
    data class ToolCallStarted(
        val callId: String,
        val name: String,
        val summary: String,
    ) : AgentEvent

    /** One line of live tool output. */
    data class ToolOutput(val callId: String, val line: String) : AgentEvent

    /** Free-form progress from a running tool. */
    data class ToolProgress(val callId: String, val text: String) : AgentEvent

    /**
     * The loop is parked waiting for the user.
     *
     * The run is not finished and not failed — it resumes when the matching
     * [AgentApproval] is answered. See `PendingRequests` in the agent module.
     */
    data class ApprovalRequired(val approval: AgentApproval) : AgentEvent

    /** A tool call reached a final result. */
    data class ToolCompleted(
        val callId: String,
        val name: String,
        val result: ToolResult,
        /**
         * True when the output was clipped before being handed to the model.
         * The UI shows this on the row so a shortened result is never mistaken
         * for a complete one.
         */
        val truncated: Boolean,
        val durationMs: Long,
        /**
         * The arguments the model sent, as JSON.
         *
         * Carried so the transcript row can store them: a restored conversation
         * needs the assistant's `tool_use` block rebuilt, and that block is made
         * of these. See [ChatMessage.ToolCall.arguments].
         *
         * Last, and defaulted, so the call sites that pass this positionally
         * keep meaning what they meant.
         */
        val arguments: JsonObject = JsonObject(emptyMap()),
    ) : AgentEvent

    /** Provider-reported token counts, updated as they arrive. */
    data class UsageUpdated(val usage: TokenUsage) : AgentEvent

    /** A transient provider failure; the loop will resend after [delayMs]. */
    data class Retrying(val attempt: Int, val maxAttempts: Int, val delayMs: Long, val reason: String) :
        AgentEvent

    /** One loop iteration ended. Emitted after tool results are folded into history. */
    data class TurnCompleted(val step: Int) : AgentEvent

    /** The run ended. Always the last event of a [dev.drosh.domain.agent.AgentSession.send] flow. */
    data class RunFinished(val outcome: RunOutcome) : AgentEvent
}

/** How a run ended. */
sealed interface RunOutcome {

    /** The model answered without asking for more tools. */
    data class Completed(val finalText: String) : RunOutcome

    /**
     * The loop hit [AgentLimits.MAX_STEPS] with the model still requesting tools.
     *
     * This is a successful stop with unfinished work, not an error — the
     * transcript is intact and the user can send another prompt to continue.
     */
    data class StepLimitReached(val steps: Int) : RunOutcome

    /**
     * The model asked for the same call over and over.
     *
     * Distinct from [StepLimitReached]: the step budget was not the constraint,
     * the loop was. Retrying would produce the same result, so the run stops and
     * says which call repeated.
     */
    data class RepetitiveLoop(val toolName: String, val repeats: Int) : RunOutcome

    /** The user stopped the run. */
    data object Cancelled : RunOutcome

    /** The run could not continue. */
    data class Failed(val message: String, val cause: Throwable? = null) : RunOutcome
}
