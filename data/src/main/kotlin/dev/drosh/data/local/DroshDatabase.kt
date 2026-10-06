package dev.drosh.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.drosh.data.agent.AgentChatDao
import dev.drosh.data.agent.AgentChatEntity
import dev.drosh.data.agent.AgentMessageDao
import dev.drosh.data.agent.AgentMessageEntity
import dev.drosh.data.ssh.SshHostDao
import dev.drosh.data.ssh.SshHostEntity
import dev.drosh.data.ssh.SshKeyDao
import dev.drosh.data.ssh.SshKeyEntity
import dev.drosh.data.session.SessionDao
import dev.drosh.data.session.SessionEntity
import dev.drosh.data.workspace.WorkspaceDao
import dev.drosh.data.workspace.WorkspaceEntity

/**
 * Single source of truth for all persistent metadata.
 *
 * Versioning: bump [version] and write a migration whenever a schema
 * change ships. Room generates the migration SQL from the version diff
 * during `assembleDebug`, but production migrations are written by
 * hand and committed alongside the schema bump.
 *
 * Schema export target is set in `data/build.gradle.kts` to
 * `data/schemas/`; CI runs `gradlew :data:exportSchemaDebug` to surface
 * the diff on PRs that touch this file.
 */
@Database(
    entities = [
        SessionEntity::class,
        WorkspaceEntity::class,
        AgentChatEntity::class,
        AgentMessageEntity::class,
        SshHostEntity::class,
        SshKeyEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class DroshDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun sshHostDao(): SshHostDao
    abstract fun sshKeyDao(): SshKeyDao
    abstract fun agentChatDao(): AgentChatDao
    abstract fun agentMessageDao(): AgentMessageDao

    companion object {
        const val DATABASE_NAME = "irisshell.db"

        /**
         * The identity hash Room expects for the current schema.
         *
         * Room stores this in `room_master_table` and compares it on every open. A
         * database whose marker is stale fails to open with
         * `IllegalStateException: Room cannot verify the data integrity` — on the
         * device, on the next launch, and never in a build.
         *
         * **Update this whenever the schema changes — not when the version does.**
         * Verified by exporting two snapshots over identical entities: the hash was
         * the same, so Room derives it from the schema alone. Only a schema change
         * invalidates it. The value here is the one in 4.json, and 3.json holds the
         * previous one for the schema without workspaces.
         *
         * CI diffs the exported `data/schemas/` snapshot against the committed one,
         * so a missed update is a red build rather than a crash on every install.
         */
        const val IDENTITY_HASH = "7a24d67aad74fdf9f5ff71afe52144c9"

        /**
         * The identity hash for the version-3 schema — everything except agent
         * transcripts.
         *
         * Only needed by [MIGRATION_2_3], whose schema genuinely differs. The hash
         * is overwritten again by [MIGRATION_3_4] for any device that continues.
         */
        const val IDENTITY_HASH_V3: String = "2c34080b274172ef8334419145cb02ea"

        /**
         * The SQL that brings a database to the current schema.
         *
         * Both agent tables are created `IF NOT EXISTS` so this is safe over a
         * database that already has them, which is the normal case: only a
         * database that took the broken version-3 path is missing them.
         *
         * The trailing UPDATE is the part that is easy to leave out and impossible
         * to see in a build — without it the tables are right and the marker is
         * stale, which is a green build and a crash on launch.
         */
        private fun createAgentTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_chats` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `working_directory` TEXT NOT NULL,
                    `created_at_ms` INTEGER NOT NULL,
                    `last_used_at_ms` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `last_message_preview` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_messages` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `chat_id` TEXT NOT NULL,
                    `seq` INTEGER NOT NULL,
                    `kind` TEXT NOT NULL,
                    `message_id` TEXT NOT NULL,
                    `text` TEXT NOT NULL,
                    `tool_call_id` TEXT,
                    `tool_name` TEXT,
                    `tool_summary` TEXT,
                    `tool_state` TEXT,
                    `tool_output` TEXT,
                    `tool_final_output` TEXT,
                    `truncated` INTEGER NOT NULL,
                    `duration_ms` INTEGER,
                    `approval_id` TEXT,
                    `approval_title` TEXT,
                    `approval_body` TEXT,
                    `approval_diff` TEXT,
                    `approval_options` TEXT,
                    `approval_decision_kind` TEXT,
                    `approval_decision_value` TEXT,
                    `created_at_ms` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_messages_chat_id_seq`
                ON `agent_messages` (`chat_id`, `seq`)
                """.trimIndent(),
            )
        }

        private fun writeIdentityHash(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE room_master_table SET identity_hash = '$IDENTITY_HASH'")
        }

        /**
         * 1 → 2: agent chats.
         *
         * From the workspace branch. Additive, so no existing row is rewritten.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createAgentTables(db)
                writeIdentityHash(db)
            }
        }

        /**
         * 2 → 3: workspaces, and sessions gain a workspace.
         *
         * From the workspace branch, which rebuilt `sessions` into a new table and
         * copied the rows across — the only way to add a foreign key to an existing
         * table in SQLite. Every existing session lands with a null workspace,
         * which the grouping UI treats as ungrouped.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `workspaces` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `root_path` TEXT NOT NULL,
                        `description` TEXT NOT NULL,
                        `color_seed` INTEGER NOT NULL,
                        `created_at_ms` INTEGER NOT NULL,
                        `last_opened_at_ms` INTEGER NOT NULL,
                        `archived` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sessions_new` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `created_at_ms` INTEGER NOT NULL,
                        `last_used_at_ms` INTEGER NOT NULL,
                        `last_snapshot` TEXT NOT NULL,
                        `workspace_id` TEXT,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`workspace_id`) REFERENCES `workspaces`(`id`)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `sessions_new` (
                        `id`, `name`, `state`, `created_at_ms`,
                        `last_used_at_ms`, `last_snapshot`, `workspace_id`
                    )
                    SELECT
                        `id`, `name`, `state`, `created_at_ms`,
                        `last_used_at_ms`, `last_snapshot`, NULL
                    FROM `sessions`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `sessions`")
                db.execSQL("ALTER TABLE `sessions_new` RENAME TO `sessions`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sessions_workspace_id` " +
                        "ON `sessions` (`workspace_id`)",
                )
                // The hash that reaches v3 is the one for the agent schema without
                // transcripts, so it is necessarily wrong for v3. Written here
                // rather than left to 3→4, because a device that never opens the
                // app again on this build should still open correctly.
                db.execSQL("UPDATE room_master_table SET identity_hash = '$IDENTITY_HASH_V3'")
            }
        }

        /**
         * 3 → 4: agent transcripts, and the identity marker.
         *
         * The missing path that made every affected install unlaunchable. Version 3
         * shipped without a migration to match, so those devices sit at 3 with
         * neither agent table and the version-2 marker still in
         * `room_master_table`. Room compares the version first, finds 3 matches 3,
         * runs nothing, and then fails the identity check.
         *
         * This also fixes the marker for every other path: a database that
         * upgraded through 1→2 or 2→3 has the correct tables but a hash that
         * predates the transcripts, which is the same crash.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createAgentTables(db)
                writeIdentityHash(db)
            }
        }

        /**
         * 4 → 5: ssh hosts and keys.
         *
         * Additive — both tables are new, nothing is rebuilt.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ssh_hosts` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `hostname` TEXT NOT NULL,
                        `port` INTEGER NOT NULL,
                        `username` TEXT NOT NULL,
                        `auth_method` TEXT NOT NULL,
                        `key_id` TEXT,
                        `jump_host_id` TEXT,
                        `is_production` INTEGER NOT NULL,
                        `tags_json` TEXT NOT NULL,
                        `last_used_at_ms` INTEGER NOT NULL,
                        `created_at_ms` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ssh_keys` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `algorithm` TEXT NOT NULL,
                        `public_key_openssh` TEXT NOT NULL,
                        `comment` TEXT NOT NULL,
                        `created_at_ms` INTEGER NOT NULL,
                        `last_used_at_ms` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                writeIdentityHash(db)
            }
        }

        /**
         * Every migration, in order.
         *
         * Registered in `DatabaseModule`. Room walks this to find a path from
         * whatever version a device is at, so a device on 1, on 2 or on the broken
         * 3 all reach the current schema.
         */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
    }
}
