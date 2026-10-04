package dev.drosh.domain.agent

import kotlinx.coroutines.flow.Flow

/**
 * A configured LLM endpoint.
 *
 * [kind] is the wire protocol, not the vendor. Several vendors speak
 * [ProviderKind.OPENAI_COMPAT] and are reached by pointing [baseUrl] at them —
 * adding OpenRouter or Groq costs one row here, not one adapter class. Gemini
 * is its own kind because its request and stream shapes genuinely differ.
 */
data class LlmProvider(
    val id: String,
    val label: String,
    val kind: ProviderKind,
    /** e.g. `https://openrouter.ai/api/v1` */
    val baseUrl: String,
    /** Appended to [baseUrl] for model discovery; null when unsupported. */
    val modelsPath: String? = null,
    /** Extra headers this provider expects beyond `Authorization`. */
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    val chatCompletionsUrl: String
        get() = "${baseUrl.trimEnd('/')}/chat/completions"

    val modelsUrl: String?
        get() = modelsPath?.let { "${baseUrl.trimEnd('/')}/${it.trimStart('/')}" }
}

enum class ProviderKind {
    /** OpenAI Chat Completions — OpenAI, OpenRouter, Groq, most local servers. */
    OPENAI_COMPAT,

    /** Google's generateContent wire format. */
    GEMINI,
}

/**
 * Credentials, as stored.
 *
 * The key is never exposed on the request path: the UI passes a [LlmProvider.id]
 * and the agent module resolves the key here at call time.
 */
data class LlmCredential(
    val providerId: String,
    val apiKey: String,
)

/** A model offered by a provider. */
data class LlmModel(
    val id: String,
    /** Provider-native display name when it has one. */
    val label: String? = null,
)

interface LlmProviderRepository {

    /** Built-in providers plus any the user added, in display order. */
    fun observeProviders(): Flow<List<LlmProvider>>

    fun observeCredentials(): Flow<Map<String, LlmCredential>>

    /**
     * Provider by id, or null. Synchronous: the catalog is configuration, not
     * storage, so an adapter resolving its endpoint mid-stream never blocks on IO.
     */
    fun provider(id: String): LlmProvider?

    /** Throws when there is no credential — the caller surfaces it as a setup prompt. */
    suspend fun credential(providerId: String): LlmCredential?

    /**
     * Fetch the provider's model list.
     *
     * @param forceRefresh skip the cache; the previous implementation fetched on
     *        every open of the settings surface
     */
    suspend fun fetchModels(providerId: String, forceRefresh: Boolean = false): List<LlmModel>

    /** Remember the user's model choice per provider. */
    suspend fun setSelectedModel(providerId: String, modelId: String)

    suspend fun selectedModel(providerId: String): String?
}
