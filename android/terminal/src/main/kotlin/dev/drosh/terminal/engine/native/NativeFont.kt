package dev.drosh.terminal.engine.native

import android.content.Context
import dev.drosh.terminal.engine.font.TerminalFont
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The font the terminal draws with, as the engine knows it.
 *
 * ## Why the engine has its own copy
 *
 * The renderer rasterises through Android's `Paint`, which is what the cell's
 * measurement uses; the two agree by construction. The engine's shaper lives
 * in Rust and shapes a whole run at once — ligatures, combining marks, scripts
 * — which `Paint` cannot do on its own. It needs the font's bytes, and it
 * hands back glyph ids and the masks to draw them with.
 *
 * The two are kept in step by loading the same file both sides read: the
 * bundled font. A mismatch between them is text that does not fit its own
 * advance — which is the bug the whole `TerminalFont` object exists to avoid.
 */
object NativeFont {

    /**
     * Whether the engine already holds a face, so the (multi-megabyte) font
     * bytes are read from the APK once per process rather than per preview.
     */
    @Volatile
    private var installed = false

    /**
     * Make the terminal's font available to the engine, once.
     *
     * The preview renderer rasterises glyphs from the [dev.drosh.terminal.engine.font.TerminalFont]
     * bytes — there is no second font in Rust — so without this call a session
     * card is a correctly coloured rectangle with no text in it. The bytes come
     * from the same file the on-screen surface draws with, so a preview cannot
     * disagree with the terminal it pictures.
     *
     * Safe to call from any thread and any number of times.
     */
    fun ensureInstalled(context: Context): Boolean {
        if (installed) return true
        synchronized(this) {
            if (installed) return true
            val ok = load(TerminalFont.bytes(context.applicationContext))
            installed = ok
            return ok
        }
    }

    /** Load the font's bytes. Returns true on success. */
    fun load(bytes: ByteArray): Boolean {
        if (!NativeLibraries.ensureLoaded()) return false
        return nativeLoad(bytes) != 0L
    }

    /**
     * Shape one run of text at [sizePx].
     *
     * @return the glyphs the run is made of, or an empty list on failure.
     */
    fun shape(text: String, sizePx: Float): List<ShapedGlyph> {
        if (text.isEmpty() || sizePx <= 0f) return emptyList()
        if (!NativeLibraries.ensureLoaded()) return emptyList()
        val buffer = nativeShape(text, sizePx) ?: return emptyList()
        return parseShaped(buffer)
    }

    /**
     * Rasterize one glyph at [sizePx] into an alpha mask.
     *
     * @return the mask, or null when the glyph has no outline.
     */
    fun rasterize(glyphId: Int, sizePx: Float): GlyphMask? {
        if (glyphId <= 0 || sizePx <= 0f) return null
        if (!NativeLibraries.ensureLoaded()) return null
        val buffer = nativeRasterize(glyphId, sizePx) ?: return null
        return parseMask(buffer)
    }

    private external fun nativeLoad(data: ByteArray): Long

    private external fun nativeShape(text: String, sizePx: Float): ByteArray?

    private external fun nativeRasterize(glyphId: Int, sizePx: Float): ByteArray?

    // --- wire formats -------------------------------------------------------

    private fun parseShaped(buffer: ByteArray): List<ShapedGlyph> {
        if (buffer.size < 4) return emptyList()
        val little = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        val count = little.int
        if (count < 0 || count > 10000) return emptyList()
        val result = ArrayList<ShapedGlyph>(count)
        for (i in 0 until count) {
            if (little.remaining() < 16) return result
            val glyphId = little.int
            val cluster = little.int
            val offset = little.float
            val advance = little.float
            result.add(ShapedGlyph(glyphId, cluster, offset, advance))
        }
        return result
    }

    private fun parseMask(buffer: ByteArray): GlyphMask? {
        if (buffer.size < 16) return null
        val little = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        val width = little.int
        val height = little.int
        val left = little.int
        val top = little.int
        if (width <= 0 || height <= 0 || width > 512 || height > 512) return null
        val coverage = ByteArray(width * height)
        val available = little.remaining()
        if (available < width * height) return null
        little.get(coverage)
        return GlyphMask(width, height, left, top, coverage)
    }

    /** A glyph the shaper produced. */
    data class ShapedGlyph(
        val glyphId: Int,
        val cluster: Int,
        val offset: Float,
        val advance: Float,
    )

    /** An alpha mask the rasteriser produced. */
    data class GlyphMask(
        val width: Int,
        val height: Int,
        val left: Int,
        val top: Int,
        val coverage: ByteArray,
    )
}
