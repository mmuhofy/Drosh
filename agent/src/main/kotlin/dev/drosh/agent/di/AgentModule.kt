package dev.drosh.agent.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import dev.drosh.agent.provider.AnthropicAdapter
import dev.drosh.agent.provider.GeminiAdapter
import dev.drosh.agent.provider.OpenAiCompatAdapter
import dev.drosh.agent.provider.OpenAiResponsesAdapter
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
 * A multibinding, so `ProviderRegistry` needs no edit when a protocol arrives
 * and therefore cannot be left inconsistent with what is actually available.
 *
 * The `@Multibinds` declaration is only the *set*; every adapter still needs its
 * own `@Binds @IntoSet` below. `@Multibinds` with no contributions is a valid,
 * empty set rather than a compile error — which is why this shipped looking
 * correct and then failed at the first run with "no adapter implements",
 * naming the symptom rather than the missing annotation.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AdapterModule {

    @Binds
    @IntoSet
    abstract fun bindOpenAiCompatAdapter(impl: OpenAiCompatAdapter): ChatAdapter

    /**
     * Anthropic's Messages API — `anthropic`, `minimax` and the other
     * Anthropic-shaped gateways in the catalog.
     */
    @Binds
    @IntoSet
    abstract fun bindAnthropicAdapter(impl: AnthropicAdapter): ChatAdapter

    /** Google's `generateContent` — one provider, `google`. */
    @Binds
    @IntoSet
    abstract fun bindGeminiAdapter(impl: GeminiAdapter): ChatAdapter

    /**
     * OpenAI's Responses API — `openai`, `meta`, `perplexity-agent`, `infer`,
     * `neosmith`, `vivgrid`.
     */
    @Binds
    @IntoSet
    abstract fun bindOpenAiResponsesAdapter(impl: OpenAiResponsesAdapter): ChatAdapter

    @Multibinds
    abstract fun adapters(): Set<@JvmSuppressWildcards ChatAdapter>
}
