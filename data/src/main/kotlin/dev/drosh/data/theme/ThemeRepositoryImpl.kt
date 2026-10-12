package dev.drosh.data.theme

import dev.drosh.data.settings.toml.SettingsPaths
import dev.drosh.domain.settings.DroshTheme
import dev.drosh.domain.settings.ThemeIndex
import dev.drosh.domain.settings.ThemeIndexEntry
import dev.drosh.domain.settings.ThemeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The theme store on disk and over the network.
 *
 * Installed themes are files in `<rootfs>/.drosh/themes/` — beside the
 * settings file, inside the environment the user owns. The app-private
 * copy of that directory is kept too, so themes survive a rootfs reset
 * the same way settings do; the rootfs copy is the one that is read.
 *
 * The index is a small JSON file in the store repo, fetched over plain
 * HTTPS and cached next to the themes it lists. A day-old cache is a
 * perfectly good index: theme releases are not breaking news.
 */
@Singleton
class ThemeRepositoryImpl @Inject constructor(
    private val paths: SettingsPaths,
) : ThemeRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val _installed = MutableStateFlow<List<DroshTheme>>(emptyList())
    override val installed: StateFlow<List<DroshTheme>> = _installed.asStateFlow()

    init {
        refreshInstalled()
    }

    // ── installed ─────────────────────────────────────────────────────────────

    /** Re-reads both theme directories, the rootfs copy winning per id. */
    private fun refreshInstalled() {
        val byId = LinkedHashMap<String, DroshTheme>()
        // The built-in leads the list: it is the way back to the colours the
        // app started with, and a store that only lists what was downloaded
        // has no home.
        byId[DroshTheme.DEFAULT.id] = DroshTheme.DEFAULT
        // Backup first so the rootfs copy overwrites it when both exist.
        readThemeDir(backupThemesDir()).forEach { byId[it.id] = it }
        readThemeDir(rootfsThemesDir()).forEach { byId[it.id] = it }
        _installed.value = byId.values.sortedBy { it.name.lowercase() }
    }

    private fun readThemeDir(dir: File): List<DroshTheme> =
        dir.listFiles { file -> file.isFile && file.name.endsWith(".toml") }
            ?.mapNotNull { file ->
                runCatching { ThemeToml.parse(file.readText()) }.getOrNull()
            }
            ?: emptyList()

    override fun theme(id: String): DroshTheme? = _installed.value.firstOrNull { it.id == id }

    // ── the index ─────────────────────────────────────────────────────────────

    override suspend fun index(force: Boolean): Result<ThemeIndex> = withContext(Dispatchers.IO) {
        val cache = File(rootfsThemesDir(), INDEX_FILE)
        val cached = runCatching { json.decodeFromString<IndexFile>(cache.readText()) }
            .getOrNull()
            ?.toIndex()

        if (!force && cached != null && cache.lastModified() > System.currentTimeMillis() - INDEX_TTL_MS) {
            return@withContext Result.success(cached)
        }

        val fetched = fetchText(THEME_INDEX_URL)
        if (fetched.isFailure) {
            // A failed fetch answers the cache when there is one: a theme
            // store that cannot be reached should not be a dark room.
            return@withContext if (cached != null) {
                Result.success(cached)
            } else {
                Result.failure(fetched.exceptionOrNull() ?: IOException("index fetch failed"))
            }
        }

        val text = fetched.getOrThrow()
        val parsed = runCatching { json.decodeFromString<IndexFile>(text).toIndex() }
            .getOrElse { error ->
                return@withContext if (cached != null) Result.success(cached)
                else Result.failure(error)
            }
        runCatching {
            cache.parentFile?.mkdirs()
            cache.writeText(text)
        }
        Result.success(parsed)
    }

    // ── installing ────────────────────────────────────────────────────────────

    override suspend fun install(entry: ThemeIndexEntry): Result<DroshTheme> =
        withContext(Dispatchers.IO) {
            val text = fetchText("$THEMES_BASE_URL/${entry.file}").getOrElse {
                return@withContext Result.failure(it)
            }
            val theme = runCatching { ThemeToml.parse(text) }.getOrElse {
                return@withContext Result.failure(it)
            }
            writeTheme(theme).also { result ->
                result.onSuccess { refreshInstalled() }
            }
        }

    override suspend fun installFromUrl(url: String): Result<DroshTheme> =
        withContext(Dispatchers.IO) {
            val text = fetchText(url).getOrElse { return@withContext Result.failure(it) }
            val theme = runCatching { ThemeToml.parse(text) }.getOrElse {
                return@withContext Result.failure(it)
            }
            writeTheme(theme).also { result ->
                result.onSuccess { refreshInstalled() }
            }
        }

    override suspend fun saveCustom(theme: DroshTheme): Result<DroshTheme> =
        withContext(Dispatchers.IO) {
            writeTheme(theme).also { result ->
                result.onSuccess { refreshInstalled() }
            }
        }

    override suspend fun delete(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        val primary = File(rootfsThemesDir(), "$id.toml")
        val backup = File(backupThemesDir(), "$id.toml")
        val deleted = primary.delete() or backup.delete()
        if (deleted || !primary.exists()) {
            refreshInstalled()
            Result.success(Unit)
        } else {
            Result.failure(IOException("no theme file for $id"))
        }
    }

    // ── plumbing ──────────────────────────────────────────────────────────────

    /** Writes to the rootfs copy, then to the app-private one. */
    private fun writeTheme(theme: DroshTheme): Result<DroshTheme> = try {
        writeAtomically(File(rootfsThemesDir(), "${theme.id}.toml"), ThemeToml.render(theme))
        writeAtomically(File(backupThemesDir(), "${theme.id}.toml"), ThemeToml.render(theme))
        Result.success(theme)
    } catch (e: IOException) {
        Timber.w(e, "themes: could not write ${theme.id}")
        Result.failure(e)
    }

    private fun writeAtomically(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun fetchText(url: String): Result<String> = try {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return Result.failure(IOException("$url answered ${response.code}"))
            }
            Result.success(response.body?.string() ?: "")
        }
    } catch (e: IOException) {
        Timber.w(e, "themes: fetch failed for $url")
        Result.failure(e)
    }

    private fun rootfsThemesDir(): File = File(paths.primaryDir, "themes")

    private fun backupThemesDir(): File = File(paths.backupDir, "themes")

    @Serializable
    private data class IndexFile(
        val schema: Int = 1,
        val themes: List<IndexEntryFile> = emptyList(),
    ) {
        fun toIndex(): ThemeIndex =
            ThemeIndex(
                themes.map {
                    ThemeIndexEntry(it.id, it.name, it.author, it.description, it.file, it.preview)
                },
            )
    }

    @Serializable
    private data class IndexEntryFile(
        val id: String,
        val name: String,
        val author: String = "",
        val description: String = "",
        val file: String = "",
        val preview: List<String> = emptyList(),
    )

    private companion object {
        const val THEMES_BASE_URL = "https://raw.githubusercontent.com/mmuhofy/drosh-themes/main"
        const val THEME_INDEX_URL = "$THEMES_BASE_URL/index.json"
        const val INDEX_FILE = "index.json"
        val INDEX_TTL_MS = TimeUnit.DAYS.toMillis(1)
    }
}