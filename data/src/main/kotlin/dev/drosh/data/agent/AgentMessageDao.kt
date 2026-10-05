package dev.drosh.data.agent

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentMessageDao {

    @Query("SELECT * FROM agent_messages WHERE chat_id = :chatId ORDER BY seq ASC")
    fun observe(chatId: String): Flow<List<AgentMessageEntity>>

    @Query("SELECT * FROM agent_messages WHERE chat_id = :chatId ORDER BY seq ASC")
    suspend fun load(chatId: String): List<AgentMessageEntity>

    @Query("SELECT COALESCE(MAX(seq), -1) FROM agent_messages WHERE chat_id = :chatId")
    suspend fun maxSeq(chatId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentMessageEntity)

    @Query("DELETE FROM agent_messages WHERE chat_id = :chatId")
    suspend fun deleteForChat(chatId: String)

    /**
     * Replace a chat's whole transcript.
     *
     * One transaction, because a partial write here is not recoverable by the
     * user: a chat restored with half its history produces a conversation the
     * model cannot follow and the user cannot explain. The delete and the
     * re-insert either both land or neither does.
     */
    @Transaction
    suspend fun replaceAll(chatId: String, messages: List<AgentMessageEntity>) {
        deleteForChat(chatId)
        messages.forEach { upsert(it) }
    }

    /**
     * Rows older than [keepSeq] that the model would see, oldest first.
     *
     * Filtered in SQL by kind rather than in Kotlin so compaction never has to
     * load a large transcript into memory only to discard most of it.
     */
    @Query(
        """
        SELECT * FROM agent_messages
        WHERE chat_id = :chatId AND seq <= :keepSeq AND kind IN ('user', 'assistant', 'tool')
        ORDER BY seq ASC
        """,
    )
    suspend fun loadSummarizable(chatId: String, keepSeq: Int): List<AgentMessageEntity>
}
