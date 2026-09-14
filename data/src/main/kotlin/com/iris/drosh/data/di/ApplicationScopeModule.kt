package com.iris.irisshell.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Application-wide singletons that don't fit elsewhere.
 *
 * @ApplicationScope — root SupervisorJob scope tied to the application
 *   process. Used by long-lived data flows (bootstrap log, session replay, etc.)
 *   that must outlive any single ViewModel.
 */
@Module
@InstallIn(SingletonComponent::class)
object ApplicationScopeModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
