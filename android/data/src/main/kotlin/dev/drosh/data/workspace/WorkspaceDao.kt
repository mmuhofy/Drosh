package dev.drosh.data.workspace

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkspaceDao {

    /**
     * Most recently opened first.
     *
     * The order is the affordance: the project you were just in is the one you
     * are most likely to come back to, and an alphabetical list buries it under
     * however many older workspaces have accumulated.
     */
    @Query("SELECT * FROM workspaces WHERE archived = 0 ORDER BY last_opened_at_ms DESC")
    fun observeAll(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces WHERE archived = 1 ORDER BY last_opened_at_ms DESC")
    fun observeArchived(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces WHERE id = :id LIMIT 1")
    fun observe(id: String): Flow<WorkspaceEntity?>

    @Query("SELECT * FROM workspaces WHERE id = :id LIMIT 1")
    suspend fun get(id: String): WorkspaceEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM workspaces WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorkspaceEntity)

    @Query(
        "UPDATE workspaces SET name = :name, root_path = :rootPath, " +
            "description = :description, color_seed = :colorSeed WHERE id = :id",
    )
    suspend fun update(
        id: String,
        name: String,
        rootPath: String,
        description: String,
        colorSeed: Int,
    )

    @Query("UPDATE workspaces SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Query("UPDATE workspaces SET last_opened_at_ms = :nowMs WHERE id = :id")
    suspend fun touch(id: String, nowMs: Long)

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun delete(id: String)
}