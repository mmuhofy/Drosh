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

    /**
     * Where an emoji is drawn once before it is fitted into its slot.
     *
     * An emoji's ink is measured from pixels rather than from
     * `Paint.getTextBounds`: a colour emoji is a bitmap font, and the bounds a
     * text measurement returns for it are not the picture that is drawn. The
     * two disagreed, so the fitted size was wrong by a factor that varied per
     * codepoint — the emoji was drawn larger than its slot and clipped to a
     * fragment of itself, and adding one beside it changed the measurement of
     * the other. Pixels cannot disagree with the pixels that are drawn.
     *
     * One scratch picture, reused: it is only touched when a codepoint is
     * rasterised, and every codepoint is cached after that.
     */
    private val scratch: Bitmap =
        Bitmap.createBitmap(SCRATCH_PX, SCRATCH_PX, Bitmap.Config.ARGB_8888)
    private val scratchCanvas = Canvas(scratch)

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
        val scale = rasterScale
        val widthPx = kotlin.math.round(span * cellWidth * scale).toInt()
        val heightPx = kotlin.math.round(rasterPx).toInt()
        if (widthPx <= 0 || heightPx <= 0 || widthPx > size || heightPx > size) return null

        // Drawn once at a nominal size, then measured from its own pixels.
        // `getTextBounds` is not used: for a bitmap emoji font it reports a
        // box that is not the picture, and a fit computed from it drew the
        // emoji past its slot — clipped to a fragment — with the factor
        // changing from one codepoint to the next.
        paint.textSize = NOMINAL_PX
        scratchCanvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR)
        val centre = SCRATCH_PX / 2f
        scratchCanvas.drawText(text, centre, centre - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f, paint)

        val ink = inkBounds()
        if (ink == null) return null

        // A texel of transparent room around the slot, so the atlas's linear
        // filter never samples a neighbouring emoji at the slot's edge.
        val paddedWidth = widthPx + SAMPLE_PAD * 2
        val paddedHeight = heightPx + SAMPLE_PAD * 2

        var slot = reserve(paddedWidth, paddedHeight)
        if (slot == null) {
            reset()
            slot = reserve(paddedWidth, paddedHeight) ?: return null
        }

        // The ink, scaled to the slot with its aspect kept and centred in it.
        val room = INK_ROOM
        val fit = minOf(
            widthPx * room / ink.width().toFloat(),
            heightPx * room / ink.height().toFloat(),
        )
        val destWidth = ink.width() * fit
        val destHeight = ink.height() * fit
        val destLeft = slot.x + SAMPLE_PAD + (widthPx - destWidth) / 2f
        val destTop = slot.y + SAMPLE_PAD + (heightPx - destHeight) / 2f

        val canvas = Canvas(bitmap)
        canvas.drawBitmap(
            scratch,
            android.graphics.Rect(ink.left, ink.top, ink.right, ink.bottom),
            android.graphics.RectF(destLeft, destTop, destLeft + destWidth, destTop + destHeight),
            paint,
        )

        // Only the slot is copied out: the whole bitmap is four megabytes.
        val row = IntArray(paddedWidth)
        for (line in 0 until paddedHeight) {
            bitmap.getPixels(row, 0, paddedWidth, slot.x, slot.y + line, paddedWidth, 1)
            for (column in 0 until paddedWidth) {
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

        return ColorGlyph(
            x = slot.x + SAMPLE_PAD,
            y = slot.y + SAMPLE_PAD,
            width = widthPx,
            height = heightPx,
        )
    }

    /**
     * The box the last scratch draw's ink occupies, or null when it drew
     * nothing.
     *
     * Read one row at a time: the scratch picture is under a megabyte, and the
     * emoji is the only thing in it, so a bounding box found from its opaque
     * pixels is the picture itself.
     */
    private fun inkBounds(): android.graphics.Rect? {
        val row = IntArray(SCRATCH_PX)
        var left = SCRATCH_PX
        var top = SCRATCH_PX
        var right = -1
        var bottom = -1
        for (y in 0 until SCRATCH_PX) {
            scratch.getPixels(row, 0, SCRATCH_PX, 0, y, SCRATCH_PX, 1)
            for (x in 0 until SCRATCH_PX) {
                // Anything less than this is antialiasing haze, not ink.
                if (row[x] ushr 24 < INK_ALPHA) continue
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return null
        return android.graphics.Rect(left, top, right + 1, bottom + 1)
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

        /**
         * The scratch picture's side, in texels.
         *
         * Twice the nominal size: a glyph drawn at [NOMINAL_PX] fits in it with
         * room for the ones that hang below their baseline or draw past their
         * em box. Nothing is clipped by it, so the ink it reports is the whole
         * picture, and the picture is small enough to scan in one frame.
         */
        const val SCRATCH_PX = 256

        /** Transparent texels around a slot, so filtering cannot reach a neighbour. */
        const val SAMPLE_PAD = 1

        /** The coverage below which a texel is haze rather than ink. */
        const val INK_ALPHA = 8
    }
}