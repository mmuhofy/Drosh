package dev.drosh.domain.agent

/**
 * The rendered shape of a conversation.
 *
 * Distinct from [LlmStep]-style wire history: this is what the transcript shows,
 * with tool calls expanded into a row that has a state machine of its own. The
 * model-facing history is assembled separately inside the loop and never comes
 * from here.
 */
sealed interface ChatMessage {

    val id: String

    data class User(
        override val id: String,
        val text: String,
    ) : ChatMessage

    data class Assistant(
        override val id: String,
        val text: String,
        val streaming: Boolean = false,
    ) : ChatMessage

    /** Hidden reasoning, shown collapsed behind a "thinking" affordance. */
    data class Reasoning(
        override val id: String,
        val text: String,
    ) : ChatMessage

    data class ToolCall(
        override val id: String,
        val callId: String,
        val name: String,
        val summary: String,
        val state: ToolCallState,
        /** Live output, newest last. Capped by the caller. */
        val output: List<String> = emptyList(),
        /** Final output after the tool finished; null while running. */
        val finalOutput: String? = null,
        /** Set when the result was clipped before reaching the model. */
        val truncated: Boolean = false,
        val durationMs: Long? = null,
        /** Non-null when the tool failed. */
        val error: String? = null,
    ) : ChatMessage

    /** An approval request and its answer, as one row. */
    data class Approval(
        override val id: String,
        val approval: AgentApproval,
        val decision: ApprovalDecision? = null,
    ) : ChatMessage

    data class Failure(
        override val id: String,
        val message: String,
    ) : ChatMessage

    /**
     * Informational row that is neither model output nor an error — the run hit
     * its step limit, or a tool was blocked.
     */
    data class Notice(
        override val id: String,
        val text: String,
    ) : ChatMessage
}

sealed interface ToolCallState {

    /** Queued, not started. */
    data object Pending : ToolCallState

    /** Running; [ChatMessage.ToolCall.output] is filling up. */
    data object Running : ToolCallState

    /** Parked awaiting the user's decision. */
    data object AwaitingApproval : ToolCallState

    data object Succeeded : ToolCallState

    data object Failed : ToolCallState

    /** The user rejected it, or the work mode blocked it. */
    data object Cancelled : ToolCallState
}
