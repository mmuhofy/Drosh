package dev.drosh.data.ssh

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SshKeyDao {

    @Query("SELECT * FROM ssh_keys ORDER BY last_used_at_ms DESC, created_at_ms DESC")
    fun observeAll(): Flow<List<SshKeyEntity>>

    @Query("SELECT * FROM ssh_keys WHERE id = :id LIMIT 1")
    fun observe(id: String): Flow<SshKeyEntity?>

    @Query("SELECT * FROM ssh_keys WHERE id = :id LIMIT 1")
    suspend fun get(id: String): SshKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SshKeyEntity)

    @Query("UPDATE ssh_keys SET last_used_at_ms = :nowMs WHERE id = :id")
    suspend fun touch(id: String, nowMs: Long)

    @Query("DELETE FROM ssh_keys WHERE id = :id")
    suspend fun delete(id: String)
}
