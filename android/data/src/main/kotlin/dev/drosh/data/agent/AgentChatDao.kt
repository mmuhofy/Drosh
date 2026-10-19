package dev.drosh.data.agent

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentChatDao {

    /**
     * Most recently used first.
     *
     * The ordering is the whole navigation affordance: the chat you were just in
     * is the one you most likely want again, and an alphabetical list would bury
     * it under however many older chats have accumulated.
     */
    @Query("SELECT * FROM agent_chats ORDER BY last_used_at_ms DESC")
    fun observeAll(): Flow<List<AgentChatEntity>>

    @Query("SELECT * FROM agent_chats WHERE id = :id LIMIT 1")
    fun observe(id: String): Flow<AgentChatEntity?>

    @Query("SELECT * FROM agent_chats WHERE id = :id LIMIT 1")
    suspend fun get(id: String): AgentChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AgentChatEntity)

    @Query("UPDATE agent_chats SET name = :newName WHERE id = :id")
    suspend fun rename(id: String, newName: String)

    @Query("UPDATE agent_chats SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE agent_chats SET last_used_at_ms = :nowMs WHERE id = :id")
    suspend fun touch(id: String, nowMs: Long)

    @Query(
        "UPDATE agent_chats SET last_message_preview = :preview, last_used_at_ms = :nowMs " +
            "WHERE id = :id",
    )
    suspend fun updatePreview(id: String, preview: String, nowMs: Long)

    @Query("DELETE FROM agent_chats WHERE id = :id")
    suspend fun delete(id: String)
}
