package dev.drosh.domain.agent

/**
 * A question the agent needs the user to answer before it can continue.
 *
 * One type covers both interactive cases rather than having two flows:
 *
 *  - `write_file` sets [diff] and offers approve/reject
 *  - `ask_user` sets [options] and offers a free-text or multiple-choice answer
 *
 * The loop parks on `ToolResult.AwaitingApproval(approval.id)` until
 * `answerApproval` delivers a matching [ApprovalDecision]. Nothing about the
 * two cases needs to diverge below that.
 *
 * @param id correlation handle; the tool result carries it back to the loop
 * @param chatId chat that raised it
 * @param callId tool call that raised it
 * @param title single line, e.g. `vite.config.js değiştirilsin mi?`
 * @param body optional longer explanation shown above the controls
 * @param diff unified diff when the decision is about a file change
 * @param options choices for [ApprovalDecision.Answer]; empty means free text
 */
data class AgentApproval(
    val id: String,
    val chatId: String,
    val callId: String,
    val toolName: String,
    val title: String,
    val body: String? = null,
    val diff: String? = null,
    val options: List<String> = emptyList(),
)

/** The user's answer to an [AgentApproval]. */
sealed interface ApprovalDecision {

    /** Run the tool as requested. */
    data object Approve : ApprovalDecision

    /**
     * Do not run it.
     *
     * @param reason sent back to the model verbatim — say *why*, so it can adapt
     *               rather than retry the same thing blindly
     */
    data class Reject(val reason: String) : ApprovalDecision

    /** Answer a question. [text] is fed to the model as the tool result. */
    data class Answer(val text: String) : ApprovalDecision
}
