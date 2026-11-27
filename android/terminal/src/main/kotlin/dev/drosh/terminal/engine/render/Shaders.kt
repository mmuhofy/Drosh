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
     * A full-screen triangle, with no vertex attributes.
     *
     * Ghostty's background pass uses the same trick: three vertices that cover
     * the viewport, so the clear is a draw like any other and the colour comes
     * from a uniform instead of `glClearColor`. A triangle rather than a quad
     * because it is one primitive and the rasteriser clips the overflow for
     * free.
     */
    val SCREEN_VERTEX = """
        #version 300 es
        void main() {
            vec2 corner = vec2(
                gl_VertexID == 1 ? 3.0 : -1.0,
                gl_VertexID == 2 ? 3.0 : -1.0
            );
            gl_Position = vec4(corner, 0.0, 1.0);
        }
    """.trimIndent()

    val SCREEN_FRAGMENT = """
        #version 300 es
        precision mediump float;
        uniform vec4 uColor;
        out vec4 fragColor;
        void main() {
            fragColor = uColor;
        }
    """.trimIndent()

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
        layout(location = 3) in vec4 aStrikethrough;
        layout(location = 4) in vec4 aOverline;
        uniform vec4 uProjection;
        uniform vec2 uCellSize;
        // A varying that both stages declare must declare the same precision
        // in both: the vertex stage's floats are highp by default and the
        // fragment stage's are whatever it says, and a mismatch is a link
        // error, not a warning.
        out highp vec4 vColor;
        out highp vec4 vUnderline;
        out highp vec4 vStrikethrough;
        out highp vec4 vOverline;
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
            vStrikethrough = aStrikethrough;
            vOverline = aOverline;
            vLocal = corner;
        }
    """.trimIndent()

    val BACKGROUND_FRAGMENT = """
        #version 300 es
        precision mediump float;
        // The same precision the vertex stage declares them with.
        in highp vec4 vColor;
        in highp vec4 vUnderline;
        in highp vec4 vStrikethrough;
        in highp vec4 vOverline;
        in highp vec2 vLocal;
        out vec4 fragColor;
        void main() {
            fragColor = vColor;
            // The decorations, if the cell has them.
            //
            // They are drawn here, in the cell's own quad, rather than as
            // glyphs: a rule belongs to the cell, not to the character in it.
            // Each colour's alpha says whether there is one at all — a cell
            // without it carries a transparent colour and nothing is drawn.
            //
            // The rule is 0.06 of the cell's height: on a small cell a thicker
            // one would start eating the text around it.
            float rule = 0.06;

            // The underline's style is packed in the alpha byte as a small
            // integer: 1 single, 2 double, 3 curly, 4 dotted, 5 dashed. The
            // same vec4 carries the rule's colour in rgb.
            bool underlined = false;
            if (vUnderline.a > 0.0) {
                float style = floor(vUnderline.a * 255.0 + 0.5);
                float x = vLocal.x;
                float y = vLocal.y;
                if (style < 1.5) {
                    underlined = y > 1.0 - rule;
                } else if (style < 2.5) {
                    underlined = y > 1.0 - rule ||
                        (y > 1.0 - 3.0 * rule && y < 1.0 - 2.0 * rule);
                } else if (style < 3.5) {
                    float wave = 1.0 - 1.6 * rule + sin(x * 25.0) * rule;
                    underlined = abs(y - wave) < rule * 0.5;
                } else if (style < 4.5) {
                    underlined = y > 1.0 - rule && mod(x * 28.0, 2.0) < 1.0;
                } else {
                    underlined = y > 1.0 - rule && mod(x * 10.0, 2.0) < 1.2;
                }
            }

            if (vOverline.a > 0.0 && vLocal.y < rule) {
                fragColor = vec4(vOverline.rgb, 1.0);
            } else if (vStrikethrough.a > 0.0 && abs(vLocal.y - 0.35) < rule * 0.5) {
                // Through the middle of the lowercase letters, which is where a
                // strikethrough reads as one rather than as an underline that
                // happened to be short.
                fragColor = vec4(vStrikethrough.rgb, 1.0);
            } else if (underlined) {
                fragColor = vec4(vUnderline.rgb, 1.0);
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
        // One when the glyph is a blinking one; the fragment dims it in the
        // dark half of the blink.
        out highp float vBlink;
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

            // A wide glyph (CJK, a Nerd Font icon) is one glyph in two cells,
            // and its mask is narrower than the two cells it covers: the font
            // gives it one advance, not two. Drawing it from the pen would put
            // the ink hard against the left edge of the pair, so it is centred
            // in the space it was given. Narrow cells are left alone — their
            // left bearing is the font's, and moving it would shift every
            // stroke in the grid.
            float spare = (aSpan > 1u) ? max(0.0, targetWidth - size.x) * 0.5 : 0.0;

            gl_Position = vec4(
                uProjection.xy + uProjection.zw * (cell + offset + vec2(spare, 0.0) + corner * size),
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
            vBlink = float((aFlags & 2u) != 0u);
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
        in highp float vBlink;
        uniform sampler2D uAtlas;
        // How much of its colour a blinking glyph keeps this frame.
        uniform float uBlinkAlpha;
        out vec4 fragColor;
        void main() {
            float coverage = texture(uAtlas, vTexCoord).r;
            float blink = mix(1.0, uBlinkAlpha, vBlink);
            fragColor = vec4(vColor.rgb, vColor.a * coverage * blink);
        }
    """.trimIndent()
}
