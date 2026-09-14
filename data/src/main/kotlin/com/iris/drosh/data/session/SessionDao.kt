package com.iris.irisshell.data.session

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
}