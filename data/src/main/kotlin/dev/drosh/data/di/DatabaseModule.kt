package dev.drosh.data.di

import android.content.Context
import androidx.room.Room
import dev.drosh.data.agent.AgentChatDao
import dev.drosh.data.local.DroshDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the Room [DroshDatabase] instance.
 *
 * Database file lives at the standard `databases/irisshell.db` path
 * inside the app's no-backup-data directory; the framework guarantees
 * it's excluded from backup for privacy reasons.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDroshDatabase(
        @ApplicationContext context: Context,
    ): DroshDatabase = Room.databaseBuilder(
        context,
        DroshDatabase::class.java,
        DroshDatabase.DATABASE_NAME,
    )
        // Every version bump must add its migration here. Room does not fall back
        // to a destructive migration or to dropping the table — it throws at open
        // time — so an unregistered migration takes every existing install down
        // with it rather than degrading quietly.
        .addMigrations(DroshDatabase.MIGRATION_1_2)
        .build()

    /**
     * Agent chats.
     *
     * From the same database rather than a second one: the two record sets are
     * read together on the sessions screen, and a second connection would mean a
     * second WAL and a second thing to migrate.
     */
    @Provides
    fun provideAgentChatDao(database: DroshDatabase): AgentChatDao = database.agentChatDao()
}
