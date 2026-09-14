package com.iris.irisshell.data.di

import android.content.Context
import androidx.room.Room
import com.iris.irisshell.data.local.DroshDatabase
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
    ).build()
}
