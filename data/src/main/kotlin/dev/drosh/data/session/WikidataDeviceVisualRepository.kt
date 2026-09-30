package dev.drosh.data.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.drosh.data.local.irisShellDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A product image for the device, from Wikidata and Wikimedia Commons.
 *
 * Both are Wikimedia projects: the lookup goes through the public Wikidata
 * API, and the image is whatever Commons hosts for that item. Nothing is
 * scraped and there is no key, which is the whole point — the alternative
 * databases people reach for are all scrapers of a site that publishes no
 * API and whose images are copyrighted.
 *
 * Coverage is uneven. Recent Androids are largely there, some are not, and the
 * answer for a given model does not change, so the result — including "there
 * is no image" — is cached and never asked for again. A missing picture costs
 * a monogram in the circle and nothing else.
 */
@Singleton
class WikidataDeviceVisualRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: OkHttpClient,
) {

    private val store: DataStore<Preferences> get() = context.applicationContext.irisShellDataStore

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The image URL for [marketingName], or null if there is none or we could
     * not find one. Returns the cached answer without touching the network on
     * every call after the first.
     */
    suspend fun imageUrlFor(marketingName: String): String? {
        if (marketingName.isBlank()) return null

        val key = cacheKey(marketingName)
        val cached = readCache(key)
        if (cached != null) return cached.takeIf { it != NONE }

        val url = withContext(Dispatchers.IO) { lookup(marketingName) }
        // Cached even when the lookup found nothing, so a device that has no
        // image is not re-queried on every drawer open.
        writeCache(key, url ?: NONE)
        return url
    }

    private suspend fun readCache(key: Preferences.Key<String>): String? =
        store.data.first()[key]

    private suspend fun writeCache(key: Preferences.Key<String>, value: String) {
        store.edit { it[key] = value }
    }

    private fun cacheKey(name: String) =
        stringPreferencesKey("device_visual_${name.hashCode().toUInt().toString(16)}")

    private fun lookup(name: String): String? {
        val qid = findEntity(name) ?: return null
        val fileName = imageProperty(qid) ?: return null
        return commonsImageUrl(fileName)
    }

    /** Wikidata item for the device, if the search returns a plausible match. */
    private fun findEntity(name: String): String? {
        val url = "https://www.wikidata.org/w/api.php" +
            "?action=wbsearchentities&format=json&language=en&type=item&limit=5" +
            "&search=" + enc(name)
        val root = getJson(url) ?: return null
        val results = root["search"]?.jsonArray ?: return null
        val wanted = name.lowercase().split(' ', '-').filter { it.length > 2 }

        for (element in results) {
            val obj = element.jsonObject
            val label = obj["label"]?.jsonPrimitive?.content ?: continue
            val id = obj["id"]?.jsonPrimitive?.content ?: continue
            // Wikidata will happily return a song or a person with a similar
            // name. Only accept it when the model's own words are all there.
            val haystack = label.lowercase()
            if (wanted.all { haystack.contains(it) }) return id
        }
        return null
    }

    /** P18 is Wikidata's "image" property; its value is a Commons filename. */
    private fun imageProperty(qid: String): String? {
        val url = "https://www.wikidata.org/w/api.php" +
            "?action=wbgetentities&format=json&props=claims&ids=" + enc(qid)
        val root = getJson(url) ?: return null
        val claims = root["entities"]?.jsonObject?.get(qid)?.jsonObject
            ?.get("claims")?.jsonObject ?: return null
        val images = claims["P18"]?.jsonArray ?: return null
        val first = images.firstOrNull()?.jsonObject ?: return null
        return first["mainsnak"]?.jsonObject
            ?.get("datavalue")?.jsonObject
            ?.get("value")?.jsonPrimitive?.content
    }

    /** Turns a Commons `File:` title into a thumbnail URL on the Commons CDN. */
    private fun commonsImageUrl(fileName: String): String? {
        val url = "https://commons.wikimedia.org/w/api.php" +
            "?action=query&format=json&prop=imageinfo&iiprop=url&iiurlwidth=256" +
            "&titles=" + enc(fileName)
        val root = getJson(url) ?: return null
        val pages = root["query"]?.jsonObject?.get("pages")?.jsonObject ?: return null
        for (page in pages.values) {
            val info = page.jsonObject["imageinfo"]?.jsonArray ?: continue
            val thumb = info.firstOrNull()?.jsonObject
                ?.get("thumburl")?.jsonPrimitive?.content
            if (!thumb.isNullOrBlank()) return thumb
        }
        return null
    }

    private fun getJson(url: String): JsonObject? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            // body is non-null from OkHttp 5, so the null guard is gone. An
            // empty body still has to bail out rather than reach the parser.
            val body = response.body.string().takeIf { it.isNotBlank() } ?: return null
            json.parseToJsonElement(body).jsonObject
        }
    } catch (_: IOException) {
        null
    } catch (_: Exception) {
        // A malformed or unexpected response means no image, not a crash: this
        // is decoration on a header row.
        null
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        /** Wikimedia asks for a contact in the UA so problems can be traced. */
        const val USER_AGENT = "Drosh/1.0 (Android terminal; device header image)"

        /** Cached stand-in for "we looked and there is nothing". */
        const val NONE = "\u0000none"
    }
}
