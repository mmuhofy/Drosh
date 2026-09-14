package com.iris.irisshell.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.iris.irisshell.data.session.SessionDao
import com.iris.irisshell.data.session.SessionEntity

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
    entities = [SessionEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class IrisDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        const val DATABASE_NAME = "irisshell.db"
    }
}
