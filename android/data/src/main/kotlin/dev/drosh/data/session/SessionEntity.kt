package dev.drosh.data.session

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A session, as persisted.
 *
 * Invariant 1 of `docs/SESSION-SYSTEM.md` — "a session is one row here" — still
 * holds. A session row is the session's whole persistent identity; a row that
 * does not exist is not a session.
 */
@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = dev.drosh.data.workspace.WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspace_id"],
            // Deleting a workspace ungroups its sessions instead of taking them
            // with it. A grouping label is cheap to lose; a session's history is
            // not, and cascade would delete rows the user never asked to lose.
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("workspace_id")],
)
data class SessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "state")
    val state: String,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    @ColumnInfo(name = "last_used_at_ms")
    val lastUsedAtMs: Long,

    @ColumnInfo(name = "last_snapshot")
    val lastSnapshot: String,

    /**
     * The workspace this session is filed under, null when it is in none.
     *
     * Nullable on purpose: most sessions are never grouped, and an empty string
     * would have to be special-cased everywhere the column is read.
     */
    @ColumnInfo(name = "workspace_id")
    val workspaceId: String? = null,
)