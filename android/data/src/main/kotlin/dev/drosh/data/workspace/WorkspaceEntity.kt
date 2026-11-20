package dev.drosh.data.workspace

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

// Schema-compat stub — workspace feature removed; do not delete (Room identity hash)
/**
 * Kept solely so the exported schema JSON and the Room identity hash stay
 * byte-identical to the shipped version-6 schema.
 *
 * Dropping this entity would change the hash Room stores in
 * `room_master_table`, and an installed database carrying the old hash fails to
 * open with `IllegalStateException: Room cannot verify the data integrity`.
 * The `workspaces` table and `sessions.workspace_id` therefore remain part of
 * the schema, and `MIGRATION_2_3` still creates them for upgrades. No feature
 * code reads or writes this entity any more.
 *
 * Do not delete without a schema version bump, a matching migration and a
 * regenerated `IDENTITY_HASH` / `6.json`.
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

    /** Index into a fixed palette. Retained for column shape only. */
    @ColumnInfo(name = "color_seed")
    val colorSeed: Int,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    @ColumnInfo(name = "last_opened_at_ms")
    val lastOpenedAtMs: Long,

    @ColumnInfo(name = "archived")
    val archived: Boolean,
)
