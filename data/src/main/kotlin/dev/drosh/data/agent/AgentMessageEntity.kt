package dev.drosh.data.agent

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row of an agent chat's transcript.
 *
 * ## The same rows serve two readers
 *
 * The transcript the user sees and the history the model sees are different
 * views of one thing. Both come from this table — a `ToolCall` row's
 * `final_output` is what the model receives as its tool result, and the same
 * string is rendered in the expanded row. Storing them separately would let them
 * drift, and the symptom of drift is a model contradicting something the user
 * just read.
 *
 * Rows that only the user sees — reasoning, approvals, notices — persist too and
 * are skipped when history is assembled. One table means one ordering, one cursor
 * and one delete.
 *
 * ## `seq` rather than a timestamp
 *
 * Several rows can be written inside the same millisecond while a tool streams,
 * and a timestamp tie would make restore order non-deterministic. A per-chat
 * monotonic counter is what actually defines "before".
 */
@Entity(
    tableName = "agent_messages",
    indices = [Index(value = ["chat_id", "seq"], unique = true)],
)
data class AgentMessageEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "chat_id")
    val chatId: String,

    @ColumnInfo(name = "seq")
    val seq: Int,

    /** [dev.drosh.domain.agent.ChatMessage] subclass discriminator. */
    @ColumnInfo(name = "kind")
    val kind: String,

    /**
     * The message id as the UI knows it.
     *
     * Stored rather than generated on restore so Compose keys, `saveable` state
     * and a row that was still streaming all survive a process restart pointing
     * at the same thing.
     */
    @ColumnInfo(name = "message_id")
    val messageId: String,

    /** Assistant/user text, reasoning text, or a notice/failure line. */
    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "tool_call_id")
    val toolCallId: String? = null,
    @ColumnInfo(name = "tool_name")
    val toolName: String? = null,
    @ColumnInfo(name = "tool_summary")
    val toolSummary: String? = null,
    /** [dev.drosh.domain.agent.ToolCallState] name. */
    @ColumnInfo(name = "tool_state")
    val toolState: String? = null,
    /** Newline-joined live output, so a streaming row survives a restart. */
    @ColumnInfo(name = "tool_output")
    val toolOutput: String? = null,
    @ColumnInfo(name = "tool_final_output")
    val toolFinalOutput: String? = null,
    @ColumnInfo(name = "truncated")
    val truncated: Boolean = false,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null,

    @ColumnInfo(name = "approval_id")
    val approvalId: String? = null,
    @ColumnInfo(name = "approval_title")
    val approvalTitle: String? = null,
    @ColumnInfo(name = "approval_body")
    val approvalBody: String? = null,
    @ColumnInfo(name = "approval_diff")
    val approvalDiff: String? = null,
    /** Comma-separated; the option list is display-only and never replayed. */
    @ColumnInfo(name = "approval_options")
    val approvalOptions: String? = null,
    /** "approve" | "reject" | "answer", plus the reason or text. */
    @ColumnInfo(name = "approval_decision_kind")
    val approvalDecisionKind: String? = null,
    @ColumnInfo(name = "approval_decision_value")
    val approvalDecisionValue: String? = null,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
)
