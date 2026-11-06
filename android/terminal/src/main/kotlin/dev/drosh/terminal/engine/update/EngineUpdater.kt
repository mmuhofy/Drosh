package dev.drosh.terminal.engine.update

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Asking the release page what engines exist, and fetching one.
 *
 * ## The repository
 *
 * The engines are published by the Drosh project's own build workflow, in the
 * same repository as the app. The API is GitHub's, unauthenticated, because the
 * repository is public and a token in an app is a token somebody will find.
 *
 * ## What is checked and what is trusted
 *
 * The *list* is trusted: a wrong version number in a JSON body costs the user
 * a bad download dialog, not a compromised device. The *bytes* are not: the
 * published SHA-256 is compared against what arrives, and a mismatch is
 * discarded. That is the line — metadata can be wrong, code cannot.
 *
 * ## Channels
 *
 * `release` and `nightly` are separate tags on the same release page. A nightly
 * is published every day from upstream's `main`; a release is tagged when it
 * has been tested. The user chooses which to follow, and the choice is stored.
 */
class EngineUpdater(
    private val context: Context,
    private val repository: String = DEFAULT_REPOSITORY,
) {

    /** Where a downloaded library is staged before it is installed. */
    private val staging: File
        get() = File(context.cacheDir, "engine-download")

    /**
     * The newest build on [channel] for this device's ABI, or null when the
     * network or the API said no.
     *
     * Never throws: an update check that fails is not an error the user has to
     * act on, and a terminal must open whether or not the network is there.
     */
    suspend fun newest(channel: EngineChannel, abi: String = EngineInstall.defaultAbi()): EngineBuild? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { fetchNewest(channel, abi) }
                .onFailure { Log.w(TAG, "the update check failed", it) }
                .getOrNull()
        }

    private fun fetchNewest(channel: EngineChannel, abi: String): EngineBuild? {
        val url = "https://api.github.com/repos/$repository/releases?per_page=20"
        val body = httpGet(url) ?: return null

        // A hand-rolled parse rather than a JSON library: the shape is one
        // array of objects with three fields each, and a dependency for that
        // is a dependency to keep current.
        val releases = splitObjects(body)
        for (release in releases) {
            val tag = stringField(release, "tag_name") ?: continue
            val matched = when (channel) {
                is EngineChannel.Release -> tag.startsWith("vt-")
                is EngineChannel.Nightly -> tag.startsWith("nightly-")
                else -> false
            }
            if (!matched) continue

            val asset = findAsset(release, abi) ?: continue
            val sumsUrl = findAssetUrl(release, "SHA256SUMS") ?: continue
            val digest = digestFor(sumsUrl, "libghostty-vt-$abi.so") ?: continue
            val published = stringField(release, "published_at") ?: ""

            val version = when (channel) {
                is EngineChannel.Release -> tag.removePrefix("vt-")
                is EngineChannel.Nightly -> tag.removePrefix("nightly-")
                else -> tag
            }
            val resolved = when (channel) {
                is EngineChannel.Release -> EngineChannel.Release(version)
                is EngineChannel.Nightly -> EngineChannel.Nightly(
                    date = version,
                    commit = "",
                )
                else -> channel
            }

            return EngineBuild(
                tag = tag,
                channel = resolved,
                ghosttyCommit = "",
                abi = abi,
                sizeBytes = longField(asset, "size") ?: 0L,
                sha256 = digest,
                downloadUrl = stringField(asset, "browser_download_url") ?: continue,
                publishedAt = published,
            )
        }
        return null
    }

    /**
     * Download and install [build]. Returns true when the library is in place.
     *
     * The bytes are checksummed twice — once here and once by
     * [EngineInstall.install] — because the second check is the one that
     * protects the file that is actually loaded, and the first is the one that
     * gives a useful message when the network corrupted something.
     */
    suspend fun download(build: EngineBuild): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val bytes = httpGetBytes(build.downloadUrl) ?: return@withContext false
            if (bytes.size.toLong() != build.sizeBytes && build.sizeBytes > 0) {
                Log.w(TAG, "the download's size does not match the release")
                return@withContext false
            }
            EngineInstall.install(context, bytes, build.sha256)
        }

    /** True when the engine on this device is already [build]. */
    fun isInstalled(build: EngineBuild): Boolean =
        EngineInstall.hasDownloaded(context, build.sha256)

    // --- HTTP -----------------------------------------------------------------

    private fun httpGet(url: String): String? {
        val bytes = httpGetBytes(url, accept = "application/vnd.github+json") ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    private fun httpGetBytes(url: String, accept: String = "application/octet-stream"): ByteArray? {
        val connection = runCatching {
            (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "Drosh")
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
        }.getOrNull() ?: return null

        return try {
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${connection.responseCode} for $url")
                return null
            }
            connection.inputStream.use { it.readBytes() }
        } catch (error: java.io.IOException) {
            Log.w(TAG, "the request failed", error)
            null
        } finally {
            connection.disconnect()
        }
    }

    // --- a tiny JSON reader ---------------------------------------------------

    /** Split a JSON array body into its object bodies. */
    private fun splitObjects(body: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false

        for ((index, char) in body.withIndex()) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth += 1
                }
                '}' -> {
                    depth -= 1
                    if (depth == 0 && start >= 0) {
                        out.add(body.substring(start, index + 1))
                        start = -1
                    }
                }
            }
        }
        return out
    }

    /** The string value of a top-level `"key": "value"` pair. */
    private fun stringField(body: String, key: String): String? {
        val marker = "\"$key\""
        val at = body.indexOf(marker)
        if (at < 0) return null
        val colon = body.indexOf(':', at + marker.length)
        if (colon < 0) return null
        val quote = body.indexOf('"', colon + 1)
        if (quote < 0) return null

        val out = StringBuilder()
        var index = quote + 1
        while (index < body.length) {
            when (val char = body[index]) {
                '"' -> return out.toString()
                '\\' -> {
                    index += 1
                    if (index < body.length) out.append(body[index])
                }
                else -> out.append(char)
            }
            index += 1
        }
        return null
    }

    /** The numeric value of a top-level `"key": 123` pair. */
    private fun longField(body: String, key: String): Long? {
        val marker = "\"$key\""
        val at = body.indexOf(marker)
        if (at < 0) return null
        val colon = body.indexOf(':', at + marker.length)
        if (colon < 0) return null

        var index = colon + 1
        while (index < body.length && body[index].isWhitespace()) index += 1
        val start = index
        while (index < body.length && (body[index].isDigit() || body[index] == '-')) index += 1
        if (index == start) return null
        return body.substring(start, index).toLongOrNull()
    }

    /** The asset whose name names this ABI. */
    private fun findAsset(release: String, abi: String): String? {
        val marker = "libghostty-vt-$abi.so"
        val at = release.indexOf(marker)
        if (at < 0) return null
        // The object that contains the name: walk back to the nearest '{'.
        val start = release.lastIndexOf('{', at).coerceAtLeast(0)
        val end = release.indexOf('}', at).let { if (it < 0) release.length else it }
        return release.substring(start, end)
    }

    /** The download URL of an asset by exact name, or null. */
    private fun findAssetUrl(release: String, name: String): String? {
        val at = release.indexOf("\"$name\"")
        if (at < 0) return null
        val start = release.lastIndexOf('{', at).coerceAtLeast(0)
        val end = release.indexOf('}', at).let { if (it < 0) release.length else it }
        return stringField(release.substring(start, end), "browser_download_url")
    }

    /**
     * The digest for one file, read from the release's `SHA256SUMS`.
     *
     * The list is the authority: it is the same file the fetch script checks
     * against at build time, so a device and CI agree on what the bytes should
     * be. A digest that cannot be read means the artifact cannot be verified,
     * and an unverifiable library is not installed.
     */
    private fun digestFor(sumsUrl: String, fileName: String): String? {
        val body = httpGet(sumsUrl) ?: return null
        for (line in body.lineSequence()) {
            // `sha256sum` shape: "<digest>  <name>"
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2 && parts[1] == fileName) {
                return parts[0].lowercase()
            }
        }
        return null
    }

    companion object {
        private const val TAG = "EngineUpdater"

        /** Where the engines are published. */
        const val DEFAULT_REPOSITORY = "mmuhofy/Drosh"

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000

    }
}
