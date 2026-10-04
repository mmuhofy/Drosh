package dev.drosh.agent.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import dev.drosh.domain.agent.ChatAdapter
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
        // with JsonObjectBuilder and serialises it with toString() — setting it
        // here would look like a deliberate choice and change nothing on the wire.
    }
}

/**
 * Collects the protocol adapters.
 *
 * A multibinding, so a new protocol is one injectable class and nothing else —
 * `ProviderRegistry` needs no edit, and therefore cannot be left inconsistent
 * with what is actually available.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AdapterModule {

    @Multibinds
    abstract fun adapters(): Set<@JvmSuppressWildcards ChatAdapter>
}
