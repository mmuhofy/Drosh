package dev.drosh.terminal.engine.render

/**
 * The shaders, as text.
 *
 * ## Two programs, one geometry
 *
 * The terminal is drawn as two instanced passes over the same grid:
 *
 * 1. **Backgrounds** — one quad per cell, a flat colour. This is the bulk of
 *    the pixels and the cheapest possible fragment shader.
 * 2. **Foregrounds** — one quad per glyph, sampling the atlas's coverage
 *    texture and tinting it with the cell's colour.
 *
 * Both take the grid position as an unsigned short pair and multiply by the
 * cell size on the GPU, so a resize costs a uniform update rather than a
 * rebuild of every vertex.
 *
 * ## Why `#version 300 es`
 *
 * GLES 3.0 gives instanced arrays (`glVertexAttribDivisor`), which is what makes
 * one draw call per pass possible. The fallback for GLES 2 would be a buffer
 * rebuild per frame; every device that runs this app has GLES 3.
 *
 * ## The projection
 *
 * `clip = xy + zw * pixel`. A plain pixel-to-clip map, with no scale in it. The
 * pinch lives in the glyph's own size, not here: scaling the projection would
 * zoom the whole surface — grid, cursor, everything — which is an image zoom
 * rather than a text size.
 */
internal object Shaders {

    /**
     * A cell's background.
     *
     * The grid row is signed: the frame's overscan rows sit above and below the
     * viewport, and a row above is `-1`. Stored as an unsigned short in the
     * record, so the shader reinterprets the top half of the range.
     */
    val BACKGROUND_VERTEX = """
        #version 300 es
        layout(location = 0) in uvec2 aGridPos;
        layout(location = 1) in vec4 aColor;
        uniform vec4 uProjection;
        uniform vec2 uCellSize;
        out vec4 vColor;
        void main() {
            vec2 corner = vec2(
                float(gl_VertexID == 1 || gl_VertexID == 3),
                float(gl_VertexID == 2 || gl_VertexID == 3)
            );
            int gridRow = int(aGridPos.y);
            if (gridRow > 32767) {
                gridRow -= 65536;
            }
            vec2 cell = vec2(float(aGridPos.x), float(gridRow)) * uCellSize;
            gl_Position = vec4(
                uProjection.xy + uProjection.zw * (cell + corner * uCellSize),
                0.0,
                1.0
            );
            vColor = aColor;
        }
    """.trimIndent()

    val BACKGROUND_FRAGMENT = """
        #version 300 es
        precision mediump float;
        in vec4 vColor;
        out vec4 fragColor;
        void main() {
            fragColor = vColor;
        }
    """.trimIndent()

    /**
     * A glyph.
     *
     * `aGlyphSize` and `aOffset` are in pixels at the size the mask was
     * rasterised — they describe the glyph, not the cell. A pinch re-rasterises
     * at the new size rather than scaling these: a bitmap scaled up by
     * nearest-neighbour doubles its strokes, which is a blurry font rather than
     * a large one.
     *
     * A glyph wider than the cell it owns is fitted to it. CJK, an emoji and
     * some symbols are drawn at two or three times the cell width by the font;
     * at their natural size they overlap their neighbours. The cell's width is
     * the terminal's — a column is a column — so the glyph is squeezed into it.
     * A wide character owns two columns, so its target is twice the cell.
     */
    val FOREGROUND_VERTEX = """
        #version 300 es
        layout(location = 0) in uvec2 aAtlasRect;
        layout(location = 1) in uvec2 aGlyphSize;
        layout(location = 2) in ivec2 aOffset;
        layout(location = 3) in uvec2 aGridPos;
        layout(location = 4) in vec4 aColor;
        layout(location = 5) in uint aFlags;
        layout(location = 6) in uint aSpan;
        uniform vec4 uProjection;
        uniform vec2 uCellSize;
        uniform vec2 uAtlasSize;
        out vec2 vTexCoord;
        out vec4 vColor;
        void main() {
            vec2 corner = vec2(
                float(gl_VertexID == 1 || gl_VertexID == 3),
                float(gl_VertexID == 2 || gl_VertexID == 3)
            );

            int gridRow = int(aGridPos.y);
            if (gridRow > 32767) {
                gridRow -= 65536;
            }
            vec2 cell = vec2(float(aGridPos.x), float(gridRow)) * uCellSize;
            vec2 natural = vec2(aGlyphSize);

            // The offset is where the glyph sits inside its cell. It does not
            // change when the font does: the pen position is the cell's.
            vec2 offset = vec2(aOffset);

            float targetWidth = uCellSize.x * float(max(aSpan, 1u));
            float squeeze = (natural.x > 0.0) ? min(1.0, targetWidth / natural.x) : 1.0;

            // A pinch scales the whole picture in the projection, not the glyph
            // inside its cell: growing the glyph alone changes the space around
            // each letter, which is the font's tracking changing rather than the
            // terminal zooming.
            vec2 size = vec2(natural.x * squeeze, natural.y);

            gl_Position = vec4(
                uProjection.xy + uProjection.zw * (cell + offset + corner * size),
                0.0,
                1.0
            );

            // The UV walks the *atlas* texels, which do not scale. Inset by half
            // a texel: GL_LINEAR blends the four texels around every sample, and
            // at a rectangle's edge those include the neighbouring glyph's —
            // the faint line at the letter edges.
            vTexCoord = (aFlags & 1u) != 0u
                ? (vec2(aAtlasRect) + 0.5) / uAtlasSize
                : (vec2(aAtlasRect) + 0.5 + corner * max(natural - 1.0, vec2(0.0))) / uAtlasSize;
            vColor = aColor;
        }
    """.trimIndent()

    /**
     * Single-channel coverage from the atlas, tinted by the cell's colour.
     *
     * The atlas is `GL_R8`: one byte per texel, the glyph's alpha. Storing
     * colour in the atlas would multiply the memory by four and force a
     * re-rasterisation for every colour a program chose.
     */
    /**
     * A rectangle in cell units, for the bar and underline cursors.
     *
     * `uRect` is `(x, y, width, height)` where one unit is one cell. The four
     * corners come from `gl_VertexID`, so there is no vertex buffer: this shader
     * draws exactly one quad and that is all it is for.
     */
    val CURSOR_VERTEX = """
        #version 300 es
        uniform vec4 uProjection;
        uniform vec2 uCellSize;
        uniform vec4 uRect;
        void main() {
            int id = gl_VertexID;
            vec2 corner = vec2(
                float(id == 1 || id == 3),
                float(id == 2 || id == 3)
            );
            vec2 cell = (uRect.xy + corner * uRect.zw) * uCellSize;
            gl_Position = vec4(
                uProjection.xy + uProjection.zw * cell,
                0.0,
                1.0
            );
        }
    """.trimIndent()

    val CURSOR_FRAGMENT = """
        #version 300 es
        precision mediump float;
        uniform vec4 uColor;
        out vec4 fragColor;
        void main() {
            fragColor = uColor;
        }
    """.trimIndent()

    val FOREGROUND_FRAGMENT = """
        #version 300 es
        precision mediump float;
        in vec2 vTexCoord;
        in vec4 vColor;
        uniform sampler2D uAtlas;
        out vec4 fragColor;
        void main() {
            float coverage = texture(uAtlas, vTexCoord).r;
            fragColor = vec4(vColor.rgb, vColor.a * coverage);
        }
    """.trimIndent()
}
