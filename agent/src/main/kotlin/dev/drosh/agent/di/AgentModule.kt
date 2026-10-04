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
        // Tool arguments are parsed from raw JSON text once a stream completes, so
        // lenient quoting is never wanted.
        //
        // `explicitNulls` is deliberately left at its default. It only affects
        // encoding of annotated classes, and the adapter builds its request body
        // with JsonObjectBuilder and serialises that with toString() — so setting
        // it here would look meaningful and change nothing on the wire.
    }
}
