package dev.drosh.agent.provider

import dev.drosh.domain.agent.ChatAdapter
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.ProviderKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Maps a provider's wire protocol to the adapter that speaks it.
 *
 * Adapters arrive through a multibinding rather than as constructor parameters,
 * which buys two things. Adding a protocol is one class and nothing else — no
 * edit to this file, and therefore no chance of adding the adapter but forgetting
 * to register it. And the registry can be constructed with a stand-in adapter in
 * a test, which a hard-coded `OpenAiCompatAdapter` parameter made impossible.
 *
 * An unimplemented protocol fails loudly. Silently routing an unknown provider to
 * the OpenAI-compatible adapter would produce a request the provider rejects in a
 * way that looks like a bad API key.
 */
@Singleton
class ProviderRegistry @Inject constructor(
    adapters: Set<@JvmSuppressWildcards ChatAdapter>,
) {

    private val byKind: Map<ProviderKind, ChatAdapter> =
        adapters.associateBy { it.kind }

    fun adapterFor(provider: LlmProvider): ChatAdapter = byKind[provider.kind]
        ?: throw UnsupportedOperationException(
            "Provider '${provider.label}' speaks ${provider.kind}, which no adapter implements. " +
                "Adapters registered: ${byKind.keys.joinToString(", ").ifEmpty { "none" }}.",
        )

    internal fun supportedKinds(): Set<ProviderKind> = byKind.keys
}
