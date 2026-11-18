package dev.drosh.terminal.engine.font

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One colour glyph: where it sits in the atlas and how it sits on the line.
 */
internal class ColorGlyph(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val isBlank: Boolean get() = width <= 0 || height <= 0
}

/**
 * The emoji, rasterised by Android into one colour texture.
 *
 * ## Why a second atlas
 *
 * The text atlas is one byte per texel, because a monospace glyph is a mask:
 * its colour comes from the cell. An emoji is the opposite — the glyph *is* a
 * picture, and the platform's emoji font draws it in its own colours. Shoving
 * it into the alpha atlas throws the colour away and leaves a black
 * silhouette, which is what an emoji used to look like.
 *
 * Ghostty splits the same way: an `atlas_grayscale` and an `atlas_color`, and
 * a renderer that samples one or the other per glyph. This is the second half
 * of that pair.
 *
 * ## The packing
 *
 * Skyline-packed, like the text atlas ([Skyline]): emoji are square-ish and
 * all the same size, so they pack tightly and the atlas rarely starts over.
 */
internal class ColorAtlas(
    val size: Int = ATLAS_SIZE,
) {

    private val paint = Paint().apply {
        // The platform's own emoji font, through the default fallback chain:
        // the monospace face has no emoji, and Android's fallback draws the
        // system emoji in colour.
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        isAntiAlias = true
        isFilterBitmap = true
    }

    /** The atlas, four bytes per texel: the emoji's own colours. */
    private val pixels: ByteBuffer = ByteBuffer
        .allocateDirect(size * size * 4)
        .order(ByteOrder.nativeOrder())

    private val bitmap: Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    /** Set when a glyph was added and the texture has to be re-uploaded. */
    var dirty = true

    /**
     * Set when the whole atlas was wiped and has to go up as one.
     *
     * The same rule as the text atlas: a reset clears the bitmap, and a
     * texture that is uploaded in pieces after it keeps the glyphs the cache
     * no longer has.
     */
    private var fullDirty: Boolean = false

    private var dirtyTopY: Int = Int.MAX_VALUE

    /** The size the current glyphs were rasterised at, in texels. */
    private var rasterPx: Float = 0f

    /** The factor the current glyphs were rasterised at. */
    private var rasterScale: Float = 1f

    /** The drawn size is [rasterPx] divided by this. */
    val scale: Float get() = rasterScale

    /** Incremented on every reset, so a consumer knows its rects are stale. */
    var generation: Int = 0
        private set

    /** The same packer the text atlas uses, for the same reason. */
    private val packer = Skyline(size, size)

    /** Glyphs, keyed by codepoint and the size they were drawn at. */
    private val cache = HashMap<Long, ColorGlyph?>(64)

    /** How much of the atlas is in use, for the diagnostic surface. */
    val usedPercent: Int
        get() = if (size <= 0) 0 else (packer.usedBottom * 100) / size

    /**
     * The glyph for [codepoint] at [px], or null when the platform has none.
     *
     * @param px the cell's height, which is the box an emoji is fitted into.
     */
    fun glyph(codepoint: Int, px: Float): ColorGlyph? {
        beginSize(px)
        if (rasterPx <= 0f) return null

        val key = (codepoint.toLong() shl 20) or rasterPx.toLong()
        cache[key]?.let { return it }
        if (cache.containsKey(key)) return null

        val glyph = rasterise(codepoint)
        cache[key] = glyph
        return glyph
    }

    /** The atlas's bytes, for the texture upload. */
    fun directPixels(): ByteBuffer {
        pixels.limit(pixels.capacity())
        return pixels
    }

    /** The rows that changed, or null when nothing did. */
    fun dirtyRows(): IntRange? {
        if (fullDirty) return 0 until size
        if (dirtyTopY == Int.MAX_VALUE) return null
        val top = dirtyTopY.coerceIn(0, size)
        val bottom = packer.usedBottom.coerceIn(top, size)
        if (bottom <= top) return null
        return top until bottom
    }

    /** Forget what changed: the texture has it now. */
    fun clearDirty() {
        dirtyTopY = Int.MAX_VALUE
        fullDirty = false
    }

    /** Drop every glyph. A new size is a new glyph, and the old ones are wrong. */
    fun reset() {
        cache.clear()
        packer.reset()
        pixels.clear()
        for (index in 0 until pixels.capacity()) pixels.put(index, 0)
        pixels.clear()
        bitmap.eraseColor(0)
        dirtyTopY = Int.MAX_VALUE
        fullDirty = true
        dirty = true
        generation += 1
    }

    /**
     * Draw at [px] from now on.
     *
     * The size is part of the cache key, so a pinch that wanders back over a
     * size finds the glyphs it drew then. Nothing is thrown away: the atlas
     * only starts over when it is full.
     */
    private fun beginSize(px: Float) {
        // The same rule as the text atlas: a small glyph rasterised at its own
        // size loses its colour edges between texels. Twice the size, drawn at
        // half on the GPU, keeps them.
        val scale = if (px > 0f && px <= SUPERSAMPLE_BELOW_PX) 2f else 1f
        val rounded = if (px <= 0f) 0f else kotlin.math.round(px * scale)
        if (rounded == rasterPx && scale == rasterScale) return
        // The size is in the cache key, so the sizes a pinch wanders back over
        // are still there — no new atlas on every step of a gesture.
        rasterPx = rounded
        rasterScale = scale
        paint.textSize = rounded
    }

    private fun rasterise(codepoint: Int): ColorGlyph? {
        if (codepoint <= 0 || codepoint > Character.MAX_CODE_POINT) return null
        val text = String(Character.toChars(codepoint))

        // An emoji is drawn into a square the cell's height, which is the box
        // it occupies on the grid: a terminal gives an emoji two columns and
        // one row.
        // The emoji font draws outside the nominal em box: a glyph measured at
        // N pixels can cover more than N. A slot of exactly N cut the edges off
        // — "kesik kesik" — and two neighbouring slots bled into each other.
        // Reserve a padded square and draw the glyph in its centre; the pad is
        // transparent and disappears when the quad is sampled.
        val extent = kotlin.math.ceil(rasterPx * GLYPH_PAD).toInt()
        if (extent <= 0 || extent > size) return null

        var slot = reserve(extent, extent)
        if (slot == null) {
            reset()
            slot = reserve(extent, extent) ?: return null
        }

        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.clipRect(slot.x, slot.y, slot.x + extent, slot.y + extent)
        val metrics = paint.fontMetrics
        // Centred in the square: a glyph drawn from the pen would hang below
        // the box and be clipped.
        val baseline = slot.y + extent / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, slot.x + extent / 2f, baseline, paint)
        canvas.restore()

        // Only the slot is copied out: the whole bitmap is four megabytes.
        val row = IntArray(extent)
        for (line in 0 until extent) {
            bitmap.getPixels(row, 0, extent, slot.x, slot.y + line, extent, 1)
            for (column in 0 until extent) {
                val colour = row[column]
                val base = ((slot.y + line) * size + slot.x + column) * 4
                pixels.put(base, (colour ushr 16 and 0xff).toByte())
                pixels.put(base + 1, (colour ushr 8 and 0xff).toByte())
                pixels.put(base + 2, (colour and 0xff).toByte())
                pixels.put(base + 3, (colour ushr 24 and 0xff).toByte())
            }
        }

        dirty = true
        if (slot.y < dirtyTopY) dirtyTopY = slot.y

        return ColorGlyph(x = slot.x, y = slot.y, width = extent, height = extent)
    }

    private fun reserve(width: Int, height: Int): Slot? {
        val rect = packer.reserve(width, height) ?: return null
        return Slot(rect.x, rect.y)
    }

    private class Slot(val x: Int, val y: Int)

    private companion object {
        /** 1024² at four bytes is four megabytes: room for every emoji at once. */
        const val ATLAS_SIZE = 1024

        /** Below this cell height an emoji is rasterised twice as large. */
        const val SUPERSAMPLE_BELOW_PX = 24f

        /** Room around an emoji so its ink cannot be clipped by the slot. */
        const val GLYPH_PAD = 1.4f
    }
}