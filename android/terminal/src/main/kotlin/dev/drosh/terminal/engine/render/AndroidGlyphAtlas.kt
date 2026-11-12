package dev.drosh.terminal.engine.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One glyph's mask: where it sits in the atlas and how it sits on the line.
 */
internal class GlyphMask(
    /** Atlas position of the mask's left column, in texels. */
    val x: Int,
    /** Atlas position of the mask's top row, in texels. */
    val y: Int,
    val width: Int,
    val height: Int,
    /**
     * The mask's left edge relative to the pen, in pixels.
     *
     * A monospace cell's pen is its left edge, so this is usually zero; it is
     * here because a font may overhang its advance and a glyph that hung
     * outside its cell would be clipped by the next one.
     */
    val left: Int,
    /**
     * The mask's top edge relative to the cell's top, in pixels.
     *
     * Zero, and not the baseline's offset: the mask is the font's line box
     * (ascent to descent) and the cell is that same box measured with the same
     * font, so their tops are the same line. It stays a field because the mask
     * is a rasterised thing and the cell is a measured one, and the renderer is
     * the one place that resolves the sub-pixel between them.
     */
    val top: Int,
) {
    val isBlank: Boolean get() = width <= 0 || height <= 0
}

/**
 * Glyphs, rasterised by Android and packed into one texture.
 *
 * ## Why its own
 *
 * The old atlas rasterised through the Rust core's font stack — a shaper, a
 * rasteriser and a font loader that belong to an engine on its way out. The
 * Ghostty path never loads a font into it, so every mask came back blank and
 * the terminal drew backgrounds with no text on them.
 *
 * Android has a shaper and a rasteriser: `Paint` with a monospace typeface, and
 * a `Bitmap` to draw into. For a terminal that is enough and it is the platform
 * the app already runs on.
 *
 * ## The packing
 *
 * Shelves: fill a row left to right, then start the next below the tallest
 * glyph in it. Simple, and a terminal's glyphs are all about the same height,
 * so the rows pack almost perfectly.
 *
 * ## The format
 *
 * `ALPHA_8`: one byte per texel, the glyph's coverage. Colour lives in the
 * vertex buffer, so a program changing its palette does not re-rasterise
 * anything.
 */
internal class TextAtlas(
    val size: Int = ATLAS_SIZE,
) {

    private val paint = Paint().apply {
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
        color = 0xFFFFFFFF.toInt()
    }

    private val boldPaint = Paint(paint).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    /** A row of transparent pixels, for clearing the bitmap a row at a time. */
    private val clearRow = IntArray(ATLAS_SIZE)

    /** The atlas, one byte per texel. */
    private val pixels: ByteBuffer = ByteBuffer
        .allocateDirect(size * size)
        .order(ByteOrder.nativeOrder())

    private val bitmap: Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)

    /** Set when a mask was added and the texture has to be re-uploaded. */
    var dirty = true

    /**
     * The topmost row that changed since the last upload.
     *
     * The atlas is a square of megabytes and a pinch changes its size on
     * every step of the gesture, so uploading all of it every frame is four
     * megabytes a frame and a GPU that cannot keep up. Only the rows that
     * changed are uploaded, and this is where they start.
     */
    private var dirtyTopY: Int = Int.MAX_VALUE

    /** The size the current masks were rasterised at, in texels. */
    private var rasterPx: Float = 0f

    /** The factor the current masks were rasterised at. */
    private var rasterScale: Float = 1f

    /** The shelf being filled, and where the next one starts. */
    private var penX = 0
    private var penY = 0
    private var shelfHeight = 0

    /** Masks, keyed by codepoint and weight. */
    private val cache = HashMap<Long, GlyphMask?>(256)

    /** How much of the atlas is in use, for a diagnostic surface. */
    val usedPercent: Int
        get() = if (size <= 0) 0 else (penY * 100) / size

    /**
     * Start a new generation at [px].
     *
     * A pinch changes the size the glyphs are rasterised at; masks of the old
     * size are the wrong masks, and the cache is keyed by size so the ones the
     * gesture wanders back over are still there.
     *
     * Small sizes are rasterised at twice the size and drawn at half of it.
     * A terminal's cell is a fraction of a pixel wide — the text is measured,
     * not rounded — so a mask rasterised at the cell's own size is sampled
     * between texels and the linear filter softens every stroke. At a large
     * size that is invisible; at a small one the strokes are a pixel wide and
     * the softening is the difference between sharp text and a grey blur.
     * Rasterising twice as large and letting the GPU halve it is the same
     * answer with more to spare.
     */
    fun beginSize(px: Float) {
        val scale = supersampleFor(px)
        val rounded = if (px <= 0f) 0f else kotlin.math.round(px * scale)
        if (rounded == rasterPx && scale == rasterScale) return
        rasterPx = rounded
        rasterScale = scale
        paint.textSize = rounded
        boldPaint.textSize = rounded
    }

    /**
     * The factor the current masks are rasterised at, relative to the font's
     * own size.
     *
     * One for a large font, two for a small one. The renderer divides the
     * mask's size by it to get the size to draw at.
     */
    val scale: Float get() = rasterScale

    /**
     * The cache key for a mask: the character, its weight, and the size it was
     * rasterised at.
     *
     * The size is part of the key because the size is part of the mask. A key
     * without it returned the mask from the *previous* size after a pinch — the
     * glyphs stayed the size they were while the cells around them grew, which
     * is a terminal whose letters no longer fit their own cells.
     */
    private fun keyFor(codepoint: Int, bold: Boolean): Long =
        (codepoint.toLong() shl 17) or
            ((if (bold) 1L else 0L) shl 16) or
            rasterPx.toLong()

    /**
     * The rows that changed since the last upload, or null when nothing did.
     *
     * The range is over the atlas's rows, and it is what the renderer hands
     * to the texture upload.
     */
    fun dirtyRows(): IntRange? {
        if (dirtyTopY == Int.MAX_VALUE) return null
        val top = dirtyTopY.coerceIn(0, size)
        val bottom = (penY + shelfHeight).coerceIn(top, size)
        if (bottom <= top) return null
        return top until bottom
    }

    /** Forget what changed: the texture has it now. */
    fun clearDirty() {
        dirtyTopY = Int.MAX_VALUE
    }

    /** Drop every mask. The font changed, and a mask of the wrong face is the wrong mask. */
    fun reset() {
        cache.clear()
        penX = 0
        penY = 0
        shelfHeight = 0
        pixels.clear()
        dirtyTopY = Int.MAX_VALUE
        // A zeroed buffer is a transparent atlas, which is what "no glyphs"
        // looks like; the re-upload below publishes it.
        while (pixels.hasRemaining()) pixels.put(0)
        pixels.clear()
        // The bitmap is where the glyphs are actually drawn, so it has to be
        // cleared too — otherwise a new glyph is drawn over the old one and
        // carries its edges with it. `setPixels` rather than `eraseColor`:
        // the bitmap is ALPHA_8, and that is the call that is defined for it.
        for (row in 0 until size) {
            bitmap.setPixels(clearRow, 0, size, 0, row, size, 1)
        }
        dirty = true
    }

    /**
     * The atlas bytes, for the texture upload.
     *
     * The buffer's limit is its capacity: the upload reads a window of it, and
     * the atlas writes into the whole of it with absolute puts, which are
     * checked against the limit.
     */
    fun directPixels(): ByteBuffer {
        pixels.limit(pixels.capacity())
        return pixels
    }

    /**
     * The mask for a codepoint at the current size, or null when the font has
     * no glyph for it.
     */
    fun glyph(codepoint: Int, bold: Boolean, px: Float): GlyphMask? {
        beginSize(px)
        if (rasterPx <= 0f) return null

        val key = keyFor(codepoint, bold)
        cache[key]?.let { return it }
        if (cache.containsKey(key)) return null

        val mask = rasterise(codepoint, bold)
        cache[key] = mask
        return mask
    }

    private fun rasterise(codepoint: Int, bold: Boolean): GlyphMask? {
        if (codepoint <= 0 || codepoint > Character.MAX_CODE_POINT) return null

        val text = String(Character.toChars(codepoint))
        val p = if (bold) boldPaint else paint

        val advance = p.measureText(text)
        val metrics = p.fontMetrics
        val width = kotlin.math.ceil(advance).toInt()
        val height = kotlin.math.ceil(metrics.descent - metrics.ascent).toInt()
        if (width <= 0 || height <= 0) return null

        // A space is an advance and nothing else: reserving a mask for it would
        // fill the atlas with blank rectangles.
        if (text.isBlank()) {
            return GlyphMask(x = 0, y = 0, width = 0, height = 0, left = 0, top = 0)
        }

        // The atlas is finite and a pinch asks for a mask at every size it
        // passes through. When it fills, the atlas starts over rather than
        // refusing the glyph: a missing mask is a hole in a letter, and a
        // re-laid atlas is a re-rasterised letter.
        var slot = reserve(width, height)
        if (slot == null) {
            reset()
            slot = reserve(width, height) ?: return null
        }

        // Drawn on the CPU once: a terminal draws the same few hundred glyphs
        // forever, and the atlas is the cache.
        val canvas = Canvas(bitmap)
        canvas.save()
        canvas.clipRect(slot.x, slot.y, slot.x + width, slot.y + height)
        canvas.drawText(text, slot.x.toFloat(), slot.y - metrics.ascent, p)
        canvas.restore()

        // Only the slot that was just drawn is copied out. Copying the whole
        // bitmap would move four megabytes per new glyph, and a first frame can
        // introduce forty.
        val row = IntArray(width)
        for (line in 0 until height) {
            bitmap.getPixels(row, 0, width, slot.x, slot.y + line, width, 1)
            val base = (slot.y + line) * size + slot.x
            for (column in 0 until width) {
                // ALPHA_8 comes back as ARGB with the coverage in alpha.
                pixels.put(base + column, (row[column] ushr 24 and 0xff).toByte())
            }
        }
        dirty = true
        if (slot.y < dirtyTopY) dirtyTopY = slot.y

        return GlyphMask(
            x = slot.x,
            y = slot.y,
            width = width,
            height = height,
            left = 0,
            // The mask's first row is the font's ascent line: `drawText` puts
            // the baseline at `slot.y - ascent`, so everything the glyph draws
            // sits below that line and `height` covers the descent under it.
            // The cell's top is the same line, because the cell is measured as
            // `descent - ascent`.
            //
            // `-ascent` is therefore *not* this offset: it is how far the
            // baseline sits below the top of the mask. Adding it here placed
            // the whole line box one ascent — about four fifths of a cell —
            // below the cell it belongs to, which is text hanging under its
            // coloured block and a cursor that reads as a line above it.
            top = 0,
        )
    }

    /** A free rectangle, or null when the atlas is full. */
    private fun reserve(width: Int, height: Int): Slot? {
        if (width > size || height > size) return null

        if (penX + width > size) {
            // Next shelf.
            penX = 0
            penY += shelfHeight
            shelfHeight = 0
        }
        if (penY + height > size) return null

        val slot = Slot(penX, penY)
        penX += width
        if (height > shelfHeight) shelfHeight = height
        return slot
    }

    private class Slot(val x: Int, val y: Int)

    private companion object {
        /**
         * The atlas is square and one byte per texel.
         *
         * 2048² is four megabytes: enough for every glyph a terminal shows at
         * any size it can be pinched to, and small enough to allocate on every
         * device that runs the app.
         */
        const val ATLAS_SIZE = 2048

        /**
         * The font size below which the masks are rasterised twice as large.
         *
         * A small font's strokes are a pixel wide, and a mask sampled between
         * texels loses them. Above this size the softening is invisible, and
         * the atlas has room to spare.
         */
        const val SUPERSAMPLE_BELOW_PX = 24f

        /** The factor a small font's masks are rasterised at. */
        const val SUPERSAMPLE = 2f

        /** The factor a font of [px] is rasterised at. */
        fun supersampleFor(px: Float): Float =
            if (px > 0f && px <= SUPERSAMPLE_BELOW_PX) SUPERSAMPLE else 1f
    }
}
