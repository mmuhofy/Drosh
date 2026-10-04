package dev.drosh.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.drosh.data.agent.AgentChatDao
import dev.drosh.data.agent.AgentChatEntity
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
    entities = [SessionEntity::class, AgentChatEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class DroshDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun agentChatDao(): AgentChatDao

    companion object {
        const val DATABASE_NAME = "irisshell.db"

        /**
         * 1 → 2: agent chats.
         *
         * Written by hand and committed, as the class note requires. Room does not
         * fall back to destructive migration when one is missing — it throws at
         * open time — so an added table needs its own CREATE or every existing
         * install fails to launch.
         *
         * Only additive, so no data is rewritten.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
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
            }
        }
    }
}
