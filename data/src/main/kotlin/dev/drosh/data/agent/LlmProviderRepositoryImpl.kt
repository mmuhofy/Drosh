package dev.drosh.data.agent

import android.content.SharedPreferences
import dev.drosh.data.di.SecurityModule.SecretPref
import dev.drosh.domain.agent.CatalogProtocol
import dev.drosh.domain.agent.CatalogProvider
import dev.drosh.domain.agent.CatalogState
import dev.drosh.domain.agent.ConfigField
import dev.drosh.domain.agent.LlmCredential
import dev.drosh.domain.agent.LlmModel
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.LlmProviderRepository
import dev.drosh.domain.agent.ModelStatus
import dev.drosh.domain.agent.ProviderKind
import dev.drosh.domain.agent.ReasoningEffort
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provider catalog, credential storage, and the user's per-model choices.
 *
 * ## Why the key lives here and not in the UI
 *
 * The previous implementation held the API key in a `ViewModel` field. That put
 * a live credential in the UI layer, made it unrecoverable across process death,
 * and meant the key could not be revoked without navigating to a screen. Here it
 * is written once to `EncryptedSharedPreferences`, and the UI only ever holds a
 * provider id.
 *
 * ## Where the providers come from now
 *
 * This used to hardcode a list with exactly one entry. It now resolves every
 * provider from the models.dev catalog (see [ProviderCatalogRepository]), which
 * is 225 providers rather than one and needs no code change to grow.
 *
 * ## Why nothing is read in the constructor
 *
 * `EncryptedSharedPreferences` decrypts on first access, so touching it during
 * construction would put that cost on whichever thread built the singleton. The
 * flows below move to [Dispatchers.IO] with `flowOn`, leaving the decision with
 * the caller instead of hiding it in a `lazy`.
 *
 * ## Endpoints the catalog does not state
 *
 * A provider's `api` field is either absent, a plain URL, or a template with
 * `${VAR}` placeholders. The third kind is resolved from values the user stores
 * here; the first kind asks for a base URL the same way the custom row does.
 * Only the placeholders the catalog actually names are asked for — nothing is
 * guessed, and a provider whose default endpoint Drosh does not know simply
 * asks for one.
 */
@Singleton
class LlmProviderRepositoryImpl @Inject constructor(
    @SecretPref private val prefs: SharedPreferences,
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val catalog: ProviderCatalogRepository,
) : LlmProviderRepository {

    /**
     * Bumped whenever a stored value the provider list depends on changes.
     *
     * Preferences are not observable, and [observeProviders] has to re-emit when
     * the user fills in a base URL a template needs. A revision counter is the
     * smallest thing that makes that happen.
     */
    private val revision = MutableStateFlow(0)

    /**
     * The last resolved provider list, reused until the catalog or a stored
     * value changes.
     *
     * [provider] is documented as synchronous and non-blocking, and resolving a
     * provider reads preferences. Caching keeps that promise: the recompute
     * happens once per change rather than once per lookup, and the agent loop —
     * which calls [provider] while a turn is being assembled — never pays for it.
     */
    private class Snapshot(
        val catalogState: CatalogState,
        val revision: Int,
        val providers: List<LlmProvider>,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    override fun observeProviders(): Flow<List<LlmProvider>> =
        combine(catalog.state, revision) { _, _ -> current() }
            .flowOn(Dispatchers.IO)

    override fun observeCredentials(): Flow<Map<String, LlmCredential>> =
        combine(catalog.state, revision) { state, _ -> knownIds(state) }
            .map { ids -> readCredentials(ids) }
            .flowOn(Dispatchers.IO)

    override fun provider(id: String): LlmProvider? {
        if (id == CUSTOM_PROVIDER_ID) return customProviderFromPrefs()
        return current().firstOrNull { it.id == id }
    }

    override suspend fun credential(providerId: String): LlmCredential? =
        withContext(Dispatchers.IO) {
            prefs.getString(keyFor(providerId), null)
                ?.takeIf { it.isNotBlank() }
                ?.let { LlmCredential(providerId = providerId, apiKey = it) }
        }

    override suspend fun setCredential(providerId: String, apiKey: String) = withContext(Dispatchers.IO) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            // Blank clears rather than storing an empty key: saving an emptied field
            // is how a user expects to remove a credential, and a stored "" would
            // read as "no key" at one end of the app and as a set key at the other.
            prefs.edit().remove(keyFor(providerId)).apply()
        } else {
            prefs.edit().putString(keyFor(providerId), trimmed).apply()
        }
    }

    override suspend fun clearCredential(providerId: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(keyFor(providerId)).apply()
    }

    override suspend fun setConfigValue(providerId: String, key: String, value: String) {
        withContext(Dispatchers.IO) {
            val storageKey = configKey(providerId, key)
            val trimmed = value.trim()
            if (trimmed.isEmpty()) {
                prefs.edit().remove(storageKey).apply()
            } else {
                prefs.edit().putString(storageKey, trimmed).apply()
            }
        }
        // Outside the IO block, so the node stays responsive if the write throws.
        revision.value += 1
    }

    override suspend fun setCustomBaseUrl(baseUrl: String) {
        withContext(Dispatchers.IO) {
            val trimmed = baseUrl.trim().trimEnd('/')
            if (trimmed.isEmpty()) {
                prefs.edit().remove(CUSTOM_BASE_URL_KEY).apply()
            } else {
                prefs.edit().putString(CUSTOM_BASE_URL_KEY, trimmed).apply()
            }
        }
        revision.value += 1
    }

    /**
     * The model list comes from the catalog, so this is instant and works
     * offline.
     *
     * The previous implementation fetched `GET /models` from the provider, which
     * meant an empty list whenever the endpoint was slow and a spinner on every
     * visit to settings. Endpoints that serve their own `/models` — a local
     * server, or the custom row — can still be queried, but only when the user
     * asks.
     */
    override suspend fun fetchModels(providerId: String, forceRefresh: Boolean): List<LlmModel> {
        if (providerId == CUSTOM_PROVIDER_ID) {
            if (!forceRefresh) return emptyList()
            val custom = customProviderFromPrefs() ?: return emptyList()
            return fetchModelsFromEndpoint(custom)
        }

        val catalogProvider = catalog.provider(providerId) ?: return emptyList()
        return catalogProvider.models.values
            // Alpha and deprecated models are still in the document; showing them
            // alongside current ones makes a 199-model list into a guessing game.
            .filter { it.status == ModelStatus.ACTIVE || it.status == ModelStatus.BETA }
            .sortedBy { it.label.lowercase() }
            .map { LlmModel(id = it.id, label = it.label, outputTokenLimit = it.outputTokenLimit) }
    }

    override suspend fun setSelectedModel(providerId: String, modelId: String) =
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(keyFor(SELECTED_MODEL_SUFFIX + providerId), modelId)
                .apply()
        }

    override suspend fun selectedModel(providerId: String): String? = withContext(Dispatchers.IO) {
        prefs.getString(keyFor(SELECTED_MODEL_SUFFIX + providerId), null)
    }

    override suspend fun setSelectedProvider(providerId: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(keyFor(SELECTED_PROVIDER_KEY), providerId).apply()
    }

    override suspend fun selectedProvider(): String? = withContext(Dispatchers.IO) {
        prefs.getString(keyFor(SELECTED_PROVIDER_KEY), null)
    }

    /**
     * The effort values this model may be run at.
     *
     * What the model declares, narrowed to what its protocol can send. The
     * catalog is the source of truth for the values; [ReasoningEffort] for the
     * narrowing, so the selector and the request body cannot disagree.
     */
    override fun reasoningEfforts(providerId: String, modelId: String): List<String> {
        val provider = current().firstOrNull { it.id == providerId } ?: return emptyList()
        val model = catalog.model(providerId, modelId) ?: return emptyList()
        return ReasoningEffort.allowed(provider, modelId, model.reasoningEfforts)
    }

    override fun observeCatalogState(): Flow<CatalogState> = catalog.state

    override suspend fun refreshCatalog() = catalog.refresh()

    override suspend fun setReasoningEffort(providerId: String, modelId: String, effort: String?) {
        withContext(Dispatchers.IO) {
            val storageKey = effortKey(providerId, modelId)
            if (effort.isNullOrBlank()) {
                prefs.edit().remove(storageKey).apply()
            } else {
                prefs.edit().putString(storageKey, effort).apply()
            }
        }
        revision.value += 1
    }

    override suspend fun reasoningEffort(providerId: String, modelId: String): String? =
        withContext(Dispatchers.IO) {
            prefs.getString(effortKey(providerId, modelId), null)
        }

    override fun outputTokenLimit(providerId: String, modelId: String): Int? =
        catalog.model(providerId, modelId)?.outputTokenLimit

    // ── resolution ─────────────────────────────────────────────────────────

    /** The resolved provider list, cached until the catalog or a value changes. */
    private fun current(): List<LlmProvider> {
        val state = catalog.state.value
        val rev = revision.value
        snapshot?.takeIf { it.catalogState === state && it.revision == rev }?.let { return it.providers }

        val resolved = (state as? CatalogState.Ready)?.providers
            ?.map { toProvider(it) }
            .orEmpty()
            .plus(listOfNotNull(customProviderFromPrefs()))

        snapshot = Snapshot(state, rev, resolved)
        return resolved
    }

    /** Provider ids the catalog offers, for the credential flow to iterate. */
    private fun knownIds(state: CatalogState): List<String> =
        (state as? CatalogState.Ready)?.providers?.map { it.id }.orEmpty() + CUSTOM_PROVIDER_ID

    private fun toProvider(catalogProvider: CatalogProvider): LlmProvider {
        val fields = configFieldsFor(catalogProvider)
        val baseUrl = resolveBaseUrl(catalogProvider, fields)

        return LlmProvider(
            id = catalogProvider.id,
            label = catalogProvider.label,
            kind = CatalogProtocol.kindOf(catalogProvider.npm),
            baseUrl = baseUrl.orEmpty(),
            modelsPath = MODELS_PATH,
            npm = catalogProvider.npm,
            keyEnvName = catalogProvider.keyEnvName,
            extraHeaders = extraHeadersFor(catalogProvider.id),
            configFields = fields,
            unsupportedReason = catalogProvider.unsupportedReason
                ?: missingValuesReason(fields, baseUrl),
        )
    }

    /**
     * What this provider needs from the user beyond an API key.
     *
     * A provider with no `api` in the catalog asks for a base URL; one with
     * placeholders asks for each placeholder it names. Anything else asks for
     * nothing, which is the case for 200 of the 225.
     */
    private fun configFieldsFor(catalogProvider: CatalogProvider): List<ConfigField> {
        val template = catalogProvider.apiTemplate?.takeIf { it.isNotBlank() }
            ?: return listOf(
                ConfigField(
                    key = BASE_URL_KEY,
                    label = "Base URL",
                    value = stored(configKey(catalogProvider.id, BASE_URL_KEY)).orEmpty(),
                ),
            )

        return catalogProvider.configKeys.map { key ->
            ConfigField(
                key = key,
                label = labelForKey(key),
                value = stored(configKey(catalogProvider.id, key)).orEmpty(),
            )
        }
    }

    /**
     * Substitute stored values into the endpoint, or null when it cannot be
     * resolved.
     *
     * A template still containing `${...}` means a value is missing and counts as
     * unresolved rather than being passed on literally: a URL with a placeholder
     * in it is not an endpoint.
     */
    private fun resolveBaseUrl(
        catalogProvider: CatalogProvider,
        fields: List<ConfigField>,
    ): String? {
        val values = fields.associate { it.key to it.value }

        // A provider with no catalog endpoint asks for one rather than having a
        // template resolved.
        val template = catalogProvider.apiTemplate?.takeIf { it.isNotBlank() }
            ?: return values[BASE_URL_KEY]?.takeIf { it.isNotBlank() }

        return catalogProvider.copy(apiTemplate = template)
            .resolveEndpoint(values)
            ?.takeIf { it.isNotBlank() && !it.contains(UNRESOLVED_MARKER) }
    }

    /** Why the endpoint is unusable, or null when it is fine. */
    private fun missingValuesReason(fields: List<ConfigField>, baseUrl: String?): String? {
        if (baseUrl != null) return null
        val missing = fields.filter { it.value.isBlank() }.map { it.label }
        return if (missing.isEmpty()) null else "Needs ${missing.joinToString(", ")}"
    }

    /** Headers beyond the auth header, for the providers that document them. */
    private fun extraHeadersFor(providerId: String): Map<String, String> = when (providerId) {
        // OpenRouter's documented recommendations for dashboard attribution. Not
        // authentication, which is why they live here rather than in an adapter.
        "openrouter" -> mapOf(
            "HTTP-Referer" to "https://github.com/mmuhofy/Drosh",
            "X-Title" to "Drosh",
        )

        else -> emptyMap()
    }

    /**
     * The escape hatch for anything the 225 do not cover.
     *
     * Ollama, vLLM, LM Studio and any gateway missing from the catalog all land
     * here: a base URL the user types and a model id they type. It is the only
     * provider with no catalog entry behind it, which is why it is stored rather
     * than derived.
     */
    private fun customProviderFromPrefs(): LlmProvider? {
        val baseUrl = stored(CUSTOM_BASE_URL_KEY)?.takeIf { it.isNotBlank() } ?: return null
        return LlmProvider(
            id = CUSTOM_PROVIDER_ID,
            label = "Custom endpoint",
            kind = ProviderKind.OPENAI_COMPAT,
            baseUrl = baseUrl,
            npm = "@ai-sdk/openai-compatible",
        )
    }

    // ── endpoint model listing ─────────────────────────────────────────────

    /**
     * Ask an endpoint for its own model list.
     *
     * Only worth calling for an endpoint whose `/models` is live and meaningful —
     * a local server, or the custom row — because the catalog already answers
     * this for everything else without a request.
     */
    private suspend fun fetchModelsFromEndpoint(provider: LlmProvider): List<LlmModel> {
        val url = provider.modelsUrl ?: return emptyList()
        val key = credential(provider.id)?.apiKey

        val request = Request.Builder()
            .url(url)
            .get()
            .apply {
                if (!key.isNullOrBlank()) header("Authorization", "Bearer $key")
                provider.extraHeaders.forEach { (name, value) -> header(name, value) }
            }
            .build()

        val body = httpClient.newCall(request).awaitBody() ?: return emptyList()

        return runCatching {
            val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
            data.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                LlmModel(id = id, label = obj["name"]?.jsonPrimitive?.content)
            }
        }.getOrElse { emptyList() }
    }

    // ── storage helpers ────────────────────────────────────────────────────

    private fun readCredentials(providerIds: List<String>): Map<String, LlmCredential> =
        providerIds.mapNotNull { id ->
            prefs.getString(keyFor(id), null)
                ?.takeIf { it.isNotBlank() }
                ?.let { id to LlmCredential(id, it) }
        }
            .toMap()

    private fun stored(storageKey: String): String? = prefs.getString(storageKey, null)
        ?.takeIf { it.isNotBlank() }

    private fun keyFor(suffix: String) = "llm_$suffix"

    private fun configKey(providerId: String, key: String) = "llm_cfg_${providerId}_$key"

    private fun effortKey(providerId: String, modelId: String) = "llm_effort_${providerId}_$modelId"

    private companion object {
        const val SELECTED_MODEL_SUFFIX = "model_"

        /** Single key: which of the 225 the user picked last. */
        const val SELECTED_PROVIDER_KEY = "selected_provider"

        /** Every catalog provider lists its models at this path. */
        const val MODELS_PATH = "models"

        const val CUSTOM_PROVIDER_ID = "custom"

        /** The config key a provider with no catalog endpoint asks for. */
        const val BASE_URL_KEY = "base_url"

        const val CUSTOM_BASE_URL_KEY = "llm_custom_base_url"

        /** What an unresolved `${VAR}` leaves behind. */
        const val UNRESOLVED_MARKER = "\${"

        /** `CLOUDFLARE_ACCOUNT_ID` → "Cloudflare Account Id". */
        fun labelForKey(key: String): String = key.split('_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { it.uppercase() }
            }
    }
}

/**
 * Await a GET and return its body, or null on any failure.
 *
 * `enqueue` rather than `execute`, so this does not block whichever thread
 * called it. Failures become null instead of an exception: a model list that
 * cannot be fetched is a settings screen with nothing in it, not a crash, and
 * the caller has no useful recovery to write anyway.
 */
private suspend fun Call.awaitBody(): String? = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeOnce(null)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resumeOnce(response.use { if (it.isSuccessful) it.body.string() else null })
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}

/**
 * Resume exactly once.
 *
 * OkHttp can report both `onResponse` and `onFailure` for a single call under
 * some cancellation races, and a double resume throws out of a callback thread
 * where the stack trace points nowhere useful.
 */
private fun <T> CancellableContinuation<T>.resumeOnce(value: T) {
    if (isActive) resumeWith(Result.success(value))
}
