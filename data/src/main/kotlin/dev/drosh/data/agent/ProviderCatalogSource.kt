package dev.drosh.data.agent

import dev.drosh.domain.agent.CatalogModel
import dev.drosh.domain.agent.CatalogProtocol
import dev.drosh.domain.agent.CatalogProvider
import dev.drosh.domain.agent.ModelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches and trims the models.dev catalog.
 *
 * Inspired by: github.com/anomalyco/opencode — packages/core/src/models-dev.ts
 * Adapted for Drosh — dev.drosh
 *
 * ## Why the document is trimmed on the way in
 *
 * The upstream `api.json` is 5.3 MB. Almost none of it reaches a request: costs,
 * families, descriptions, release dates and weights flags are never sent, and
 * holding the whole document costs a transient heap spike that a phone notices.
 * [fold] walks the parsed document keeping only what Drosh reads, so the large
 * DOM is garbage-collectable while the small one is being built and what stays
 * behind is ~3.4 MB of plain JSON.
 *
 * ## Why the trimmed copy is cached
 *
 * OpenCode refreshes every 60 minutes and serves from disk in between. Drosh
 * ships no bundled snapshot — a remote fetch was the explicit choice — so this
 * cache is the only thing standing between a second launch and another 5.3 MB
 * download. It is written on every successful fetch and read on every start.
 */
@Singleton
class ProviderCatalogSource @Inject constructor(
    private val cacheFile: File,
) {

    /**
     * Not the shared `NetworkModule` client. That one is deliberately built for
     * short decorative lookups — 5s timeouts — and a 5.3 MB download over a slow
     * connection would be cut off by it.
     */
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val providersSerializer = ListSerializer(CatalogProvider.serializer())

    /** The catalog from disk, or null when there is nothing usable there. */
    suspend fun readCache(): List<CatalogProvider>? = withContext(Dispatchers.IO) {
        if (!cacheFile.isFile) return@withContext null
        runCatching {
            json.decodeFromString(providersSerializer, cacheFile.readText())
        }.getOrNull()
    }

    /** Fetch, trim, and write the trimmed copy to the cache. */
    suspend fun fetch(): List<CatalogProvider> = withContext(Dispatchers.IO) {
        val text = download()

        // Folded before anything else touches the document, so the large DOM is
        // garbage-collectable as the small one is built.
        val trimmed = fold(text)

        // Written before returning: a fetch that succeeded but was not persisted
        // would be repeated on the next launch for no reason.
        writeCache(trimmed)
        trimmed
    }

    private fun download(): String {
        val request = Request.Builder()
            .url(CATALOG_URL)
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Catalog endpoint returned HTTP ${response.code}")
            }
            return response.body.string()
        }
    }

    /** Write the trimmed catalog, treating a failure to persist as non-fatal. */
    private fun writeCache(providers: List<CatalogProvider>) {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            // A partially written file would be read back as a truncated catalog,
            // so the temporary file is renamed into place only once complete.
            val temp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            temp.writeText(json.encodeToString(providersSerializer, providers))
            if (!temp.renameTo(cacheFile)) {
                temp.delete()
                throw IOException("Could not replace the catalog cache")
            }
        }
    }

    // ── the trim ───────────────────────────────────────────────────────────

    /**
     * Reduce the upstream document to the fields Drosh reads.
     *
     * Tolerant by design: a provider whose shape surprises this walk is skipped
     * rather than failing the whole fetch. One malformed entry out of 225 should
     * cost that entry, not the catalog.
     */
    internal fun fold(document: String): List<CatalogProvider> {
        val root = runCatching { json.parseToJsonElement(document) }.getOrNull() as? JsonObject
            ?: return emptyList()

        return root.entries.mapNotNull { (id, raw) ->
            runCatching { foldProvider(id, raw as? JsonObject ?: return@mapNotNull null) }.getOrNull()
        }
    }

    private fun foldProvider(id: String, body: JsonObject): CatalogProvider? {
        val label = body["name"]?.jsonPrimitive?.contentOrNull ?: id
        val npm = body["npm"]?.jsonPrimitive?.contentOrNull
        val api = body["api"]?.jsonPrimitive?.contentOrNull
        val env = body["env"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

        val models = body["models"]?.jsonObject?.entries?.mapNotNull { (modelId, raw) ->
            runCatching { foldModel(modelId, raw as? JsonObject ?: return@mapNotNull null) }.getOrNull()
        }?.toMap().orEmpty()

        // A provider with no models has nothing to offer and no reason to appear.
        if (models.isEmpty()) return null

        return CatalogProvider(
            id = id,
            label = label,
            npm = npm,
            // The first documented env var names the key field the user fills in.
            keyEnvName = env.firstOrNull(),
            apiTemplate = api,
            models = models,
            unsupportedReason = CatalogProtocol.unsupportedReason(npm),
        )
    }

    private fun foldModel(id: String, body: JsonObject): CatalogModel? {
        val label = body["name"]?.jsonPrimitive?.contentOrNull ?: return null

        return CatalogModel(
            id = id,
            label = label,
            outputTokenLimit = body["limit"]?.jsonObject?.get("output")?.jsonPrimitive
                ?.contentOrNull?.toIntOrNull(),
            // The model's own declaration, and the source of truth for the picker.
            reasoningEfforts = reasoningEfforts(body),
            toolCall = body["tool_call"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            attachment = body["attachment"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            temperature = body["temperature"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            reasoning = body["reasoning"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            status = status(body["status"]?.jsonPrimitive?.contentOrNull),
        )
    }

    /**
     * The effort values a model declares, flattened across its effort options.
     *
     * A null entry in `values` is how the catalog writes "no reasoning"; it maps
     * to the literal `"none"`, which is what every protocol's wire takes.
     */
    private fun reasoningEfforts(body: JsonObject): List<String> {
        val options = body["reasoning_options"]?.jsonArray ?: return emptyList()
        return options.flatMap { option ->
            val obj = option as? JsonObject ?: return@flatMap emptyList()
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "effort") return@flatMap emptyList()
            val values = obj["values"]?.jsonArray ?: return@flatMap emptyList()
            values.mapNotNull { value ->
                // JsonNull's `content` is the four-character string "null", which
                // is what distinguishes it from an absent value.
                val raw = value.jsonPrimitive.contentOrNull
                if (raw == null || raw == JSON_NULL) EFFORT_NONE else raw
            }
        }.distinct()
    }

    private fun status(raw: String?): ModelStatus = when (raw) {
        "beta" -> ModelStatus.BETA
        "deprecated" -> ModelStatus.DEPRECATED
        "alpha" -> ModelStatus.ALPHA
        else -> ModelStatus.ACTIVE
    }

    private companion object {
        const val CATALOG_URL = "https://models.opencode.ai/api.json"

        /** What `JsonNull.content` returns. */
        const val JSON_NULL = "null"

        const val EFFORT_NONE = "none"
    }
}
