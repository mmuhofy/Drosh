package dev.drosh.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.drosh.data.agent.AgentChatDao
import dev.drosh.data.agent.AgentChatEntity
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
        AgentChatEntity::class,
        WorkspaceEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class DroshDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun agentChatDao(): AgentChatDao
    abstract fun workspaceDao(): WorkspaceDao

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

        /**
         * 2 → 3: workspaces, and the grouping column on sessions.
         *
         * ## Why the sessions table is rebuilt rather than altered
         *
         * `workspace_id` needs a foreign key to `workspaces`, and SQLite cannot
         * add a constraint to an existing table — `ALTER TABLE ... ADD
         * CONSTRAINT` does not exist. Room validates the *actual* schema against
         * the entities at open time and reports a foreign key that is declared in
         * Kotlin but absent in SQLite as an illegal state, so a plain
         * `ADD COLUMN` would take every existing install down at launch.
         *
         * The rebuild is the standard twelve-step dance: create the new shape,
         * copy, drop, rename, recreate the index. The column is appended last so
         * the `INSERT ... SELECT` can name the original six and read NULL for it,
         * which is the correct value — every session predates grouping.
         *
         * ## Why not ON DELETE CASCADE
         *
         * Deleting a project must not delete the shells worked on in it. The
         * sessions are ungrouped instead: see
         * [dev.drosh.data.workspace.WorkspaceRepositoryImpl.delete], which also
         * writes the nulls explicitly because that action only fires when the
         * foreign_keys pragma happens to be on.
         *
         * ## UNTESTED — verify before use
         *
         * Not exercised against a real v2 database. `data/schemas/` has never
         * been committed in this project, so there is no exported schema for
         * Room's `MigrationTestHelper` to build a v2 fixture from and no
         * compile-time cross-check of this SQL against version 2 — the two checks
         * that would catch a mismatch here both need those files.
         *
         * The SQL was written against the entity shape by hand, and Room does
         * validate the *resulting* schema at open time on the device, so a
         * mismatch is a launch-time crash and not silent corruption. But the
         * first real upgrade from a v2 install is still unproven. Test it on a
         * device that has run an older build, not on a fresh install — a fresh
         * install creates v3 directly and never runs this at all.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
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
            }
        }

        /** Every migration, in order. Registered in `DatabaseModule`. */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}