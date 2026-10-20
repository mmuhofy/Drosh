package dev.drosh.terminal.engine.render

import android.util.Log
import dev.drosh.terminal.engine.core.NativeCore
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One glyph in the atlas: its mask's position, size and how it sits relative to
 * the pen position and the baseline.
 */
internal class GlyphEntry(
    /** Atlas position of the mask's left column, in texels. */
    val x: Int,
    /** Atlas position of the mask's top row, in texels. */
    val y: Int,
    val width: Int,
    val height: Int,
    /** The mask's left edge relative to the pen position, pixels. */
    val left: Int,
    /** The mask's top edge relative to the baseline, up positive, pixels. */
    val top: Int,
    /** `NativeCore.GLYPH_DIRECT`, `GLYPH_SUBSTITUTED` or `GLYPH_MISSING`. */
    val kind: Int,
) {
    /** Nothing to draw: a space, or a codepoint the font does not have at all. */
    val isBlank: Boolean get() = width == 0 || height == 0
}

/**
 * The glyph atlas — Phase B's replacement for the Android-baked ASCII atlas.
 *
 * Rust rasterises one coverage mask per codepoint (decision D4) and this class
 * packs them into a single-channel texture the shader tints. Masks are added on
 * demand and cached by (codepoint, weight, pixel size); a cache miss costs one
 * JNI call, and the frame loop only ever reads the cache.
 *
 * The packer is the simplest thing that works — shelves of equal height, filled
 * left to right — because a terminal's glyph set is small and uniform; the size
 * buckets in the Phase B plan (`renderer/atlas.kt`) grow from here.
 */
internal class GlyphAtlas(val size: Int = ATLAS_SIZE) {

    private companion object {
        const val TAG = "DroshLab"

        /** 1024² x 1 byte = 1 MB of coverage: thousands of glyphs, or a few dozen sizes. */
        const val ATLAS_SIZE = 1024

        /** A 1x1 opaque texel stands in for the real coverage of solid quads. */
        const val SOLID = 1

        /** The warm-up range: printable ASCII. */
        const val FIRST_PRINTABLE = 32
        const val LAST_PRINTABLE = 126

        const val INITIAL_SCRATCH_BYTES = 4096

        /** Key layout: codepoint (32) | bold (1) | pixel size bits (31). */
        const val KEY_BOLD_SHIFT = 32
        const val KEY_SIZE_SHIFT = 33
    }

    /** Coverage, one byte per texel, row-major from the top row. */
    val pixels = ByteArray(size * size)

    private val entries = HashMap<Long, GlyphEntry>(256)
    private var shelfY = 0
    private var shelfHeight = 0
    private var penX = 0

    private var scratch = ByteBuffer.allocateDirect(INITIAL_SCRATCH_BYTES).order(ByteOrder.LITTLE_ENDIAN)

    /** Direct copy of [pixels], refreshed on demand for the texture upload. */
    private val texture = ByteBuffer.allocateDirect(size * size)

    /** Set when [pixels] changed and the texture has to be re-uploaded. */
    var dirty = true
        private set

    /** Codepoints the font had no glyph for, so the UI can say so once. */
    var substituted = 0
        private set

    /** Codepoints nothing could be drawn for. */
    var missing = 0
        private set

    /** True once the atlas ran out of space: new glyphs then draw nothing. */
    var full = false
        private set

    /** The opaque block: backgrounds, underlines, strikeout and the cursor. */
    val solidU: Float
    val solidV: Float

    /** The same block in texels, which is what a foreground record carries. */
    var solidX = 0
        private set
    var solidY = 0
        private set

    init {
        val block = reserve(SOLID, SOLID)
        if (block == null) {
            // Unreachable for any sane atlas size; a renderer must not throw.
            Log.e(TAG, "the glyph atlas cannot hold its solid texel")
            solidU = 0f
            solidV = 0f
        } else {
            val x = unpackX(block)
            val y = unpackY(block)
            pixels[y * size + x] = 255.toByte()
            solidX = x
            solidY = y
            solidU = (x + 0.5f) / size
            solidV = (y + 0.5f) / size
        }
    }

    /**
     * A direct view of the coverage for `glTexImage2D`, refreshed when the atlas
     * changed since the last call.
     */
    fun directPixels(): ByteBuffer {
        if (dirty) {
            texture.clear()
            texture.put(pixels, 0, pixels.size)
            texture.clear()
            dirty = false
        }
        return texture
    }

    /**
     * The atlas entry for `codepoint`, rasterising it first if this is the first
     * time it is asked for at this size.
     */
    fun glyph(codepoint: Int, bold: Boolean, px: Float): GlyphEntry? {
        val key = key(codepoint, bold, px)
        entries[key]?.let { return it }

        val entry = rasterize(codepoint, bold, px)
        entries[key] = entry
        return entry
    }

    /**
     * Rasterise the printable ASCII range up front.
     *
     * Without this, a burst that introduces new characters pays for each one on
     * the frame it appears: one JNI rasterisation per codepoint *and* a full
     * atlas re-upload, which a device run measured as a 120 ms frame (TODO.md,
     * device run 2 — `pump` was fine, `build` was 13.3 ms on average). ASCII is
     * what a shell spends its time printing, so it is the set worth having ready;
     * rarer codepoints still rasterise on demand.
     *
     * Called from the GL thread once the context exists.
     */
    fun warmUp(px: Float, bold: Boolean) {
        for (codepoint in FIRST_PRINTABLE..LAST_PRINTABLE) {
            glyph(codepoint, bold, px)
        }
    }

    private fun rasterize(codepoint: Int, bold: Boolean, px: Float): GlyphEntry {
        var needed = NativeCore.nativeRasterize(codepoint, bold, px, scratch)

        if (needed > scratch.capacity()) {
            // The core answers with the size it needs when the buffer is short.
            scratch = ByteBuffer.allocateDirect(needed).order(ByteOrder.LITTLE_ENDIAN)
            needed = NativeCore.nativeRasterize(codepoint, bold, px, scratch)
        }

        if (needed < NativeCore.GLYPH_HEADER_BYTES) {
            // An ErrorCode: nothing to draw, and remember that this codepoint
            // failed so it is not retried on every frame.
            Log.w(TAG, "rasterize(0x${codepoint.toString(16)}) failed: $needed")
            missing += 1
            return GlyphEntry(0, 0, 0, 0, 0, 0, NativeCore.GLYPH_MISSING)
        }

        val header = scratch.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        header.position(0)
        val kind = header.getInt()
        header.getInt() // glyph id: the atlas is keyed by codepoint, not by glyph
        val width = header.getInt()
        val height = header.getInt()
        val left = header.getInt()
        val top = header.getInt()

        when (kind) {
            NativeCore.GLYPH_SUBSTITUTED -> substituted += 1
            NativeCore.GLYPH_MISSING -> missing += 1
        }

        if (width <= 0 || height <= 0) {
            return GlyphEntry(0, 0, 0, 0, left, top, kind)
        }

        val position = reserve(width, height)
        if (position == null) {
            if (!full) {
                full = true
                Log.w(TAG, "the glyph atlas is full at ${size}x$size; some glyphs will not draw")
            }
            return GlyphEntry(0, 0, 0, 0, left, top, kind)
        }

        val x = unpackX(position)
        val y = unpackY(position)
        for (row in 0 until height) {
            val from = NativeCore.GLYPH_HEADER_BYTES + row * width
            scratch.position(from)
            scratch.get(pixels, (y + row) * size + x, width)
        }

        dirty = true
        return GlyphEntry(x, y, width, height, left, top, kind)
    }

    /**
     * Reserve a `width` x `height` block, or `null` when the atlas is full.
     *
     * Coordinates are packed into one `Long` so the rare glyph-add path does not
     * allocate.
     */
    private fun reserve(width: Int, height: Int): Long? {
        if (width > size || height > size) {
            return null
        }
        if (penX + width > size) {
            shelfY += shelfHeight
            shelfHeight = 0
            penX = 0
        }
        if (shelfY + height > size) {
            return null
        }
        if (height > shelfHeight) {
            shelfHeight = height
        }

        val position = pack(penX, shelfY)
        penX += width
        return position
    }

    private fun key(codepoint: Int, bold: Boolean, px: Float): Long {
        val sizeBits = px.toBits().toLong() and 0x7FFFFFFFL
        val boldBit = if (bold) 1L else 0L
        return (codepoint.toLong() shl KEY_SIZE_SHIFT) or (boldBit shl KEY_BOLD_SHIFT) or sizeBits
    }

    private fun pack(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    private fun unpackX(packed: Long): Int = (packed ushr 32).toInt()

    private fun unpackY(packed: Long): Int = packed.toInt()
}
