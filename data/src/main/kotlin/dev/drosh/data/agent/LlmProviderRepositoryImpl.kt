package dev.drosh.data.agent

import android.content.SharedPreferences
import dev.drosh.data.di.SecurityModule.SecretPref
import dev.drosh.domain.agent.LlmCredential
import dev.drosh.domain.agent.LlmModel
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.LlmProviderRepository
import dev.drosh.domain.agent.ProviderKind
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
 * Provider catalog and credential storage.
 *
 * ## Why the key lives here and not in the UI
 *
 * The previous implementation held the API key in a `ViewModel` field. That put
 * a live credential in the UI layer, made it unrecoverable across process death,
 * and meant the key could not be revoked without navigating to a screen. Here it
 * is written once to `EncryptedSharedPreferences`, and the UI only ever holds a
 * provider id.
 *
 * ## Why nothing is read in the constructor
 *
 * `EncryptedSharedPreferences` decrypts on first access, so touching it during
 * construction would put that cost on whichever thread built the singleton. The
 * flows below read inside the collector and move to [Dispatchers.IO] with
 * `flowOn`, leaving the decision with the caller instead of hiding it in a
 * `lazy`.
 *
 * ## Why OpenRouter is the only built-in provider
 *
 * It speaks the OpenAI-compatible protocol, so it needs no adapter of its own —
 * adding it was a catalog entry, not a class. It also aggregates many models
 * behind one key, which suits an app whose premise is that the user brings
 * their own model. Custom base URLs come later, together with endpoint
 * validation, rather than as a free-text field with no way to test it.
 */
@Singleton
class LlmProviderRepositoryImpl @Inject constructor(
    @SecretPref private val prefs: SharedPreferences,
    private val httpClient: OkHttpClient,
    private val json: Json,
) : LlmProviderRepository {

    override fun observeProviders(): Flow<List<LlmProvider>> = flow { emit(BUILT_IN_PROVIDERS) }

    override fun observeCredentials(): Flow<Map<String, LlmCredential>> =
        flow { emit(readCredentials()) }.flowOn(Dispatchers.IO)

    override fun provider(id: String): LlmProvider? =
        BUILT_IN_PROVIDERS.firstOrNull { it.id == id }

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

    override suspend fun fetchModels(providerId: String, forceRefresh: Boolean): List<LlmModel> {
        // forceRefresh is accepted for interface compatibility. There is no cache
        // yet, so there is nothing to skip; adding one should honour it.
        val provider = provider(providerId) ?: return emptyList()
        val modelsUrl = provider.modelsUrl ?: return emptyList()
        val key = credential(providerId)?.apiKey

        val request = Request.Builder()
            .url(modelsUrl)
            .get()
            .apply {
                // OpenRouter's /models endpoint is public, but sending the key when
                // one is present keeps the result consistent with the user's plan
                // instead of silently falling back to the public list.
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

    override suspend fun setSelectedModel(providerId: String, modelId: String) =
        withContext(Dispatchers.IO) {
            prefs.edit()
                .putString(keyFor(SELECTED_MODEL_SUFFIX + providerId), modelId)
                .apply()
        }

    override suspend fun selectedModel(providerId: String): String? = withContext(Dispatchers.IO) {
        prefs.getString(keyFor(SELECTED_MODEL_SUFFIX + providerId), null)
    }

    // ── storage helpers ───────────────────────────────────────────────────

    private fun readCredentials(): Map<String, LlmCredential> = BUILT_IN_PROVIDERS
        .mapNotNull { provider ->
            prefs.getString(keyFor(provider.id), null)
                ?.takeIf { it.isNotBlank() }
                ?.let { provider.id to LlmCredential(provider.id, it) }
        }
        .toMap()

    private fun keyFor(suffix: String) = "llm_$suffix"

    companion object {
        private const val SELECTED_MODEL_SUFFIX = "model_"

        /**
         * The two attribution headers are OpenRouter's documented recommendations
         * for dashboard attribution. They are not authentication, which is why
         * they live in provider config rather than in the adapter.
         */
        val BUILT_IN_PROVIDERS = listOf(
            LlmProvider(
                id = "openrouter",
                label = "OpenRouter",
                kind = ProviderKind.OPENAI_COMPAT,
                baseUrl = "https://openrouter.ai/api/v1",
                modelsPath = "models",
                extraHeaders = mapOf(
                    "HTTP-Referer" to "https://github.com/mmuhofy/Drosh",
                    "X-Title" to "Drosh",
                ),
            ),
        )
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
