package dev.drosh.data.ssh

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SshHostDao {

    /** Most recently used first — the affordance on the host list. */
    @Query("SELECT * FROM ssh_hosts ORDER BY last_used_at_ms DESC, created_at_ms DESC")
    fun observeAll(): Flow<List<SshHostEntity>>

    @Query("SELECT * FROM ssh_hosts WHERE id = :id LIMIT 1")
    fun observe(id: String): Flow<SshHostEntity?>

    @Query("SELECT * FROM ssh_hosts WHERE id = :id LIMIT 1")
    suspend fun get(id: String): SshHostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SshHostEntity)

    @Query("UPDATE ssh_hosts SET last_used_at_ms = :nowMs WHERE id = :id")
    suspend fun touch(id: String, nowMs: Long)

    @Query("DELETE FROM ssh_hosts WHERE id = :id")
    suspend fun delete(id: String)
}
