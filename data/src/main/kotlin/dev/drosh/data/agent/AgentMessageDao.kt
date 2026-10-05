package dev.drosh.data.agent

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * The Room table behind agent transcripts.
 *
 * Rows rather than domain types: `ChatMessage` is a sealed hierarchy with
 * non-obvious encodings, and a table that stores it directly would have to
 * reproduce all of them in a mapper. The mapping lives in `TranscriptStoreImpl`,
 * which is the one place that has to know how a transcript row is shaped.
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

