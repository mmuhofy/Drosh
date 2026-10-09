package dev.drosh.data.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.drosh.data.agent.ProviderCatalogRepository
import dev.drosh.data.agent.ProviderCatalogSource
import dev.drosh.domain.agent.LlmProviderRepository
import kotlinx.coroutines.CoroutineScope
import java.io.File
import javax.inject.Singleton

/**
 * Wiring for the provider catalog.
 *
 * No logic here, per AGENT.md — the seam between the Android-specific cache
 * location and the source that reads and writes it.
 */
@Module
@InstallIn(SingletonComponent::class)
object CatalogModule {

    /**
     * Where the trimmed catalog is cached.
     *
     * `cacheDir` rather than `filesDir`: the file is a cache by definition and may
     * be deleted by the system under pressure, which costs one refetch rather
     * than a broken app.
     */
    @Provides
    @Singleton
    fun provideCatalogCacheFile(@ApplicationContext context: Context): File =
        File(context.cacheDir, "models-catalog.json")

    @Provides
    @Singleton
    fun provideProviderCatalogRepository(
        source: ProviderCatalogSource,
        @ApplicationScope scope: CoroutineScope,
    ): ProviderCatalogRepository = ProviderCatalogRepository(source, scope).also {
        // Started here rather than from a screen: the catalog backs the chat
        // screen's provider lookup and the home screen's key check as well as
        // settings, and a first fetch landing between a tap and its effect is
        // exactly the "yükleniyor that never loads" this fixes.
        it.loadInBackground()
    }
}
