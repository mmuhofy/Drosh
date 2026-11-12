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
        layout(location = 2) in vec4 aUnderline;
        uniform vec4 uProjection;
        uniform vec2 uCellSize;
        // A varying that both stages declare must declare the same precision
        // in both: the vertex stage's floats are highp by default and the
        // fragment stage's are whatever it says, and a mismatch is a link
        // error, not a warning.
        out highp vec4 vColor;
        out highp vec4 vUnderline;
        // Where the fragment sits inside its cell, in cells.
        out highp vec2 vLocal;
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
            vUnderline = aUnderline;
            vLocal = corner;
        }
    """.trimIndent()

    val BACKGROUND_FRAGMENT = """
        #version 300 es
        precision mediump float;
        // The same precision the vertex stage declares them with.
        in highp vec4 vColor;
        in highp vec4 vUnderline;
        in highp vec2 vLocal;
        out vec4 fragColor;
        void main() {
            fragColor = vColor;
            // The underline, if the cell has one.
            //
            // It is drawn here, in the cell's own quad, rather than as a
            // glyph: an underline is a rule under the text, and the rule
            // belongs to the cell, not to the character in it. The alpha says
            // whether there is one at all — a cell with no underline carries
            // a transparent colour and nothing is drawn.
            // 0.06 of the cell's height, and never more than a twentieth of
            // it: on a small cell a thicker rule would start eating the text
            // above it.
            if (vUnderline.a > 0.0 && vLocal.y > 0.94) {
                fragColor = vUnderline;
            }
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
        // How much larger the mask was rasterised than the font is. A small
        // font's mask is rasterised at twice the size and drawn at half of it,
        // so that a stroke one pixel wide survives being sampled between
        // texels.
        uniform float uAtlasScale;
        // The varyings are declared by both stages, so both say highp: the
        // vertex stage's floats are highp by default and the fragment
        // stage's are mediump, and a mismatch is a link error.
        out highp vec2 vTexCoord;
        out highp vec4 vColor;
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

            // The mask's size, in atlas texels.
            vec2 natural = vec2(aGlyphSize);

            // The size to draw it at: the font's size, not the mask's. The
            // mask may have been rasterised larger than the font, and the
            // extra resolution is what keeps a small font's strokes sharp.
            vec2 drawn = natural / uAtlasScale;

            // The offset is where the glyph sits inside its cell, in the
            // rasterised mask's pixels; it does not change when the font does,
            // because the pen position is the cell's.
            vec2 offset = vec2(aOffset) / uAtlasScale;

            float targetWidth = uCellSize.x * float(max(aSpan, 1u));
            float squeeze = (drawn.x > 0.0) ? min(1.0, targetWidth / drawn.x) : 1.0;

            vec2 size = vec2(drawn.x * squeeze, drawn.y);

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
    /**
     * The pictures a program drew.
     *
     * A picture is a rectangle in the surface's own pixels — the program put it
     * at a cell and an offset — and a rectangle of the picture to show. The
     * source is normalized on the CPU, so the shader needs nothing but the
     * texture.
     */
    val IMAGE_VERTEX = """
        #version 300 es
        layout(location = 0) in vec4 aRect;
        layout(location = 1) in vec4 aSource;
        uniform vec4 uProjection;
        out vec2 vUv;
        void main() {
            vec2 corner = vec2(
                float(gl_VertexID == 1 || gl_VertexID == 3),
                float(gl_VertexID == 2 || gl_VertexID == 3)
            );
            vec2 pixel = aRect.xy + corner * aRect.zw;
            gl_Position = vec4(
                uProjection.xy + uProjection.zw * pixel,
                0.0,
                1.0
            );
            vUv = aSource.xy + corner * aSource.zw;
        }
    """.trimIndent()

    val IMAGE_FRAGMENT = """
        #version 300 es
        precision mediump float;
        uniform sampler2D uTexture;
        in vec2 vUv;
        out vec4 fragColor;
        void main() {
            fragColor = texture(uTexture, vUv);
        }
    """.trimIndent()

    val CURSOR_VERTEX = """
        #version 300 es
        uniform vec4 uProjection;
        uniform vec2 uCellSize;
        uniform highp vec4 uRect;
        // Where the fragment sits inside the cursor's rectangle, in cells.
        out highp vec2 vLocal;
        void main() {
            int id = gl_VertexID;
            vec2 corner = vec2(
                float(id == 1 || id == 3),
                float(id == 2 || id == 3)
            );
            vec2 cell = (uRect.xy + corner * uRect.zw) * uCellSize;
            vLocal = corner * uRect.zw;
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
        // One when the cursor is drawn as an outline rather than a filled box.
        uniform highp float uHollow;
        // The cursor's rectangle in cells, so the outline knows where its edges
        // are. The vertex stage declares it too, so both say highp.
        uniform highp vec4 uRect;
        // The frame's thickness, in cell units.
        //
        // The thickness is a real width on the screen, so it is worked out
        // where the cell's size is known — here the uniform would be the
        // *fragment* stage's own, which is not the one the vertex stage sets.
        uniform highp float uEdge;
        in highp vec2 vLocal;
        out vec4 fragColor;

        void main() {
            if (uHollow > 0.5) {
                // The hollow cursor is a frame: the middle is the terminal's
                // own colours, so the text under it stays readable and the
                // cursor still says where it is.
                //
                // At least a pixel and a half on the screen, and never more
                // than a quarter of the cell — a frame measured only in cell
                // fractions is invisible on a small cell and a slab on a large
                // one.
                float edge = uEdge;
                float left = vLocal.x;
                float right = uRect.z - vLocal.x;
                float top = vLocal.y;
                float bottom = uRect.w - vLocal.y;
                float border = min(min(left, right), min(top, bottom));
                if (border > edge) discard;
            }
            fragColor = uColor;
        }
    """.trimIndent()

    val FOREGROUND_FRAGMENT = """
        #version 300 es
        precision mediump float;
        // The same precision the vertex stage declares them with.
        in highp vec2 vTexCoord;
        in highp vec4 vColor;
        uniform sampler2D uAtlas;
        out vec4 fragColor;
        void main() {
            float coverage = texture(uAtlas, vTexCoord).r;
            fragColor = vec4(vColor.rgb, vColor.a * coverage);
        }
    """.trimIndent()
}
