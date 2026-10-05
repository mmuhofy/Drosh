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
    version = 3,
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
         * Room stores this in `room_master_table` and compares it on every open.
         * A migration that creates the right tables but does not update the hash
         * produces, on the next launch:
         *
         * ```
         * IllegalStateException: Room cannot verify the data integrity.
         * Looks like you've changed schema but forgot to update the version
         * number. Expected identity hash: <this>, found: <the old one>
         * ```
         *
         * The two hashes are the entire error message — the device's schema is
         * fine, the marker in it is stale. Room supplies the expected value in
         * that message, which is where this constant came from.
         *
         * **It has to be updated whenever the schema changes.** `assembleDebug`
         * writes the real value to `data/schemas/`, and CI should diff that
         * directory so a stale constant fails the build rather than every
         * install. Until it does, a wrong value here is a crash on first launch
         * and nothing else.
         */
        const val IDENTITY_HASH = "2c34080b274172ef8334419145cb02ea"

        /**
         * 1 → 3: agent chats and agent transcripts.
         *
         * One migration rather than 1→2 then 2→3, so there is a single identity
         * hash to write and only one place to forget. Room applies a migration
         * that spans versions when no intermediate step exists, and every
         * installed database is at 1 or 2 anyway — the two schema steps shipped
         * days apart and no release landed on 2 alone.
         *
         * Additive, so no existing row is rewritten. The unique index on
         * (chat_id, seq) is what makes "before" unambiguous in a transcript:
         * several rows can be written in the same millisecond while a tool
         * streams, and a timestamp tie would make restore order non-deterministic.
         *
         * The trailing UPDATE is the part that is easy to omit and impossible to
         * miss in CI — it fails on the device, not in the build.
         */
        val MIGRATION_1_3 = object : Migration(1, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
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
        }
    }
}
