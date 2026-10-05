package dev.drosh.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.drosh.data.agent.AgentChatDao
import dev.drosh.data.agent.AgentChatEntity
import dev.drosh.data.agent.AgentMessageDao
import dev.drosh.data.agent.AgentMessageEntity
import dev.drosh.data.session.SessionDao
import dev.drosh.data.session.SessionEntity

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
    entities = [SessionEntity::class, AgentChatEntity::class, AgentMessageEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class DroshDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun agentChatDao(): AgentChatDao
    abstract fun agentMessageDao(): AgentMessageDao

    companion object {
        const val DATABASE_NAME = "irisshell.db"

        /**
         * The identity hash Room expects for the current schema.
         *
         * Room stores this in `room_master_table` and compares it on every open. A
         * database whose marker is stale fails with:
         *
         * ```
         * IllegalStateException: Room cannot verify the data integrity.
         * Expected identity hash: <this>, found: <the one in the database>
         * ```
         *
         * **Update this whenever the schema or the version changes.** The build
         * exports the authoritative value to `data/schemas/`, and CI fails when
         * that export differs from the committed snapshot, so a missed update is a
         * red build rather than a crash on every install.
         */
        const val IDENTITY_HASH = "PLACEHOLDER_V4"

        /**
         * The SQL that brings a database up to the current schema.
         *
         * Both tables are created `IF NOT EXISTS`, which is what makes this safe
         * to run over a database that already has them — a device that went
         * through a broken upgrade is at the current version with no tables at
         * all, and one that upgraded cleanly has both.
         *
         * The trailing UPDATE is the part that is easy to leave out and invisible
         * until a device opens the app: without it the tables are right and the
         * marker is stale, which is a build that passes and a crash on launch.
         */
        private fun bringToCurrentSchema(db: SupportSQLiteDatabase) {
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
            db.execSQL("UPDATE room_master_table SET identity_hash = '$IDENTITY_HASH'")
        }

        /** From a version-1 database: only `sessions` exists. */
        val MIGRATION_1_4 = object : Migration(1, 4) {
            override fun migrate(db: SupportSQLiteDatabase) = bringToCurrentSchema(db)
        }

        /**
         * From a version-3 database, which is the broken case: the version was
         * bumped without a migration, so these devices are at 3 with neither
         * agent table and a stale marker. Room finds no path forward and fails the
         * identity check on launch.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) = bringToCurrentSchema(db)
        }
    }
}