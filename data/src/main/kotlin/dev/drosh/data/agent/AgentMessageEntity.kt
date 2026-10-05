package dev.drosh.agent/transcript

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The Room table behind agent transcripts.
 *
 * The row shape itself, carrying Room's annotations. `TranscriptStoreImpl` maps
 * between this and the sealed `ChatMessage` hierarchy — the one place that has
 * to know how a transcript row is encoded.
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
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "message_id")
    val messageId: String,
    @ColumnInfo(name = "text")
    val text: String,
    @ColumnInfo(name = "tool_call_id")
    val toolCallId: String? = null,
    @ColumnInfo(name = "tool_name")
    val toolName: String? = null,
    @ColumnInfo(name = "tool_summary")
    val toolSummary: String? = null,
    @ColumnInfo(name = "tool_state")
    val toolState: String? = null,
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
    @ColumnInfo(name = "approval_options")
    val approvalOptions: String? = null,
    @ColumnInfo(name = "approval_decision_kind")
    val approvalDecisionKind: String? = null,
    @ColumnInfo(name = "approval_decision_value")
    val approvalDecisionValue: String? = null,
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
)
