package dev.drosh.terminal.engine.font

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
