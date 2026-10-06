package dev.drosh.data.agent

import dev.drosh.domain.agent.AgentApproval
import dev.drosh.domain.agent.ApprovalDecision
import dev.drosh.domain.agent.AgentTodoCodec
import dev.drosh.domain.agent.ChatMessage
import dev.drosh.domain.agent.LlmMessage
import dev.drosh.domain.agent.TranscriptStore
import dev.drosh.domain.agent.ToolCallState
import dev.drosh.domain.agent.assembleModelHistory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed transcript storage.
 *
 * ## `seq` allocation
 *
 * Read once per batch, not per row. A tool that streams forty output lines would
 * otherwise run a MAX query and a full load for each one — quadratic in the
 * transcript length, on a phone.
 */
@Singleton
class TranscriptStoreImpl @Inject constructor(
    private val dao: AgentMessageDao,
) : TranscriptStore {

    override suspend fun load(chatId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        dao.load(chatId).mapNotNull { it.toMessage() }
    }

    override suspend fun save(chatId: String, messages: List<ChatMessage>) {
        // An empty transcript is a real state — the user cleared it — so the rows go
        // rather than the previous ones being left behind.
        if (messages.isEmpty()) {
            withContext(Dispatchers.IO) { dao.deleteForChat(chatId) }
            return
        }
        replaceAll(chatId, messages)
    }

    /**
     * Store the model-facing view.
     *
     * Ids are derived from position and content, not random: the same turn is
     * written again on every call and the row must be replaced rather than
     * duplicated. The index disambiguates two identical turns in a row.
     */
    override suspend fun saveModelView(chatId: String, messages: List<LlmMessage>) =
        withContext(Dispatchers.IO) {
            if (messages.isEmpty()) return@withContext
            val existing = dao.load(chatId).associateBy { it.messageId }
            var seq = dao.maxSeq(chatId)
            val now = System.currentTimeMillis()

            messages.forEachIndexed { index, message ->
                val id = when (message) {
                    is LlmMessage.User -> "model_u${index}_${message.text.hashCode()}"
                    is LlmMessage.Assistant -> "model_a${index}_${message.text.hashCode()}"
                    is LlmMessage.ToolResultMessage -> "model_t${index}_${message.callId}"
                }
                val prior = existing[id]
                dao.upsert(
                    message.toEntity(
                        chatId = chatId,
                        messageId = id,
                        seq = prior?.seq ?: ++seq,
                        createdAtMs = prior?.createdAtMs ?: now,
                    ),
                )
            }
        }

    override suspend fun replaceAll(chatId: String, messages: List<ChatMessage>) =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            dao.replaceAll(
                chatId,
                messages.mapIndexed { index, message ->
                    message.toEntity(chatId, index, now)
                },
            )
        }

    override suspend fun clear(chatId: String) = withContext(Dispatchers.IO) {
        dao.deleteForChat(chatId)
    }

    override fun assembleHistory(messages: List<ChatMessage>) = assembleModelHistory(messages)

    /** Rows eligible for compaction, still filtered in SQL. */
    suspend fun summarizable(chatId: String, keepSeq: Int): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            dao.loadSummarizable(chatId, keepSeq).mapNotNull { it.toMessage() }
        }

    // ── row ↔ message ─────────────────────────────────────────────────────

    private fun ChatMessage.toEntity(
        chatId: String,
        seq: Int,
        createdAtMs: Long,
        messageId: String = id,
    ) = AgentMessageEntity(
        chatId = chatId,
        seq = seq,
        kind = this::class.simpleName.orEmpty(),
        messageId = messageId,
        text = textForStorage(),
        toolCallId = (this as? ChatMessage.ToolCall)?.callId,
        toolName = (this as? ChatMessage.ToolCall)?.name,
        toolSummary = (this as? ChatMessage.ToolCall)?.summary,
        toolState = (this as? ChatMessage.ToolCall)?.state?.let(::stateName),
        toolOutput = (this as? ChatMessage.ToolCall)?.output
            ?.joinToString("\n")
            ?.takeIf { it.isNotEmpty() },
        toolFinalOutput = (this as? ChatMessage.ToolCall)?.finalOutput,
        truncated = (this as? ChatMessage.ToolCall)?.truncated ?: false,
        durationMs = (this as? ChatMessage.ToolCall)?.durationMs,
        approvalId = (this as? ChatMessage.Approval)?.approval?.id,
        approvalTitle = (this as? ChatMessage.Approval)?.approval?.title,
        approvalBody = (this as? ChatMessage.Approval)?.approval?.body,
        approvalDiff = (this as? ChatMessage.Approval)?.approval?.diff,
        approvalOptions = (this as? ChatMessage.Approval)?.approval?.options
            ?.joinToString(OPTION_SEPARATOR),
        approvalDecisionKind = (this as? ChatMessage.Approval)?.decision?.kind,
        approvalDecisionValue = (this as? ChatMessage.Approval)?.decision?.value,
        createdAtMs = createdAtMs,
    )

    /** The one text field every kind has, whichever that is. */
    private fun ChatMessage.textForStorage(): String = when (this) {
        is ChatMessage.User -> text
        is ChatMessage.Assistant -> text
        is ChatMessage.Reasoning -> text
        is ChatMessage.Notice -> text
        is ChatMessage.Failure -> message
        is ChatMessage.ToolCall -> ""
        is ChatMessage.Approval -> ""
    }

    private fun LlmMessage.toEntity(
        chatId: String,
        messageId: String,
        seq: Int,
        createdAtMs: Long,
    ): AgentMessageEntity = when (this) {
        is LlmMessage.User -> AgentMessageEntity(
            chatId = chatId,
            seq = seq,
            kind = "User",
            messageId = messageId,
            text = text,
            createdAtMs = createdAtMs,
        )

        is LlmMessage.Assistant -> AgentMessageEntity(
            chatId = chatId,
            seq = seq,
            kind = "Assistant",
            messageId = messageId,
            text = text,
            createdAtMs = createdAtMs,
        )

        is LlmMessage.ToolResultMessage -> AgentMessageEntity(
            chatId = chatId,
            seq = seq,
            kind = "ToolCall",
            messageId = messageId,
            text = "",
            toolCallId = callId,
            toolName = name,
            toolState = stateName(ToolCallState.Succeeded),
            toolFinalOutput = content,
            createdAtMs = createdAtMs,
        )
    }

    private fun AgentMessageEntity.toMessage(): ChatMessage? = when (kind) {
        "User" -> ChatMessage.User(messageId, text)
        "Assistant" -> ChatMessage.Assistant(messageId, text)
        "Reasoning" -> ChatMessage.Reasoning(messageId, text)
        "Notice" -> ChatMessage.Notice(messageId, text)
        "Failure" -> ChatMessage.Failure(messageId, text)

        "ToolCall" -> {
            val callId = toolCallId ?: return null
            val name = toolName ?: return null
            ChatMessage.ToolCall(
                id = messageId,
                callId = callId,
                name = name,
                summary = toolSummary.orEmpty(),
                state = toolState.toState(),
                output = toolOutput
                    ?.split("\n")
                    ?.filter { it.isNotEmpty() }
                    .orEmpty(),
                finalOutput = toolFinalOutput,
                truncated = truncated,
                durationMs = durationMs,
                // Rebuilt from the stored output rather than from a column of its
                // own: the checklist's storage is that string, and a second copy
                // could disagree with it.
                todos = if (name == TODO_TOOL) {
                    AgentTodoCodec.decode(toolFinalOutput)
                } else {
                    emptyList()
                },
            )
        }

        "Approval" -> {
            val approvalId = approvalId ?: return null
            ChatMessage.Approval(
                id = messageId,
                approval = AgentApproval(
                    id = approvalId,
                    chatId = chatId,
                    callId = toolCallId.orEmpty(),
                    toolName = toolName.orEmpty(),
                    title = approvalTitle.orEmpty(),
                    body = approvalBody,
                    diff = approvalDiff,
                    options = approvalOptions
                        ?.split(OPTION_SEPARATOR)
                        ?.filter { it.isNotEmpty() }
                        .orEmpty(),
                ),
                decision = approvalDecisionKind.toDecision(approvalDecisionValue),
            )
        }

        // An unknown kind is a row from a newer build. Dropping it is right: a
        // half-understood message renders as a blank row, and a blank row in the
        // middle of a conversation is worse than a missing one.
        else -> null
    }

    /**
     * Parse a stored state name.
     *
     * `ToolCallState` is a sealed interface of data objects, not an enum, so
     * there is no `valueOf` to lean on.
     *
     * Unknown values read as Cancelled rather than Running: a call whose state
     * cannot be parsed was not left running, and rendering it as such would put a
     * permanent spinner in the transcript for something that had already finished.
     */
    /**
     * The stored name for a state.
     *
     * Explicit, because `ToolCallState` is a sealed interface of data objects
     * whose `.name` is the class name — a refactor would change the database
     * format silently and orphan every transcript already on disk.
     */
    private fun stateName(state: ToolCallState): String = when (state) {
        is ToolCallState.Pending -> "Pending"
        is ToolCallState.Running -> "Running"
        is ToolCallState.AwaitingApproval -> "AwaitingApproval"
        is ToolCallState.Succeeded -> "Succeeded"
        is ToolCallState.Failed -> "Failed"
        is ToolCallState.Cancelled -> "Cancelled"
    }

    private fun String?.toState(): ToolCallState = when (this) {
        "Pending" -> ToolCallState.Pending
        "Running" -> ToolCallState.Running
        "AwaitingApproval" -> ToolCallState.AwaitingApproval
        "Succeeded" -> ToolCallState.Succeeded
        "Failed" -> ToolCallState.Failed
        else -> ToolCallState.Cancelled
    }

    private fun String?.toDecision(value: String?): ApprovalDecision? = when (this) {
        "approve" -> ApprovalDecision.Approve
        "reject" -> ApprovalDecision.Reject(value.orEmpty())
        "answer" -> ApprovalDecision.Answer(value.orEmpty())
        else -> null
    }

    private companion object {
        /**
         * Separator for the approval option list.
         *
         * ASCII unit separator, not a space: an option is free text the model
         * wrote, and joining on a space would split "skip the tests" into two
         * options on restore.
         */
        const val OPTION_SEPARATOR = "\u001F"

        /** Must match `UpdateTodoTool.NAME`. */
        const val TODO_TOOL = "update_todo"
    }
}

private val ApprovalDecision.kind: String
    get() = when (this) {
        is ApprovalDecision.Approve -> "approve"
        is ApprovalDecision.Reject -> "reject"
        is ApprovalDecision.Answer -> "answer"
    }

private val ApprovalDecision.value: String?
    get() = when (this) {
        is ApprovalDecision.Approve -> null
        is ApprovalDecision.Reject -> reason
        is ApprovalDecision.Answer -> text
    }
