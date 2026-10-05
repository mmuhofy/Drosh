package dev.drosh.data.agent

import dev.drosh.agent.transcript.AgentMessageRow
import dev.drosh.agent.transcript.MessageDao
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * The Room table behind agent transcripts.
 *
 * Returned as [AgentMessageRow] rather than [AgentMessageEntity] so the rest of
 * the project never sees Room's annotations. The entity stays a private
 * implementation detail of this file's adapter.
 */
@Dao
interface AgentMessageDao {

    @Query("SELECT * FROM agent_messages WHERE chat_id = :chatId ORDER BY seq ASC")
    fun observe(chatId: String): Flow<List<AgentMessageEntity>>

    @Query("SELECT * FROM agent_messages WHERE chat_id = :chatId ORDER BY seq ASC")
    suspend fun loadRows(chatId: String): List<AgentMessageEntity>

    @Query("SELECT COALESCE(MAX(seq), -1) FROM agent_messages WHERE chat_id = :chatId")
    suspend fun maxSeq(chatId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRow(row: AgentMessageEntity)

    @Query("DELETE FROM agent_messages WHERE chat_id = :chatId")
    suspend fun deleteForChat(chatId: String)

    @Query(
        """
        SELECT * FROM agent_messages
        WHERE chat_id = :chatId AND seq <= :keepSeq AND kind IN ('User', 'Assistant', 'ToolCall')
        ORDER BY seq ASC
        """,
    )
    suspend fun loadSummarizable(chatId: String, keepSeq: Int): List<AgentMessageEntity>

    @Transaction
    suspend fun replaceAllRows(chatId: String, rows: List<AgentMessageEntity>) {
        deleteForChat(chatId)
        rows.forEach { upsertRow(it) }
    }
}

/**
 * Binds the Room DAO to the port `:agent` depends on.
 *
 * The two shapes are identical field for field, so this is a rename and not a
 * translation — a mapping layer would be one more place for them to drift.
 */
class RoomMessageDao(private val dao: AgentMessageDao) : MessageDao {
    override suspend fun load(chatId: String): List<AgentMessageRow> =
        dao.loadRows(chatId).map { it.toRow() }

    override suspend fun maxSeq(chatId: String): Int = dao.maxSeq(chatId)

    override suspend fun upsert(row: AgentMessageRow) = dao.upsertRow(row.toEntity())

    override suspend fun deleteForChat(chatId: String) = dao.deleteForChat(chatId)

    override suspend fun loadSummarizable(chatId: String, keepSeq: Int): List<AgentMessageRow> =
        dao.loadSummarizable(chatId, keepSeq).map { it.toRow() }

    override suspend fun replaceAll(chatId: String, rows: List<AgentMessageRow>) =
        dao.replaceAllRows(chatId, rows.map { it.toEntity() })

    private fun AgentMessageEntity.toRow() = AgentMessageRow(
        chatId = chatId,
        seq = seq,
        kind = kind,
        messageId = messageId,
        text = text,
        toolCallId = toolCallId,
        toolName = toolName,
        toolSummary = toolSummary,
        toolState = toolState,
        toolOutput = toolOutput,
        toolFinalOutput = toolFinalOutput,
        truncated = truncated,
        durationMs = durationMs,
        approvalId = approvalId,
        approvalTitle = approvalTitle,
        approvalBody = approvalBody,
        approvalDiff = approvalDiff,
        approvalOptions = approvalOptions,
        approvalDecisionKind = approvalDecisionKind,
        approvalDecisionValue = approvalDecisionValue,
        createdAtMs = createdAtMs,
    )

    private fun AgentMessageRow.toEntity() = AgentMessageEntity(
        chatId = chatId,
        seq = seq,
        kind = kind,
        messageId = messageId,
        text = text,
        toolCallId = toolCallId,
        toolName = toolName,
        toolSummary = toolSummary,
        toolState = toolState,
        toolOutput = toolOutput,
        toolFinalOutput = toolFinalOutput,
        truncated = truncated,
        durationMs = durationMs,
        approvalId = approvalId,
        approvalTitle = approvalTitle,
        approvalBody = approvalBody,
        approvalDiff = approvalDiff,
        approvalOptions = approvalOptions,
        approvalDecisionKind = approvalDecisionKind,
        approvalDecisionValue = approvalDecisionValue,
        createdAtMs = createdAtMs,
    )
}
