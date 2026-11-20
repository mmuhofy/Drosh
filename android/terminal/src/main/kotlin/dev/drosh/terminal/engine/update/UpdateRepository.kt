package dev.drosh.terminal.engine.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a component's builds come from.
 *
 * Each source knows its own tag scheme and asset names, so the rest of the
 * system never sees a URL shape. A source that cannot be reached returns null
 * rather than throwing: an update check that fails is not an error the user has
 * to act on.
 */
interface UpdateRepository {
    val id: ComponentId
    fun supports(channel: UpdateChannel): Boolean
    suspend fun latest(channel: UpdateChannel, abi: String): ComponentRelease?
    suspend fun download(release: ComponentRelease): ByteArray?
}

@Serializable
internal data class GhAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val url: String,
)

@Serializable
internal data class GhRelease(
    val tag_name: String,
    val name: String? = null,
    val published_at: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<GhAsset> = emptyList(),
)

internal val UpdateJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** One HTTP GET, or null when the network or the API said no. */
internal suspend fun httpGetText(url: String): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Drosh")
                connectTimeout = 10_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

internal suspend fun httpGetBytes(url: String): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching {
            val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty("User-Agent", "Drosh")
                connectTimeout = 10_000
                readTimeout = 60_000
                instanceFollowRedirects = true
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
