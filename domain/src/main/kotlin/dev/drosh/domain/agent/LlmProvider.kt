package dev.drosh.domain.agent

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
/**
 * A configured LLM endpoint.
 *
 * [kind] is the wire protocol, not the vendor. Several vendors speak
 * [ProviderKind.OPENAI_COMPAT] and are reached by pointing [baseUrl] at them —
 * adding OpenRouter or Groq costs one row here, not one adapter class. Gemini,
 * Anthropic and OpenAI's Responses API are their own kinds because their
 * request and stream shapes genuinely differ.
 *
 * ## Four protocols, not four vendors
 *
 * Drosh's catalog declares 225 providers. Only four wire protocols are needed
 * to reach 222 of them: the rest is data. See `agent/provider/` for the
 * adapters and `EffortMapper` for how reasoning effort is spelled per protocol.
 *
 * ## The base URL is already resolved
 *
 * Some catalog entries carry a templated endpoint, e.g.
 * `https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID}/ai/v1`.
 * The repository substitutes [ConfigField] values and hands back a concrete
 * [baseUrl], so no adapter ever has to know a placeholder exists.
 */
data class LlmProvider(
    val id: String,
    val label: String,
    val kind: ProviderKind,
    /** e.g. `https://openrouter.ai/api/v1` — concrete, placeholders resolved. */
    val baseUrl: String,
    /** Appended to [baseUrl] for model discovery; null when unsupported. */
    val modelsPath: String? = null,
    /** Extra headers this provider expects beyond `Authorization`. */
    val extraHeaders: Map<String, String> = emptyMap(),
    /**
     * The models.dev package that speaks this endpoint, e.g.
     * `@ai-sdk/openai-compatible`.
     *
     * Not decoration: [EffortMapper] keys its wire shape off this, not off
     * [kind]. Two providers can share a kind and still spell effort differently
     * — OpenRouter wants `reasoning: {effort}` where every other
     * OpenAI-compatible gateway wants `reasoning_effort`.
     */
    val npm: String? = null,
    /** Env var name shown on the key field, e.g. `MINIMAX_API_KEY`. */
    val keyEnvName: String? = null,
    /**
     * Merged into the request body after the adapter builds its own fields.
     *
     * An escape hatch, not a preference: a catalog entry that has never been
     * exercised may reject a field the adapter sends unconditionally (currently
     * `stream_options`, which 207 providers have not been asked about).
     */
    val extraBody: JsonObject = JsonObject(emptyMap()),
    /** Appended to the request URL as a query string. */
    val extraQuery: Map<String, String> = emptyMap(),
    /**
     * Non-secret values this provider needs beyond the API key.
     *
     * Secrets stay in [LlmCredential]. What lands here — an Azure resource
     * name, a Cloudflare account id — is not sensitive, and putting it on the
     * provider keeps the credential surface unchanged.
     */
    val configFields: List<ConfigField> = emptyList(),
    /**
     * Set when an API key plus a base URL cannot reach this provider.
     *
     * Non-null keeps the row visible and explains itself rather than letting
     * the user type a key into an endpoint that will never answer. SigV4 and
     * service-account JWT flows are the cause; neither fits an API key.
     */
    val unsupportedReason: String? = null,
) {
    val chatCompletionsUrl: String
        get() = "${baseUrl.trimEnd('/')}/chat/completions"

    val modelsUrl: String?
        get() = modelsPath?.let { "${baseUrl.trimEnd('/')}/${it.trimStart('/')}" }

    /** True when the user has supplied every value [configFields] asks for. */
    val isFullyConfigured: Boolean
        get() = configFields.all { it.value.isNotBlank() }
}

/**
 * One non-secret value a provider asks for besides the API key.
 *
 * @param key the placeholder name it fills, e.g. `CLOUDFLARE_ACCOUNT_ID`
 * @param label what the settings screen shows; derived from [key] by default
 */
data class ConfigField(
    val key: String,
    val label: String,
    val value: String = "",
)

enum class ProviderKind {
    /** OpenAI Chat Completions — OpenAI, OpenRouter, Groq, most local servers. */
    OPENAI_COMPAT,

    /** Google's generateContent wire format. */
    GEMINI,

    /** Anthropic's Messages wire format. */
    ANTHROPIC,

    /** OpenAI's Responses wire format, which is not Chat Completions. */
    OPENAI_RESPONSES,
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

/**
 * A model offered by a provider.
 *
 * @param outputTokenLimit the catalog's declared ceiling on generated tokens,
 *        which the Anthropic Messages API requires on every request and which
 *        nothing else in Drosh was previously reading
 */
data class LlmModel(
    val id: String,
    /** Provider-native display name when it has one. */
    val label: String? = null,
    /** Declared maximum output tokens; null when the catalog does not say. */
    val outputTokenLimit: Int? = null,
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
     * Store a credential.
     *
     * On the domain interface rather than only on the implementation: the settings
     * sheet has to write the key, and it can only see `:domain`.
     *
     * @param apiKey trimmed; blank removes the credential, so clearing the field
     *        and saving is not a special case at the call site
     */
    suspend fun setCredential(providerId: String, apiKey: String)

    /** Remove a stored credential. */
    suspend fun clearCredential(providerId: String)

    /**
     * Store the base URL for the custom OpenAI-compatible provider.
     *
     * On the interface rather than only on the implementation because the
     * settings sheet writes it and can only see `:domain`. Blank removes the row.
     */
    suspend fun setCustomBaseUrl(baseUrl: String)

    /**
     * Store one non-secret config value.
     *
     * Blank removes it, matching [setCredential], so an emptied field is not a
     * special case at the call site either. Removing a value that
     * [LlmProvider.baseUrl] was templated from leaves the provider unconfigured
     * rather than pointing it at a URL containing a literal `${...}`.
     */
    suspend fun setConfigValue(providerId: String, key: String, value: String)

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

    /**
     * The provider the user last chose, or null before they choose one.
     *
     * Needed now that there are 225: "the provider with a key stored" is no
     * longer a usable answer, and the chat screen has to know which one to run
     * without asking every time.
     */
    suspend fun setSelectedProvider(providerId: String)

    suspend fun selectedProvider(): String?

    /**
     * What the catalog fetch is doing.
     *
     * Emitted rather than held so the settings screen can render its own empty
     * state: a first launch has nothing to show until the fetch completes, and a
     * failed one needs a retry affordance rather than an empty list.
     */
    fun observeCatalogState(): Flow<CatalogState>

    /** Re-fetch the catalog, which the settings screen calls on a retry. */
    suspend fun refreshCatalog()

    /**
     * Reasoning-effort values the selected model actually declares, in catalog
     * order.
     *
     * Empty when the model does no reasoning or the catalog says nothing. The
     * list is the raw catalog data; `ReasoningEffort` narrows it per protocol,
     * and the settings sheet shows what survives that.
     */
    fun reasoningEfforts(providerId: String, modelId: String): List<String>

    /** Remember the effort choice for one model, or null to clear it. */
    suspend fun setReasoningEffort(providerId: String, modelId: String, effort: String?)

    /** The stored effort for one model, or null when the user never chose. */
    suspend fun reasoningEffort(providerId: String, modelId: String): String?

    /**
     * The model's declared output-token ceiling, or null.
     *
     * Synchronous like [provider]: the catalog is configuration, so a turn
     * assembling its request never blocks on IO to learn it. Anthropic requires
     * `max_tokens`, so the loop reads this rather than inventing a number.
     */
    fun outputTokenLimit(providerId: String, modelId: String): Int?
}
