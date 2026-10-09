package dev.drosh.domain.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The provider catalog, as fetched from models.dev.
 *
 * Drosh used to ship a hardcoded list with exactly one entry in it
 * (`LlmProviderRepositoryImpl.BUILT_IN_PROVIDERS`). This replaces that with the
 * same source OpenCode reads: 225 providers and 8453 models, trimmed to the
 * fields Drosh actually uses.
 *
 * ## Why the trim exists
 *
 * The upstream document is 5.3 MB. Parsing it costs a transient heap spike on a
 * phone, and almost none of it is read — costs, families, descriptions, release
 * dates and open-weights flags never reach a request. The source folds the
 * document into these types as it parses and writes the result out at ~270 KB
 * compressed, so the spike is paid once and every later launch reads the small
 * file.
 *
 * ## Values are data, not policy
 *
 * [CatalogModel.reasoningEfforts] is the model's own declaration and is the
 * source of truth for what the user may pick. Which of those a protocol can
 * actually send is a different question, answered by
 * `dev.drosh.agent.provider.EffortMapper`.
 */
/**
 * A `${VAR}` placeholder in a catalog endpoint.
 *
 * Private to this file rather than on the class: the kotlinx plugin makes the
 * generated `serializer()` reach for a class's companion object, and anything
 * narrower than public on it breaks a caller in another module.
 */
private val ENDPOINT_PLACEHOLDER = Regex("""\$\{([A-Z0-9_]+)}""")

@Serializable
data class CatalogProvider(
    val id: String,
    val label: String,

    /** The models.dev package that speaks this endpoint, e.g. `@ai-sdk/openai-compatible`. */
    @SerialName("npm") val npm: String? = null,

    /** Env var name the provider documents for its key, e.g. `MINIMAX_API_KEY`. */
    val keyEnvName: String? = null,

    /**
     * The endpoint as the catalog states it, possibly containing `${VAR}`
     * placeholders — e.g.
     * `https://api.cloudflare.com/client/v4/accounts/${CLOUDFLARE_ACCOUNT_ID}/ai/v1`.
     *
     * Null for the 25 providers the catalog does not give an endpoint for; those
     * resolve against a local table of known defaults.
     */
    val apiTemplate: String? = null,

    /** Headers this provider expects beyond the auth header. */
    val extraHeaders: Map<String, String> = emptyMap(),

    /** Provider id → model, keyed by the model's catalog id. */
    val models: Map<String, CatalogModel> = emptyMap(),

    /**
     * Set when an API key plus a base URL cannot reach this provider.
     *
     * Keeps the row visible and self-explanatory rather than letting the user
     * paste a key into an endpoint that will never answer. AWS SigV4 and Google
     * service-account JWT flows are the cause; neither fits an API key.
     */
    val unsupportedReason: String? = null,
) {

    /**
     * The placeholders [apiTemplate] asks for, in order of appearance.
     *
     * Derived rather than stored so the two cannot drift: the settings screen
     * renders one field per entry here and the repository substitutes them back
     * into the template.
     */
    val configKeys: List<String>
        get() = ENDPOINT_PLACEHOLDER.findAll(apiTemplate.orEmpty())
            .map { it.groupValues[1] }
            .distinct()
            .toList()

    /** True when this provider cannot be used at all. */
    val isUnsupported: Boolean get() = unsupportedReason != null

}

/**
 * One model in the catalog.
 *
 * @param outputTokenLimit the declared ceiling on generated tokens. Anthropic's
 *        Messages API requires `max_tokens` on every request, so the agent loop
 *        reads this rather than inventing a number.
 * @param reasoningEfforts the effort values this model declares, in catalog
 *        order. Empty when it does no reasoning.
 */
@Serializable
data class CatalogModel(
    val id: String,
    val label: String,
    val outputTokenLimit: Int? = null,
    val reasoningEfforts: List<String> = emptyList(),
    val toolCall: Boolean = false,
    val attachment: Boolean = false,
    val temperature: Boolean = false,
    val reasoning: Boolean = false,
    val status: ModelStatus = ModelStatus.ACTIVE,
)

@Serializable
enum class ModelStatus {
    /** Usable; the catalog marks it live. */
    ACTIVE,

    /** Usable but subject to change; shown only on request. */
    BETA,

    /** Retained for reference; hidden from the picker. */
    DEPRECATED,

    /** Early access; hidden from the picker. */
    ALPHA,
}

/** What the catalog is doing, for the settings screen to render. */
sealed interface CatalogState {

    /** Nothing fetched yet. */
    data object Loading : CatalogState

    /** [providers] is ready to render. */
    data class Ready(val providers: List<CatalogProvider>) : CatalogState

    /**
     * The fetch failed.
     *
     * [retryable] is false when there is nothing to retry with — no network
     * permission, or a cached copy that could not be read either.
     */
    data class Failed(val message: String, val retryable: Boolean) : CatalogState
}

/**
 * Ids that are not rows in the catalog.
 *
 * The custom endpoint is stored rather than derived, so the UI needs a stable
 * name for it that no catalog provider can collide with.
 */
object ProviderCatalogIds {
    const val CUSTOM = "custom"
}

/**
 * The wire protocol a catalog entry speaks.
 *
 * Derived from the models.dev package name, which is the only field that
 * distinguishes, say, OpenRouter from every other OpenAI-compatible gateway —
 * both are `@ai-sdk/openai-compatible`-shaped on the wire to us but
 * [EffortMapper][dev.drosh.agent.provider.EffortMapper] needs the package name
 * to spell effort correctly.
 */
object CatalogProtocol {

    private const val ANTHROPIC = "@ai-sdk/anthropic"
    private const val GEMINI = "@ai-sdk/google"
    private const val OPENAI = "@ai-sdk/openai"

    /**
     * Packages that cannot be reached with an API key and a base URL.
     *
     * Amazon Bedrock requires SigV4 request signing; both Vertex variants
     * require a service-account JWT. Both are doable but neither fits the
     * "paste a key" model every other provider in the catalog uses.
     */
    private val UNSUPPORTED = mapOf(
        "@ai-sdk/amazon-bedrock" to "Needs AWS SigV4 signing, not an API key",
        "@ai-sdk/google-vertex" to "Needs a Google service-account key, not an API key",
        "@ai-sdk/google-vertex/anthropic" to "Needs a Google service-account key, not an API key",
    )

    /** Why this package cannot be used, or null when it can. */
    fun unsupportedReason(npm: String?): String? = npm?.let { UNSUPPORTED[it] }

    /**
     * The protocol for a catalog entry.
     *
     * Only three packages need naming. Everything else — including the 185 that
     * declare `@ai-sdk/openai-compatible` outright and the local gateways that
     * declare no package at all — speaks OpenAI Chat Completions, so the default
     * is not a fallback but the common case.
     */
    fun kindOf(npm: String?): ProviderKind = when (npm) {
        ANTHROPIC -> ProviderKind.ANTHROPIC
        GEMINI -> ProviderKind.GEMINI
        OPENAI -> ProviderKind.OPENAI_RESPONSES
        else -> ProviderKind.OPENAI_COMPAT
    }
}
