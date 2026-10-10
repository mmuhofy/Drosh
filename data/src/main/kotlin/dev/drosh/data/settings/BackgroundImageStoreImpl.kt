package dev.drosh.data.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.drosh.domain.settings.BackgroundImageStore
import dev.drosh.terminal.UbuntuBootstrap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The background file in `.drosh/`, and its blurred cache.
 *
 * The convention lives in the Linux environment because that is where the
 * user's files are; the app-private copy of `.drosh/` is written alongside
 * so a rootfs reset does not take the background with it — the same
 * two-copy arrangement the settings file uses.
 */
@Singleton
class BackgroundImageStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bootstrap: UbuntuBootstrap,
) : BackgroundImageStore {

    private val _imageFile = MutableStateFlow<File?>(null)
    override val imageFile: StateFlow<File?> = _imageFile.asStateFlow()

    /** Radius the current [imageFile] was resolved for; 0 means none. */
    private var resolvedBlur = -1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Resolved once at startup: the terminal may be the first thing on
        // screen, and a background left by a previous run should be there.
        scope.launch { resolve(0) }
    }

    private fun primaryDir(): File = File(bootstrap.rootfsDir, "home/.drosh")

    private fun backupDir(): File = File(context.filesDir, ".drosh")

    override fun currentFileName(): String? =
        listOf(primaryDir(), backupDir())
            .flatMap { dir -> EXTENSIONS.mapNotNull { ext -> fileNamed(dir, ext).takeIf { it.exists() } } }
            .firstOrNull()
            ?.name

    private fun fileNamed(dir: File, ext: String): File = File(dir, "background.$ext")

    private fun extensionOf(name: String): String = name.substringAfterLast('.', "")

    override suspend fun import(uri: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val parsed = Uri.parse(uri)
            val name = "background.${extensionFor(parsed) ?: "png"}"
            val bytes = context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
                ?: return@withContext Result.failure(IllegalStateException("the picked file could not be read"))
            // The rootfs copy first, the app-private one after: the same order
            // the settings file is written in, and for the same reason.
            writeAtomically(File(primaryDir(), name), bytes)
            writeAtomically(File(backupDir(), name), bytes)
            // A previous image's blurred cache is now wrong; drop every one of
            // them, whatever radius produced it.
            EXTENSIONS.forEach { ext -> File(primaryDir(), "background.$ext.blurred.v1.png").delete() }
            resolve(resolvedBlur.coerceAtLeast(0))
            Result.success(name)
        } catch (e: Exception) {
            Timber.w(e, "background: import failed")
            Result.failure(e)
        }
    }

    override suspend fun remove(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            EXTENSIONS.forEach { ext ->
                File(primaryDir(), "background.$ext").delete()
                File(backupDir(), "background.$ext").delete()
            }
            // And every blurred variant with it.
            File(primaryDir(), ".").listFiles { f -> f.name.contains(".blurred.") }
                ?.forEach { it.delete() }
            File(backupDir(), ".").listFiles { f -> f.name.contains(".blurred.") }
                ?.forEach { it.delete() }
            resolve(0)
            // The block's last expression decides the Result's type: without
            // this it is Unit?, and the interface promises Unit.
            Unit
        }
    }

    override suspend fun applyBlur(radius: Int) {
        withContext(Dispatchers.IO) { resolve(radius) }
    }

    /**
     * The file the terminal should draw, or null.
     *
     * A failed blur leaves the source in place: a sharp background is a
     * better outcome than none. A radius that has not changed resolves to
     * what is already there, so the settings flow can call this on every
     * emission without redoing work.
     */
    private fun resolve(radius: Int): File? {
        val source = sourceFile()
        if (source == null || radius == resolvedBlur) {
            if (source == null) resolvedBlur = -1
            _imageFile.value = source
            return source
        }
        if (radius <= 0) {
            resolvedBlur = 0
            _imageFile.value = source
            return source
        }
        val cache = File(source.parentFile, blurredName(extensionOf(source.name), radius))
        if (!cache.exists() || cache.lastModified() < source.lastModified()) {
            runCatching { blurToFile(source, cache, radius) }
                .onFailure { Timber.w(it, "background: blur failed") }
        }
        resolvedBlur = radius
        _imageFile.value = cache.takeIf { it.exists() } ?: source
        return _imageFile.value
    }

    private fun sourceFile(): File? {
        for (dir in listOf(primaryDir(), backupDir())) {
            for (ext in EXTENSIONS) {
                val candidate = fileNamed(dir, ext)
                if (candidate.exists()) return candidate
            }
        }
        return null
    }

    /**
     * The cache's name carries the radius: a change re-generates rather than
     * overwriting a file that is still current for another radius, and the
     * marker rides along so a change to the blur itself invalidates every
     * old cache at once.
     */
    private fun blurredName(ext: String, radius: Int): String =
        "background.$ext.blurred.$CURRENT_BLUR_MARKER.$radius.png"

    private fun blurToFile(source: File, cache: File, radius: Int) {
        val blurred = blurFile(source, radius)
        FileOutputStream(cache).use { out -> blurred.compress(Bitmap.CompressFormat.PNG, 100, out) }
        blurred.recycle()
    }

    /**
     * Blurs [source] once.
     *
     * Downscaled before the passes and scaled back after: the blur radius is
     * in output pixels, but the work is per pixel, and a background behind a
     * terminal has no detail worth full resolution. A stack blur is two box
     * blurs, which is what a Gaussian looks like to the eye at this scale.
     */
    private fun blurFile(source: File, radius: Int): Bitmap {
        val original = BitmapFactory.decodeFile(source.absolutePath)
            ?: throw IllegalStateException("the background file could not be decoded")
        val scale = 8
        val small = Bitmap.createScaledBitmap(
            original,
            (original.width / scale).coerceAtLeast(1),
            (original.height / scale).coerceAtLeast(1),
            true,
        )
        val passes = 2
        var working = small.copy(Bitmap.Config.ARGB_8888, true)
        repeat(passes) { working = boxBlur(working, radius.coerceIn(1, 100)) }
        val out = Bitmap.createScaledBitmap(working, original.width, original.height, true)
        if (out != working) working.recycle()
        if (small != working) small.recycle()
        original.recycle()
        return out
    }

    /** A separable box blur, three passes — the cheap stand-in for a Gaussian. */
    private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        val width = src.width
        val height = src.height
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, 0, 0, width, height)
        val out = IntArray(width * height)

        fun blurPass(horizontal: Boolean) {
            val outer = if (horizontal) height else width
            val inner = if (horizontal) width else height
            for (o in 0 until outer) {
                for (i in 0 until inner) {
                    var r = 0; var g = 0; var b = 0; var a = 0; var count = 0
                    val from = (i - radius).coerceAtLeast(0)
                    val to = (i + radius).coerceAtMost(inner - 1)
                    for (k in from..to) {
                        val index = if (horizontal) o * width + k else k * width + o
                        val p = pixels[index]
                        a += (p ushr 24) and 0xFF
                        r += (p ushr 16) and 0xFF
                        g += (p ushr 8) and 0xFF
                        b += p and 0xFF
                        count++
                    }
                    val index = if (horizontal) o * width + i else i * width + o
                    out[index] = (a / count shl 24) or (r / count shl 16) or (g / count shl 8) or (b / count)
                }
            }
            System.arraycopy(out, 0, pixels, 0, out.size)
        }
        blurPass(horizontal = true)
        blurPass(horizontal = false)
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    /** The picked document's extension, from its mime type where possible. */
    private fun extensionFor(uri: Uri): String? {
        val fromName = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        if (fromName != null && fromName in EXTENSIONS) return fromName
        val mime = context.contentResolver.getType(uri) ?: return null
        return when (mime) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> null
        }
    }

    companion object {
        /** The extensions the convention recognises, in probe order. */
        val EXTENSIONS = listOf("png", "jpg", "jpeg", "webp", "gif")

        /**
         * Replaced when the blur implementation changes, so a cache written by
         * an older one is regenerated rather than drawn.
         */
        const val CURRENT_BLUR_MARKER = "v1"
    }
}
