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

    /** The cell the current glyphs were fitted to, in texels. */
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

    /** Glyphs, keyed by the codepoint, the cells it covers and the cell size. */
    private val cache = HashMap<Key, ColorGlyph?>(64)

    private data class Key(val codepoint: Int, val span: Int, val width: Float, val height: Float)

    /** How much of the atlas is in use, for the diagnostic surface. */
    val usedPercent: Int
        get() = if (size <= 0) 0 else (packer.usedBottom * 100) / size

    /**
     * The glyph for [codepoint] in the cells it covers, or null when the
     * platform has none.
     *
     * @param span how many cells wide the emoji is — one, or two for the wide
     *   ones.
     * @param cellWidth the cell's width in pixels.
     * @param cellHeight the cell's height in pixels: the line box the emoji has
     *   to sit in, so that it is the size of the text beside it.
     */
    fun glyph(codepoint: Int, span: Int, cellWidth: Float, cellHeight: Float): ColorGlyph? {
        beginSize(cellHeight)
        if (rasterPx <= 0f || cellWidth <= 0f) return null
        val s = span.coerceAtLeast(1)

        val key = Key(codepoint, s, cellWidth, cellHeight)
        cache[key]?.let { return it }
        if (cache.containsKey(key)) return null

        val glyph = rasterise(codepoint, s, cellWidth)
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

    private fun rasterise(codepoint: Int, span: Int, cellWidth: Float): ColorGlyph? {
        if (codepoint <= 0 || codepoint > Character.MAX_CODE_POINT) return null
        val text = String(Character.toChars(codepoint))

        // The slot is the cells the emoji covers, exactly: span cells wide and
        // one cell tall. The glyph is fitted *inside* that slot with its own
        // aspect ratio kept, so the quad can be drawn 1:1 over the cells — the
        // emoji is the height of the text beside it, it is never squashed
        // sideways, and two emoji standing next to each other each stay inside
        // their own cells instead of being stretched into one another.
        //
        // It used to be rasterised with a 1.4× pad into a square, which is why
        // an emoji changed size with the font and never matched the line it sat
        // on: the quad and the slot described different boxes.
        val scale = rasterScale
        val widthPx = kotlin.math.round(span * cellWidth * scale).toInt()
        val heightPx = kotlin.math.round(rasterPx).toInt()
        if (widthPx <= 0 || heightPx <= 0 || widthPx > size || heightPx > size) return null

        var slot = reserve(widthPx, heightPx)
        if (slot == null) {
            reset()
            slot = reserve(widthPx, heightPx) ?: return null
        }

        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.clipRect(slot.x, slot.y, slot.x + widthPx, slot.y + heightPx)

        // Measure the ink at a nominal size, then scale it so it fills the
        // slot: the largest the glyph can be without touching its edges. The
        // bounds are the ink, not the em box, so a font whose emoji sit high
        // above the baseline still lands centred in its cell.
        val bounds = android.graphics.Rect()
        paint.textSize = NOMINAL_PX
        paint.getTextBounds(text, 0, text.length, bounds)
        val inkWidth = bounds.width().coerceAtLeast(1)
        val inkHeight = bounds.height().coerceAtLeast(1)
        val room = INK_ROOM
        val fit = minOf(
            (widthPx * room) / inkWidth.toFloat(),
            (heightPx * room) / inkHeight.toFloat(),
        )
        paint.textSize = NOMINAL_PX * fit

        // Centred on the ink's own bounds, so the slot is filled top and bottom
        // whatever the font's baseline does.
        val fitted = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, fitted)
        val baseline = slot.y + heightPx / 2f - (fitted.top + fitted.bottom) / 2f
        canvas.drawText(text, slot.x + widthPx / 2f, baseline, paint)
        canvas.restore()

        // Only the slot is copied out: the whole bitmap is four megabytes.
        val row = IntArray(widthPx)
        for (line in 0 until heightPx) {
            bitmap.getPixels(row, 0, widthPx, slot.x, slot.y + line, widthPx, 1)
            for (column in 0 until widthPx) {
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

        return ColorGlyph(x = slot.x, y = slot.y, width = widthPx, height = heightPx)
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

        /**
         * How much of its slot an emoji's ink is allowed to take.
         *
         * The whole slot, less a hair: an emoji drawn edge to edge touches the
         * cell beside it, and the antialiased boundary then reads as a seam.
         */
        const val INK_ROOM = 0.98f

        /** The size the ink is measured at before it is scaled to its slot. */
        const val NOMINAL_PX = 128f
    }
}