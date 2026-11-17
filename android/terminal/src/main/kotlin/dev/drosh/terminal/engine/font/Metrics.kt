package dev.drosh.terminal.engine.font

/**
 * The measurements of one rasterised glyph.
 *
 * A plain data holder and nothing more: it exists so the font package has the
 * metrics it will need, and nothing uses it yet — deliberately so. It is not
 * wired into the [TextAtlas] or the renderer; until a caller that needs the
 * width, the height and the font size together arrives, this is only a place
 * to put them.
 */
internal data class GlyphMetrics(
    val width: Float,
    val height: Float,
    val fontPx: Float,
)
