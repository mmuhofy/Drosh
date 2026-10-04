package dev.drosh.agent.provider

import dev.drosh.domain.agent.ChatAdapter
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.ProviderKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps a provider's wire protocol to the adapter that speaks it.
 *
 * Adapters are injected rather than constructed here so Hilt owns their lifetime
 * and a test can swap one without touching this class.
 *
 * An unimplemented protocol fails loudly. Silently routing an unknown provider
 * to the OpenAI-compatible adapter would produce a request the provider rejects
 * in a way that looks like a bad API key.
 */
@Singleton
class ProviderRegistry @Inject constructor(
    private val openAiCompat: OpenAiCompatAdapter,
) {

    fun adapterFor(provider: LlmProvider): ChatAdapter = when (provider.kind) {
        ProviderKind.OPENAI_COMPAT -> openAiCompat
        ProviderKind.GEMINI -> throw UnsupportedOperationException(
            "Provider '${provider.label}' speaks ${ProviderKind.GEMINI}, which is not " +
                "implemented yet. OpenRouter and other OpenAI-compatible endpoints work.",
        )
    }
}
