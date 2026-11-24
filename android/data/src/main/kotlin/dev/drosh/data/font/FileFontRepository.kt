package dev.drosh.data.font

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.drosh.domain.font.FontRepository
import dev.drosh.domain.font.FontStore
import dev.drosh.domain.font.InstalledFont
import dev.drosh.domain.font.StoreFont
import dev.drosh.domain.font.TerminalFontCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The font shelf on disk and over the network.
 *
 * ## Where a font lives
 *
 * In the app's own files directory — not in `<rootfs>/.drosh/`, where themes
 * and settings live — because a font file is not a user document and no shell
 * in the guest can make the renderer pick it up anyway: the renderer opens the
 * file from the app's half of the storage. The directory is one the app owns,
 * so its contents are exactly what the store put there.
 *
 * ## Why a marker beside the setting
 *
 * The active face is written twice — once as the setting, once as
 * [FontStore.ACTIVE_MARKER] — because the renderer runs on a draw thread and
 * cannot read a TOML document before it knows which face to rasterise with. The
 * marker is the fast half; the setting is the user's half. They are written
 * together, from [activate], so a disagreement has no way to happen.
 */
@Singleton
class FileFontRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : FontRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val dir: File = File(context.filesDir, FontStore.FONTS_DIR)

    private val marker: File = File(context.filesDir, FontStore.ACTIVE_MARKER)

    private val _installed = MutableStateFlow<List<InstalledFont>>(emptyList())
    override val installed: StateFlow<List<InstalledFont>> = _installed.asStateFlow()

    private val _active = MutableStateFlow("")
    override val active: StateFlow<String> = _active.asStateFlow()

    init {
        refreshInstalled()
        _active.value = readMarker()
    }

    // ── installed ─────────────────────────────────────────────────────────────

    override fun store(): List<StoreFont> = TerminalFontCatalog.fonts

    override fun installedFont(id: String): InstalledFont? =
        _installed.value.firstOrNull { it.id == id }

    private fun refreshInstalled() {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".ttf") }
            ?: emptyArray()
        _installed.value = files.mapNotNull { file ->
            val id = file.name.substringBeforeLast(".ttf")
            // A file with no shelf entry is still a font the user has: it is
            // listed by the name it was filed under, rather than hidden because
            // it is no longer offered.
            val entry = TerminalFontCatalog.byId(id)
            InstalledFont(
                id = id,
                name = entry?.name ?: id.replace('-', ' '),
                path = file.absolutePath,
                license = entry?.license ?: "",
                licenseUrl = entry?.licenseUrl ?: "",
            )
        }.sortedBy { it.name.lowercase() }
    }

    // ── downloading ───────────────────────────────────────────────────────────

    override suspend fun download(font: StoreFont): Result<InstalledFont> =
        withContext(Dispatchers.IO) {
            runCatching {
                dir.mkdirs()
                val target = File(dir, "${font.id}.ttf")
                // A partial download is written beside it and moved into place
                // only once the bytes check out: a half a font left under the
                // real name is a face the renderer fails to open at every start.
                val partial = File(dir, "${font.id}.ttf.part")
                try {
                    partial.delete()
                    fetch(font.url, partial)
                    if (!isFontFile(partial)) {
                        throw IOException("That download is not a font file")
                    }
                    if (!partial.renameTo(target)) {
                        partial.copyTo(target, overwrite = true)
                        partial.delete()
                    }
                } finally {
                    partial.delete()
                }
                refreshInstalled()
                InstalledFont(
                    id = font.id,
                    name = font.name,
                    path = target.absolutePath,
                    license = font.license,
                    licenseUrl = font.licenseUrl,
                )
            }
        }

    private fun fetch(url: String, target: File) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for $url")
            }
            val body = response.body ?: throw IOException("Empty response for $url")
            target.outputStream().use { out -> body.byteStream().use { it.copyTo(out) } }
        }
    }

    /**
     * Whether [file] starts with a TrueType or OpenType signature.
     *
     * The store serves a redirect to an object host, and an error page comes
     * back as a perfectly successful HTTP response. Four bytes are enough to
     * tell a font from an apology.
     */
    private fun isFontFile(file: File): Boolean {
        if (file.length() < MIN_FONT_BYTES) return false
        val head = ByteArray(4)
        file.inputStream().use { input ->
            if (input.read(head) != 4) return false
        }
        val tag = String(head, Charsets.US_ASCII)
        return tag == "true" || tag == "OTTO" || tag == "ttcf" ||
            (head[0] == 0.toByte() && head[1] == 1.toByte() && head[2] == 0.toByte() && head[3] == 0.toByte())
    }

    // ── active ────────────────────────────────────────────────────────────────

    override fun activate(id: String) {
        // A font that is not on the device cannot be the active one: the
        // renderer would fall back to the bundled face while the screen said
        // the download was the user's choice.
        if (id.isNotEmpty() && installedFont(id) == null) return
        _active.value = id
        runCatching {
            if (id.isEmpty()) {
                marker.delete()
            } else {
                marker.parentFile?.mkdirs()
                marker.writeText(id)
            }
        }
    }

    private fun readMarker(): String {
        val id = runCatching { marker.readText().trim() }.getOrNull().orEmpty()
        return if (id.isNotEmpty() && installedFont(id) != null) id else ""
    }

    // ── deleting ──────────────────────────────────────────────────────────────

    override suspend fun delete(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (id.isEmpty()) return@withContext Result.failure(IllegalArgumentException("the bundled font"))
        if (id == _active.value) {
            return@withContext Result.failure(
                IllegalStateException("That font is in use; switch away from it first"),
            )
        }
        runCatching {
            val file = File(dir, "$id.ttf")
            if (file.exists() && !file.delete()) {
                throw IOException("Could not delete $id")
            }
            refreshInstalled()
        }
    }

    private companion object {
        /** Below this a file is a page, not a face. */
        const val MIN_FONT_BYTES = 20_000L
    }
}
