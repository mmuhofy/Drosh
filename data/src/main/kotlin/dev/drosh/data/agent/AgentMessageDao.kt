package dev.drosh.data.agent

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface AgentMessageDao {

    @Query("SELECT * FROM agent_messages WHERE chat_id = :chatId ORDER BY seq ASC")
    suspend fun load(chatId: String): List<AgentMessageEntity>

    @Query("SELECT COALESCE(MAX(seq), -1) FROM agent_messages WHERE chat_id = :chatId")
    suspend fun maxSeq(chatId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentMessageEntity)

    @Query("DELETE FROM agent_messages WHERE chat_id = :chatId")
    suspend fun deleteForChat(chatId: String)

    /**
     * Rows the model would see, oldest first.
     *
     * Filtered by kind in SQL rather than in Kotlin so compaction never loads a
     * large transcript into memory only to discard most of it.
     */
    @Query(
        """
        SELECT * FROM agent_messages
        WHERE chat_id = :chatId AND seq <= :keepSeq
          AND kind IN ('User', 'Assistant', 'ToolCall')
        ORDER BY seq ASC
        """,
    )
    suspend fun loadSummarizable(chatId: String, keepSeq: Int): List<AgentMessageEntity>

    /**
     * Replace a chat's whole transcript.
     *
     * One transaction, because a partial write is not recoverable by the user: a
     * chat restored with half its history produces a conversation the model cannot
     * follow and the user cannot explain.
     */
    @Transaction
    suspend fun replaceAll(chatId: String, messages: List<AgentMessageEntity>) {
        deleteForChat(chatId)
        messages.forEach { upsert(it) }
    }
}
