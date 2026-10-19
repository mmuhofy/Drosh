package dev.drosh.data.workspace

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A workspace, as persisted.
 *
 * ## What is not stored here
 *
 * Sessions, and anything a session owns.
 *
 * The grouping is a nullable foreign key on the session row rather than a
 * collection column here ([dev.drosh.data.session.SessionEntity.workspaceId]).
 * A workspace can hold many sessions but is not *made of* them, and a column of
 * ids on this side would need its own consistency rules for every write that
 * touched either table.
 *
 * [rootPath] is a label. Nothing reads it to decide where to run a shell, and it
 * is stored normalised by
 * [dev.drosh.domain.workspace.WorkspacePath.normalise] so two spellings of one
 * directory cannot become two workspaces.
 */
@Entity(tableName = "workspaces")
data class WorkspaceEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    /** Absolute path inside the guest filesystem. */
    @ColumnInfo(name = "root_path")
    val rootPath: String,

    @ColumnInfo(name = "description")
    val description: String,

    /** Index into a fixed palette; see `WORKSPACE_COLOR_SEED_COUNT`. */
    @ColumnInfo(name = "color_seed")
    val colorSeed: Int,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    @ColumnInfo(name = "last_opened_at_ms")
    val lastOpenedAtMs: Long,

    @ColumnInfo(name = "archived")
    val archived: Boolean,
)