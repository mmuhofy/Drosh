package dev.drosh.agent.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

/**
 * Wiring for the agent module.
 *
 * No logic here — see the layout note in this module's build file.
 */
@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        // Provider payloads carry fields we do not model, and they gain fields
        // without notice; an unknown key must not fail an entire run.
        ignoreUnknownKeys = true
        // Tool arguments are parsed from a raw JSON string once the stream
        // completes, so lenient quoting is never wanted.
        isLenient = false
        explicitNulls = false
    }
}
