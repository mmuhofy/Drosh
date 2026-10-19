package dev.drosh.data.session

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY last_used_at_ms DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    fun observe(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun get(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SessionEntity)

    @Query("UPDATE sessions SET name = :newName WHERE id = :id")
    suspend fun rename(id: String, newName: String)

    @Query("UPDATE sessions SET last_used_at_ms = :nowMs, state = :state, last_snapshot = :snapshot WHERE id = :id")
    suspend fun updateRuntime(id: String, nowMs: Long, state: String, snapshot: String)

    @Query("UPDATE sessions SET state = :state WHERE id = :id")
    suspend fun updateState(id: String, state: String)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * File [sessionId] under [workspaceId], or ungroup it when that is null.
     *
     * One statement for both, so there is no window in which the row holds
     * neither a workspace nor the absence of one.
     */
    @Query("UPDATE sessions SET workspace_id = :workspaceId WHERE id = :sessionId")
    suspend fun setWorkspace(sessionId: String, workspaceId: String?)

    /**
     * Ungroup every session filed under [workspaceId].
     *
     * Called by the workspace repository before deleting one. See the note there:
     * the foreign key says `SET NULL`, but that only fires with the foreign_keys
     * pragma on, so the write is made explicit.
     */
    @Query("UPDATE sessions SET workspace_id = NULL WHERE workspace_id = :workspaceId")
    suspend fun ungroupWorkspace(workspaceId: String)
}