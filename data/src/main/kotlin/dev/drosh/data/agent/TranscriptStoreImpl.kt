package dev.drosh.agent/transcript

import dev.drosh.domain.agent.AgentApproval
import dev.drosh.domain.agent.ApprovalDecision
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
 * Transcript storage over a [MessageDao] port.
 *
 * Lives in `:agent` rather than `:data` because `AgentLoop` injects
 * `TranscriptStore` and `:agent`'s KSP pass cannot see bindings contributed by a
 * module it does not depend on. Room supplies the DAO through [MessageDao],
 * which `:data` binds to its Room implementation.
 *
 * ## `seq` allocation
 *
 * Read once per save rather than per row: a tool that streams forty output lines
 * would otherwise run a MAX query for every one of them.
 */
@Singleton
class TranscriptStoreImpl @Inject constructor(
    private val dao: AgentMessageDao,
) : TranscriptStore {

    override suspend fun load(chatId: String): List<ChatMessage> = withContext(Dispatchers.IO) {
        dao.load(chatId).mapNotNull { it.toMessage() }
    }

    override suspend fun save(chatId: String, messages: List<ChatMessage>) = withContext(Dispatchers.IO) {
        if (messages.isEmpty()) return@withContext

        // One read for the whole batch. Loading the chat per message would be
        // quadratic, and a tool that streams output saves after every line.
        val existingByMessageId = dao.load(chatId).associateBy { it.messageId }
        var seq = dao.maxSeq(chatId)
        val now = System.currentTimeMillis()

        messages.forEach { message ->
            val existing = existingByMessageId[message.id]
            dao.upsert(
                message.toEntity(
                    chatId = chatId,
                    // A row that already exists keeps its position; only genuinely
                    // new rows consume a sequence number, so re-saving a streaming
                    // message cannot move it down the transcript.
                    seq = existing?.seq ?: ++seq,
                    createdAtMs = existing?.createdAtMs ?: now,
                ),
            )
        }
    }

    /**
     * Store the model-facing view.
     *
     * Only the kinds that have a `LlmMessage` equivalent are written. The UI's
     * own rows — reasoning, approvals, notices — keep their sequence numbers, so
     * a run's rows and the UI's interleave correctly instead of overwriting each
     * other.
     */
    override suspend fun saveModelView(chatId: String, messages: List<LlmMessage>) =
        withContext(Dispatchers.IO) {
            if (messages.isEmpty()) return@withContext
            val existingByMessageId = dao.load(chatId).associateBy { it.messageId }
            var seq = dao.maxSeq(chatId)
            val now = System.currentTimeMillis()

            messages.forEachIndexed { index, message ->
                val stableId = message.stableId(index)
                val existing = existingByMessageId[stableId]
                dao.upsert(
                    message.toEntity(
                        chatId = chatId,
                        messageId = stableId,
                        seq = existing?.seq ?: ++seq,
                        createdAtMs = existing?.createdAtMs ?: now,
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
                    message.toEntity(chatId = chatId, seq = index, createdAtMs = now)
                },
            )
        }

    override suspend fun clear(chatId: String) = withContext(Dispatchers.IO) {
        dao.deleteForChat(chatId)
    }

    override fun assembleHistory(messages: List<ChatMessage>) = assembleModelHistory(messages)

    /** Rows eligible for compaction, oldest first, still filtered in SQL. */
    suspend fun summarizable(chatId: String, keepSeq: Int): List<ChatMessage> =
        withContext(Dispatchers.IO) {
            dao.loadSummarizable(chatId, keepSeq).mapNotNull { it.toMessage() }
        }

    // ── row ↔ message ─────────────────────────────────────────────────────

    private fun ChatMessage.toEntity(chatId: String, seq: Int, createdAtMs: Long) =
        AgentMessageEntity(
            chatId = chatId,
            seq = seq,
            kind = this::class.simpleName.orEmpty(),
            messageId = id,
            text = textForStorage(),
            toolCallId = (this as? ChatMessage.ToolCall)?.callId,
            toolName = (this as? ChatMessage.ToolCall)?.name,
            toolSummary = (this as? ChatMessage.ToolCall)?.summary,
            toolState = (this as? ChatMessage.ToolCall)?.state?.name,
            toolOutput = (this as? ChatMessage.ToolCall)?.output?.joinToString("\n")
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
                state = toolState.toToolCallState(),
                output = toolOutput
                    ?.split("\n")
                    ?.filter { it.isNotEmpty() }
                    .orEmpty(),
                finalOutput = toolFinalOutput,
                truncated = truncated,
                durationMs = durationMs,
                error = null,
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
        // half-understood message would render as a blank row, and a blank row in
        // the middle of a conversation is worse than a missing one.
        else -> null
    }

    private fun String?.toToolCallState(): ToolCallState =
        runCatching { ToolCallState.valueOf(this.orEmpty()) }.getOrDefault(ToolCallState.Cancelled)

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
