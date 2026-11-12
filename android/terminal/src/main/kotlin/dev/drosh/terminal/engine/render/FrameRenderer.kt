package dev.drosh.terminal.engine.render

import android.opengl.GLES20
import android.opengl.GLES30
import dev.drosh.terminal.engine.layout.SizeInfo
import dev.drosh.terminal.engine.protocol.Frame
import dev.drosh.terminal.engine.protocol.FrameFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws a [Frame].
 *
 * ## What it does not do
 *
 * It does not decide what a colour means. The frame's colours are resolved: the
 * engine has already applied the palette, the inverse flag, bold-brightening and
 * the selection. This class turns a cell into a quad and a glyph into a texture
 * lookup, and that is all it knows.
 *
 * That split is deliberate. The previous renderer carried a palette resolver, a
 * bold-brightener and an inverse swap, all of which were copies of decisions the
 * engine had already made — and a copy that disagrees with its source is the bug
 * you cannot see, because both halves look right on their own.
 *
 * ## Two passes
 *
 * Backgrounds first, then glyphs. A single pass with per-fragment branching
 * would be simpler to write and slower to run: the fragment shader would sample
 * the atlas for cells that have no glyph.
 *
 * ## Only the loop thread
 *
 * Every method here must be called on the thread that created the GL context.
 * [RenderThread] is that thread.
 */
internal class FrameRenderer {

    private var backgroundProgram = 0
    private var foregroundProgram = 0

    private var backgroundProjection = 0
    private var backgroundCellSize = 0
    private var foregroundProjection = 0
    private var foregroundCellSize = 0
    private var foregroundAtlasSize = 0
    private var foregroundAtlas = 0

    /** The factor the atlas's masks are rasterised at. */
    private var foregroundAtlasScale = 0

    private var atlasTexture = 0
    private var backgroundBuffer = 0
    private var cursorProgram = 0
    private var cursorProjection = 0
    private var cursorCellSize = 0
    private var cursorRect = 0
    private var cursorColorUniform = 0

    private var cursorHollow = 0
    private var cursorEdge = 0
    private var foregroundBuffer = 0

    /** The glyph masks. Owned by the loop thread. */
    private val atlas = TextAtlas()

    /** Cell size, in pixels — the line box, which is what a cell is drawn at. */
    private var cellWidth = 1f
    private var cellHeight = 1f

    /**
     * The font's own pixel size: what a glyph is rasterised at.
     *
     * The cell is the line box and is about a fifth taller than the font. Using
     * the cell's height as the rasterisation size draws every glyph too big for
     * the cell it sits in.
     *
     * Set by [RenderThread] from the newest cell command that carried a size,
     * including one that arrived before this renderer existed — see
     * `RenderThread.fontPx`.
     */
    var fontPx: Float = 0f

    /**
     * The size a glyph is rasterised at: the font's own, or the cell's when no
     * font size has ever been named.
     *
     * The fallback is a last resort, not a rule: a caller that measures a cell
     * but not the font (`attach` has only the cell) leaves this zero, and a
     * glyph rasterised at the cell's height is about a sixth too large for the
     * cell it is drawn in. Every path that knows the font publishes it, so the
     * fallback only covers a caller that never did.
     */
    private val rasterPx: Float get() = if (fontPx > 0f) fontPx else cellHeight

    /**
     * True once the programs compiled and the buffers exist.
     *
     * Public because a renderer that never came up draws nothing, and a surface
     * that never swaps is a black rectangle — the same picture as a hundred
     * other faults, and the only one whose cause is not on the screen.
     */
    var created = false
        private set

    /** Why [create] gave up, when it did. */
    var failure: String? = null
        private set

    private var backgrounds: ByteBuffer = ByteBuffer
        .allocateDirect(1024)
        .order(ByteOrder.LITTLE_ENDIAN)

    private var foregrounds: ByteBuffer = ByteBuffer
        .allocateDirect(1024)
        .order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Create the GL resources. Called once, on the loop thread, after the
     * context is current.
     */
    fun create() {
        backgroundProgram = buildProgram(Shaders.BACKGROUND_VERTEX, Shaders.BACKGROUND_FRAGMENT)
        foregroundProgram = buildProgram(Shaders.FOREGROUND_VERTEX, Shaders.FOREGROUND_FRAGMENT)
        cursorProgram = buildProgram(Shaders.CURSOR_VERTEX, Shaders.CURSOR_FRAGMENT)
        imageProgram = buildProgram(Shaders.IMAGE_VERTEX, Shaders.IMAGE_FRAGMENT)
        if (backgroundProgram == 0 || foregroundProgram == 0 || cursorProgram == 0 ||
            imageProgram == 0
        ) {
            failure = lastShaderError ?: "the shaders did not compile"
            return
        }
        imageProjection = GLES20.glGetUniformLocation(imageProgram, "uProjection")
        imageTexture = GLES20.glGetUniformLocation(imageProgram, "uTexture")
        cursorProjection = GLES20.glGetUniformLocation(cursorProgram, "uProjection")
        cursorCellSize = GLES20.glGetUniformLocation(cursorProgram, "uCellSize")
        cursorRect = GLES20.glGetUniformLocation(cursorProgram, "uRect")
        cursorColorUniform = GLES20.glGetUniformLocation(cursorProgram, "uColor")
        cursorHollow = GLES20.glGetUniformLocation(cursorProgram, "uHollow")
        cursorEdge = GLES20.glGetUniformLocation(cursorProgram, "uEdge")

        backgroundProjection = GLES20.glGetUniformLocation(backgroundProgram, "uProjection")
        backgroundCellSize = GLES20.glGetUniformLocation(backgroundProgram, "uCellSize")
        foregroundProjection = GLES20.glGetUniformLocation(foregroundProgram, "uProjection")
        foregroundCellSize = GLES20.glGetUniformLocation(foregroundProgram, "uCellSize")
        foregroundAtlasSize = GLES20.glGetUniformLocation(foregroundProgram, "uAtlasSize")
        foregroundAtlas = GLES20.glGetUniformLocation(foregroundProgram, "uAtlas")
        foregroundAtlasScale = GLES20.glGetUniformLocation(foregroundProgram, "uAtlasScale")

        atlasTexture = buildAtlasTexture()
        backgroundBuffer = buildBuffer()
        foregroundBuffer = buildBuffer()
        imageBuffer = buildBuffer()

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        created = true
    }

    /** Release every GL object. */
    fun release() {
        if (!created) return
        GLES20.glDeleteProgram(backgroundProgram)
        GLES20.glDeleteProgram(foregroundProgram)
        if (cursorProgram != 0) {
            GLES20.glDeleteProgram(cursorProgram)
        }
        GLES20.glDeleteTextures(1, intArrayOf(atlasTexture), 0)
        GLES20.glDeleteBuffers(2, intArrayOf(backgroundBuffer, foregroundBuffer), 0)
        created = false
    }

    /**
     * The cell size changed.
     *
     * The atlas's masks are rasterised at a size, so a new cell means a new
     * generation. Nothing is thrown away: the atlas keys its masks by the size
     * they were rasterised at.
     */
    fun onCellChanged() {
        // A new size, not a new face: the masks are keyed by the size they were
        // rasterised at, so nothing is thrown away.
        atlas.beginSize(rasterPx)
    }

    /**
     * The grid's shape changed.
     *
     * Only the step matters to drawing: the cells arrive in the frame, in draw
     * order, with their rows already signed. The column and row counts are the
     * caller's business.
     */
    fun onGridChanged(size: SizeInfo) {
        cellWidth = size.stepX
        cellHeight = size.stepY
    }

    /** The surface or the cell changed; drawing needs the new numbers. */
    fun onSizeChanged(size: SizeInfo) {
        cellWidth = size.stepX
        cellHeight = size.stepY
        GLES20.glViewport(0, 0, size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
    }

    /**
     * Wipe the surface.
     *
     * Used when the session behind the pane changes: the new session's grid
     * is empty until its shell prints something, and the renderer keeps the
     * previous picture when a frame draws nothing — so without this the old
     * session stays on screen while the new one's name is already in the
     * title.
     */
    fun clear(size: SizeInfo) {
        if (!created) return
        GLES20.glViewport(0, 0, size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
        GLES20.glClearColor(backgroundR, backgroundG, backgroundB, if (transparentBackground) 0f else 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    /**
     * Draw one frame.
     *
     * Returns the number of cells drawn, which the loop uses to decide whether
     * presenting is worth it. Zero means the frame was empty or invalid and the
     * previous picture should stay on screen.
     */
    fun draw(frame: Frame?, size: SizeInfo): Int {
        if (!created || frame == null || !frame.valid) {
            return 0
        }

        cellWidth = size.stepX
        cellHeight = size.stepY

        // The clear is the terminal's own background, and it happens only when
        // a frame is actually drawn: a frame that draws nothing leaves the
        // previous picture alone, which is what stops the screen from wiping to
        // the clear colour and coming back.
        GLES20.glViewport(0, 0, size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
        GLES20.glClearColor(backgroundR, backgroundG, backgroundB, if (transparentBackground) 0f else 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Decided once, before either pass reads it: the block cursor inverts
        // exactly one cell, and both the background and the glyph of that cell
        // have to know which one.
        invertedCell = blockCursorCell(frame)

        // The backgrounds are built here, once, and drawn by the pass below.
        // Building them in the pass would work too, but a buffer that nobody
        // fills is a pass that draws whatever was left in it — which is how the
        // coloured bars of a full-screen program went missing while the text
        // kept rendering.
        buildBackgrounds(frame, size)
        val foregroundCount = buildForegrounds(frame)

        if (atlas.dirty) {
            uploadAtlas()
        }

        drawBackgrounds(frame, size)
        drawForegrounds(frame, size, foregroundCount)
        drawCursor(frame, size)
        drawBellFlash(frame, size)
        drawImages(size)

        return frame.cellCount
    }

    // --- geometry ----------------------------------------------------------

    /**
     * One record per cell: `u16 column, i16 row, vec4 colour`.
     *
     * The row is signed because the frame's overscan rows sit above the
     * viewport; the shader reinterprets the top half of the unsigned range.
     */
    private fun buildBackgrounds(frame: Frame, size: SizeInfo) {
        val bytes = frame.cellCount * BACKGROUND_BYTES
        if (backgrounds.capacity() < bytes) {
            backgrounds = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)
        }

        backgrounds.clear()
        for (index in 0 until frame.cellCount) {
            // The row the engine says this cell belongs to.
            val row = index / frame.columns + frame.firstRowY
            backgrounds.putShort((index % frame.columns).toShort())
            backgrounds.putShort(row.toShort())
            // A search match is a tint over the cell's own background.
            val background = backgroundOf(frame, index)
            val painted = if (frame.isHighlighted(index)) {
                val alpha = HIGHLIGHT_TINT
                val r = FrameFormat.red(background) / 255f
                val g = FrameFormat.green(background) / 255f
                val b = FrameFormat.blue(background) / 255f
                val mixed = (r * (1f - alpha) + highlightR * alpha) * 255f
                val mixedG = (g * (1f - alpha) + highlightG * alpha) * 255f
                val mixedB = (b * (1f - alpha) + highlightB * alpha) * 255f
                (0xff shl 24) or
                    (mixed.toInt().coerceIn(0, 255) shl 16) or
                    (mixedG.toInt().coerceIn(0, 255) shl 8) or
                    mixedB.toInt().coerceIn(0, 255)
            } else {
                background
            }
            putColor(backgrounds, painted)
            // The underline, if the cell has one. A cell with no underline
            // carries a transparent colour and nothing is drawn.
            val underline = if (frame.attributes(index) and FrameFormat.ATTR_UNDERLINE_MASK != 0) {
                foregroundOf(frame, index)
            } else {
                TRANSPARENT
            }
            putColor(backgrounds, underline)
        }
        backgrounds.flip()
    }

    /**
     * The bar and underline cursors.
     *
     * A block cursor needs no shape of its own — the cell is inverted in the
     * two passes above. A bar and an underline are thin rectangles over a cell
     * whose glyph stays legible, so they are drawn here with a shader that
     * takes a rectangle in cell units, which the grid passes cannot express
     * (their vertex shader multiplies whole cell indices).
     */
    /**
     * Whether a blinking cursor is in its visible phase right now.
     *
     * The engine says the cursor blinks; the phase and the rate are the clock's
     * business — see [RenderClock], which the settings move.
     */
    private fun blinkVisible(): Boolean = RenderClock.blinkVisible()

    /**
     * The outline's thickness, in cell units.
     *
     * A frame that is a fraction of a cell vanishes on a small cell and is a
     * slab on a large one, so the thickness is a real width on the screen: at
     * least a pixel and a half, and never more than a quarter of the cell.
     */
    private fun cursorEdgeFraction(): Float {
        val smallest = minOf(cellWidth, cellHeight).coerceAtLeast(1f)
        return (1.5f / smallest).coerceIn(0.06f, 0.25f)
    }

    private fun drawCursor(frame: Frame, size: SizeInfo) {
        if (cursorProgram == 0) return
        if (!frame.cursorVisible) return
        if (frame.cursorColumn !in 0 until frame.columns) return
        if (frame.cursorRow !in 0 until frame.rows) return

        // A blinking cursor is hidden half the time. The engine says that the
        // cursor blinks; whether it is in its visible phase is the clock's
        // business, and the same clock the render loop already wakes on.
        if (frame.cursorBlinking && !blinkVisible()) return

        // The cursor's shape. A block is the cell inverted; a bar and an
        // underline are rectangles of their own.
        //
        // The focus does not change it: this terminal is the app, and a cursor
        // that turned into an outline the moment the app went to the background
        // read as a bug, not as a hint about where the keystrokes go.
        val hollow = frame.cursorStyle == FrameFormat.CURSOR_STYLE_BLOCK_HOLLOW
        val block = frame.cursorStyle == FrameFormat.CURSOR_STYLE_BLOCK && !hollow

        if (block) return // drawn by inverting the cell

        val isUnderline = frame.cursorStyle == FrameFormat.CURSOR_STYLE_UNDERLINE
        // A bar hugs the left edge of the cell; an underline the bottom.
        //
        // The row is the viewport's, and so is a cell's: the glyph pass takes
        // its row from the engine's own answer for the frame's first captured
        // row ([Frame.firstRowY]), which is the space the cursor's viewport row
        // is reported in. The two are the same rows, so no shift belongs here.
        val x = frame.cursorColumn.toFloat()
        val y = frame.cursorRow.toFloat() +
            if (isUnderline) 1f - UNDERLINE_HEIGHT else 0f
        val w = if (isUnderline) 1f else if (hollow) 1f else BAR_WIDTH
        val h = if (isUnderline) UNDERLINE_HEIGHT else 1f

        val projection = FloatArray(4)
        size.projection(projection)

        // The cursor's colour is the cell's own foreground, the way Ghostty
        // draws it: a block cursor is the text's colour, so the text under it
        // stays readable. The frame's cursor colour is the terminal's
        // foreground, which is right for a cursor standing on empty ground and
        // wrong for one standing on coloured text.
        //
        // The cell is found the same way the block cursor finds it — the
        // viewport row, less where the frame's first row sits — so the two
        // cannot disagree about which cell the cursor is on.
        val cellIndex =
            (frame.cursorRow - frame.firstRowY) * frame.columns + frame.cursorColumn
        val colour = if (cellIndex in 0 until frame.cellCount) {
            frame.foreground(cellIndex)
        } else {
            frame.cursorColor
        }

        GLES20.glUseProgram(cursorProgram)
        GLES20.glUniform4fv(cursorProjection, 1, projection, 0)
        GLES20.glUniform2f(cursorCellSize, cellWidth, cellHeight)
        GLES20.glUniform4f(
            cursorRect,
            x,
            y,
            w,
            h,
        )
        GLES20.glUniform1f(cursorHollow, if (hollow) 1f else 0f)
        // The outline's thickness: at least a pixel and a half on the screen,
        // and never more than a quarter of the cell.
        GLES20.glUniform1f(cursorEdge, cursorEdgeFraction())
        GLES20.glUniform4f(
            cursorColorUniform,
            FrameFormat.red(colour) / 255f,
            FrameFormat.green(colour) / 255f,
            FrameFormat.blue(colour) / 255f,
            FrameFormat.alpha(colour) / 255f,
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * The visual bell: the accent colour over the terminal, fading away.
     *
     * The cursor's program draws a rectangle, and the grid is a rectangle, so
     * the wash is the same draw with the whole grid as the rectangle.
     */
    private fun drawBellFlash(frame: Frame, size: SizeInfo) {
        if (cursorProgram == 0) return
        val elapsed = System.currentTimeMillis() - bellStartedAt
        if (elapsed >= BELL_FLASH_MS) return
        // The wash fades: it is at its strongest at the first frame and gone by
        // the last, so it reads as a pulse and not as a blink.
        val strength = 1f - elapsed.toFloat() / BELL_FLASH_MS

        val projection = FloatArray(4)
        size.projection(projection)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(cursorProgram)
        GLES20.glUniform4fv(cursorProjection, 1, projection, 0)
        GLES20.glUniform2f(cursorCellSize, cellWidth, cellHeight)
        GLES20.glUniform4f(
            cursorRect,
            0f,
            0f,
            frame.columns.toFloat(),
            frame.rows.toFloat(),
        )
        GLES20.glUniform1f(cursorHollow, 0f)
        GLES20.glUniform4f(
            cursorColorUniform,
            bellRed,
            bellGreen,
            bellBlue,
            BELL_FLASH_ALPHA * strength,
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * The cursor cell under a block cursor, as a cell index, or -1.
     *
     * A block cursor is drawn by inverting the cell it sits on — that is what
     * Ghostty does, and it is why a block covers the character rather than
     * hiding it: the glyph stays, in the cell's own background colour. A bar or
     * an underline is a shape of its own and does not touch the cell.
     */
    private fun blockCursorCell(frame: Frame): Int {
        if (!frame.cursorVisible || frame.cursorIsRectangle) return -1
        // A hollow cursor is an outline drawn over the cell; the cell keeps
        // its own colours.
        if (frame.cursorStyle == FrameFormat.CURSOR_STYLE_BLOCK_HOLLOW) return -1
        if (frame.cursorColumn !in 0 until frame.columns) return -1
        if (frame.cursorRow !in 0 until frame.rows) return -1
        // The frame's own index for that row: the viewport's row, less where
        // the frame's first row sits in it.
        val row = frame.cursorRow - frame.firstRowY
        if (row < 0) return -1
        return row * frame.columns + frame.cursorColumn
    }

    /** The cell the block cursor inverts this frame, or -1. Computed once. */
    private var invertedCell: Int = -1

    /**
     * The colour the surface is wiped to, as this theme's terminal background.
     *
     * Wiping to a fixed near-black was wrong twice over: a light theme had a
     * black ground wherever the grid did not reach, and every gap — the band
     * under a zoomed-out grid, the edge of the last column — was that black.
     * The terminal's own background is the honest colour: it is what the cells
     * are drawn on, so the parts of the surface that hold no cell look like the
     * parts that do.
     */
    private var backgroundR = DEFAULT_BACKGROUND_R
    private var backgroundG = DEFAULT_BACKGROUND_G
    private var backgroundB = DEFAULT_BACKGROUND_B

    /**
     * The colour a search match is drawn in.
     *
     * The match is a tint over the cell's own background rather than a colour
     * of its own: the cell's text stays its text, and the block under it says
     * "this is the match".
     */
    private var highlightR = 1f
    private var highlightG = 0.85f
    private var highlightB = 0.2f

    /** Set the colour a search match is drawn in, from the theme. */
    fun setHighlightColour(argb: Int) {
        highlightR = ((argb shr 16) and 0xff) / 255f
        highlightG = ((argb shr 8) and 0xff) / 255f
        highlightB = (argb and 0xff) / 255f
    }

    // --- the pictures a program drew ---------------------------------------

    /**
     * A picture's texture, and the generation it belongs to.
     *
     * The pixels come once per generation: the program sends a picture, and
     * until it sends another one the texture is the same picture.
     */
    private class ImageTexture(
        val generation: Long,
        val texture: Int,
        val width: Int,
        val height: Int,
    )

    private val imageTextures = HashMap<Int, ImageTexture>()

    /** The pictures of the last frame, in the order they are drawn. */
    private val images = ArrayList<ImageDraw>()

    /** One picture: where it is, and the texture it is drawn from. */
    private class ImageDraw(
        val texture: Int,
        val col: Int,
        val row: Int,
        val offsetX: Int,
        val offsetY: Int,
        val width: Int,
        val height: Int,
        val sourceU: Float,
        val sourceV: Float,
        val sourceU2: Float,
        val sourceV2: Float,
    )

    private var imageProgram = 0
    private var imageProjection = 0
    private var imageTexture = 0
    private var imageBuffer = 0
    private var imageVertices: java.nio.ByteBuffer? = null

    /**
     * Reads the pictures of a frame.
     *
     * The buffer is the app's own format: a count, then per picture its id,
     * its generation, the cell and the offset it sits at, the part of the
     * picture to show, and — the first time that generation is seen — its
     * pixels.
     */
    fun setImages(buffer: ByteArray?) {
        images.clear()
        if (buffer == null || buffer.size < 4) return
        val little = java.nio.ByteBuffer.wrap(buffer).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val count = little.int
        var at = 4
        for (i in 0 until count) {
            if (at + 60 > buffer.size) break
            val id = little.int
            val generation = little.long
            val col = little.int
            val row = little.int
            val offsetX = little.int
            val offsetY = little.int
            val width = little.int
            val height = little.int
            val sourceX = little.int
            val sourceY = little.int
            val sourceWidth = little.int
            val sourceHeight = little.int
            val format = little.int
            val dataLength = little.int
            val hasPixels = little.get().toInt() != 0
            at += 60

            // The pixels, when they are new.
            if (hasPixels && at + dataLength <= buffer.size) {
                val pixels = buffer.copyOfRange(at, at + dataLength)
                at += dataLength
                uploadImage(id, generation, pixels, width, height, format)
            }

            val texture = imageTextures[id] ?: continue
            if (texture.generation != generation) continue
            if (width <= 0 || height <= 0) continue

            images.add(
                ImageDraw(
                    texture = texture.texture,
                    col = col,
                    row = row,
                    offsetX = offsetX,
                    offsetY = offsetY,
                    width = width,
                    height = height,
                    sourceU = sourceX.toFloat() / texture.width.toFloat(),
                    sourceV = sourceY.toFloat() / texture.height.toFloat(),
                    sourceU2 = (sourceX + sourceWidth).toFloat() / texture.width.toFloat(),
                    sourceV2 = (sourceY + sourceHeight).toFloat() / texture.height.toFloat(),
                ),
            )
        }
    }

    /**
     * Uploads a picture's pixels.
     *
     * The kitty protocol sends RGB or RGBA, and the rows are not padded to
     * four bytes, so the unpack alignment is one.
     */
    private fun uploadImage(
        id: Int,
        generation: Long,
        pixels: ByteArray,
        width: Int,
        height: Int,
        format: Int,
    ) {
        if (width <= 0 || height <= 0) return
        val texture = imageTextures[id]?.texture ?: run {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            ids[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        // The pixels go in as a buffer: the bytes are the picture's own, and
        // a picture's rows are not padded to four bytes.
        val data = java.nio.ByteBuffer.wrap(pixels)
        if (format == KITTY_FORMAT_RGB) {
            // RGB has no alpha; the picture is drawn over the terminal, so it
            // is opaque.
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB, width, height, 0,
                GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, data,
            )
        } else {
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, data,
            )
        }
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
        )
        imageTextures[id] = ImageTexture(generation, texture, width, height)
    }

    /**
     * Draws the pictures, over the text.
     *
     * A picture is *on* the grid: it covers the cells it is placed on, so it
     * is drawn after the glyphs and before the cursor.
     */
    private fun drawImages(size: SizeInfo) {
        if (images.isEmpty() || imageProgram == 0) return

        val floats = images.size * 8
        if (imageVertices == null || imageVertices!!.capacity() < floats * 4) {
            imageVertices = java.nio.ByteBuffer
                .allocateDirect(floats * 4)
                .order(java.nio.ByteOrder.nativeOrder())
        }
        val buffer = imageVertices!!
        buffer.clear()
        for (image in images) {
            val x = image.col * cellWidth + image.offsetX
            val y = image.row * cellHeight + image.offsetY
            buffer.putFloat(x)
            buffer.putFloat(y)
            buffer.putFloat(image.width.toFloat())
            buffer.putFloat(image.height.toFloat())
            buffer.putFloat(image.sourceU)
            buffer.putFloat(image.sourceV)
            buffer.putFloat(image.sourceU2)
            buffer.putFloat(image.sourceV2)
        }
        buffer.flip()

        val projection = FloatArray(4)
        size.projection(projection)

        GLES20.glUseProgram(imageProgram)
        GLES20.glUniform4fv(imageProjection, 1, projection, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(imageTexture, 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, imageBuffer)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            buffer.remaining(),
            buffer,
            GLES20.GL_DYNAMIC_DRAW,
        )
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 32, 0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 4, GLES20.GL_FLOAT, false, 32, 16)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        for ((index, image) in images.withIndex()) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, image.texture)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, index * 4, 4)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
    }

    /**
     * When the visual bell started, in milliseconds.
     *
     * A program that rings the bell has said something, and the screen has to
     * say it back. A white flash is the old way and it is harsh: the terminal
     * blinks and the text is unreadable for a moment.
     *
     * What it does now is a *wash*: the app's accent colour over the terminal,
     * faint, and it fades away. Nothing blinks, nothing is unreadable, and the
     * screen says something happened.
     */
    private var bellStartedAt: Long = 0L

    /** The colour the wash is in: the app's accent. */
    private var bellRed = 0.3f
    private var bellGreen = 0.5f
    private var bellBlue = 1f

    /** Set the colour the bell's wash is in, from the theme. */
    fun setBellColour(argb: Int) {
        bellRed = ((argb shr 16) and 0xff) / 255f
        bellGreen = ((argb shr 8) and 0xff) / 255f
        bellBlue = (argb and 0xff) / 255f
    }

    /** The visual bell: a wash that fades. */
    fun flashBell() {
        bellStartedAt = System.currentTimeMillis()
    }

    /**
     * Whether the surface is wiped to transparent.
     *
     * With a background image behind the terminal, the surface has to be
     * transparent: an opaque wipe would paint over the picture, and the picture
     * is the whole point of the setting.
     */
    var transparentBackground: Boolean = false

    /**
     * Set the terminal's background colour from the theme.
     *
     * `argb` is an Android colour. The surface is wiped to it before every
     * frame and whenever a session switch clears the pane, so this is the
     * colour of everything the grid does not cover.
     */
    fun setBackground(argb: Int) {
        backgroundR = ((argb shr 16) and 0xff) / 255f
        backgroundG = ((argb shr 8) and 0xff) / 255f
        backgroundB = (argb and 0xff) / 255f
    }

    /**
     * Whether the terminal has the focus.
     *
     * A terminal that does not have it draws a hollow cursor, so the cursor
     * says where it is without pretending to be the place the user is typing.
     */
    @Volatile
    var focused: Boolean = true

    private fun backgroundOf(frame: Frame, index: Int): Int =
        if (index == invertedCell) frame.foreground(index) else frame.background(index)

    private fun foregroundOf(frame: Frame, index: Int): Int =
        if (index == invertedCell) frame.background(index) else frame.foreground(index)

    private fun buildForegrounds(frame: Frame): Int {
        val bytes = frame.cellCount * RECORD_BYTES
        if (foregrounds.capacity() < bytes) {
            foregrounds = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)
        }

        foregrounds.clear()
        var written = 0

        for (index in 0 until frame.cellCount) {
            val codepoint = frame.codepoint(index)
            if (codepoint == 0 || codepoint == ' '.code) continue

            val attributes = frame.attributes(index)
            val isBold = attributes and FrameFormat.ATTR_BOLD != 0
            val entry = atlas.glyph(codepoint, isBold, rasterPx) ?: continue
            if (entry.isBlank) continue

            val row = index / frame.columns + frame.firstRowY
            val column = index % frame.columns
            val span = if (frame.width(index) == 2) 2 else 1

            foregrounds.putShort(entry.x.toShort())
            foregrounds.putShort(entry.y.toShort())
            foregrounds.putShort(entry.width.toShort())
            foregrounds.putShort(entry.height.toShort())
            foregrounds.putShort(entry.left.toShort())
            // The mask's line box, centred in the cell it is drawn in.
            //
            // The cell is the font's line box, so the two have the same height
            // and the remainder is zero — almost. The atlas rasterises a mask in
            // whole rows (`ceil`) at a size that may be supersampled, while the
            // cell is a measured float, so the mask can end up a pixel taller
            // than its cell. Ghostty puts exactly that remainder half above and
            // half below the face (font/Metrics.zig derives `cell_baseline` from
            // `(cell_height - face_height) / 2`), so a glyph never sits a whole
            // pixel low in its cell. Both terms are in the mask's own pixels,
            // which is the unit the shader divides by `uAtlasScale`.
            val lineBox = cellHeight * atlas.scale - entry.height
            foregrounds.putShort((entry.top + lineBox / 2f).toInt().toShort())
            foregrounds.putShort(column.toShort())
            foregrounds.putShort(row.toShort())
            putColor(foregrounds, foregroundOf(frame, index))
            foregrounds.putShort(FLAG_NONE.toShort())
            foregrounds.putShort(span.toShort())
            written += 1
        }
        foregrounds.flip()

        return written
    }

    // --- drawing -----------------------------------------------------------

    private fun drawBackgrounds(frame: Frame, size: SizeInfo) {
        if (frame.cellCount == 0) return

        val projection = FloatArray(4)
        size.projection(projection)

        GLES20.glUseProgram(backgroundProgram)
        GLES20.glUniform4fv(backgroundProjection, 1, projection, 0)
        GLES20.glUniform2f(backgroundCellSize, cellWidth, cellHeight)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, backgroundBuffer)
        // `remaining()`, not `capacity()`: the buffer was flipped, so its limit
        // is what was written and its position is zero. Handing GL the capacity
        // asks it to read past the data, which is the IllegalArgumentException
        // that killed the loop.
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            backgrounds.remaining(),
            backgrounds,
            GLES20.GL_DYNAMIC_DRAW,
        )

        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribIPointer(0, 2, GLES30.GL_UNSIGNED_SHORT, BACKGROUND_BYTES, 0)
        GLES30.glVertexAttribDivisor(0, 1)

        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_UNSIGNED_BYTE, true, BACKGROUND_BYTES, 4)
        GLES30.glVertexAttribDivisor(1, 1)

        // The underline's colour: transparent when the cell has none.
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_UNSIGNED_BYTE, true, BACKGROUND_BYTES, 8)
        GLES30.glVertexAttribDivisor(2, 1)

        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, frame.cellCount)
    }

    private fun drawForegrounds(frame: Frame, size: SizeInfo, count: Int) {
        if (count == 0) return

        val projection = FloatArray(4)
        size.projection(projection)

        GLES20.glUseProgram(foregroundProgram)
        GLES20.glUniform4fv(foregroundProjection, 1, projection, 0)
        GLES20.glUniform2f(foregroundCellSize, cellWidth, cellHeight)
        GLES20.glUniform2f(foregroundAtlasSize, atlas.size.toFloat(), atlas.size.toFloat())
        // The masks may be rasterised larger than the font, so that a small
        // font's strokes survive being sampled between texels. The shader
        // divides the mask's size by this to get the size to draw at.
        GLES20.glUniform1f(foregroundAtlasScale, atlas.scale)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexture)
        GLES20.glUniform1i(foregroundAtlas, 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, foregroundBuffer)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            foregrounds.remaining(),
            foregrounds,
            GLES20.GL_DYNAMIC_DRAW,
        )

        // atlasRect, glyphSize, offset, gridPos, colour, flags, span
        val stride = RECORD_BYTES
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribIPointer(0, 2, GLES30.GL_UNSIGNED_SHORT, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribIPointer(1, 2, GLES30.GL_UNSIGNED_SHORT, stride, 4)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribIPointer(2, 2, GLES30.GL_SHORT, stride, 8)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribIPointer(3, 2, GLES30.GL_UNSIGNED_SHORT, stride, 12)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 4, GLES30.GL_UNSIGNED_BYTE, true, stride, 16)
        GLES30.glEnableVertexAttribArray(5)
        GLES30.glVertexAttribIPointer(5, 1, GLES30.GL_UNSIGNED_SHORT, stride, 20)
        GLES30.glEnableVertexAttribArray(6)
        GLES30.glVertexAttribIPointer(6, 1, GLES30.GL_UNSIGNED_SHORT, stride, 22)

        for (attribute in 0..6) {
            GLES30.glVertexAttribDivisor(attribute, 1)
        }

        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, count)
    }

    // --- GL helpers --------------------------------------------------------

    /**
     * Upload the rows that changed, not the whole atlas.
     *
     * The atlas is 2048x2048 — four megabytes. Uploading all of it on every
     * frame it changed is four megabytes a frame, and a pinch changes it on
     * every step of the gesture: the text got bigger, every mask was
     * rasterised again, and the whole megabyte went to the GPU each time. That
     * is the pinch's stutter.
     *
     * Only the rows that changed go up, and the buffer's position says where
     * they start.
     */
    private fun uploadAtlas() {
        val rows = atlas.dirtyRows()
        if (rows == null) {
            atlas.dirty = false
            return
        }
        val height = rows.count()
        if (height <= 0) {
            atlas.dirty = false
            atlas.clearDirty()
            return
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexture)

        val pixels = atlas.directPixels()
        val start = rows.first * atlas.size
        // The limit is only ever the capacity: the upload reads a window of the
        // buffer, but the atlas writes into the whole of it with absolute
        // puts, and an absolute put is checked against the limit. Shrinking it
        // for the upload left the buffer refusing the next glyph.
        pixels.limit(pixels.capacity())
        pixels.position(start)

        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            0,
            rows.first,
            atlas.size,
            height,
            GLES30.GL_RED,
            GLES20.GL_UNSIGNED_BYTE,
            pixels,
        )
        atlas.clearDirty()
        atlas.dirty = false
    }

    private fun buildAtlasTexture(): Int {
        val handle = IntArray(1)
        GLES20.glGenTextures(1, handle, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handle[0])
        // Linear, not nearest: a mask drawn at another size — a pinch, a
        // fractional cell step — is interpolated rather than pixel-doubled.
        // Nearest doubled every stroke, which is text that grew bolder as it
        // grew bigger.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES30.GL_R8,
            atlas.size,
            atlas.size,
            0,
            GLES30.GL_RED,
            GLES20.GL_UNSIGNED_BYTE,
            null,
        )
        return handle[0]
    }

    private fun buildBuffer(): Int {
        val handle = IntArray(1)
        GLES20.glGenBuffers(1, handle, 0)
        return handle[0]
    }

    private fun buildProgram(vertex: String, fragment: String): Int {
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, vertex)
        if (vertexShader == 0) return 0
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        if (fragmentShader == 0) return 0

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            val message = "program link failed: $log"
            lastShaderError = message
            android.util.Log.e(TAG, message)
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    /** The last compile or link failure, kept for the screen. */
    private var lastShaderError: String? = null

    private fun compile(kind: Int, source: String): Int {
        val shader = GLES20.glCreateShader(kind)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            val message = "shader compile failed: $log"
            lastShaderError = message
            android.util.Log.e(TAG, message)
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    /**
     * Write a colour as four bytes in the order the shader reads them.
     *
     * The vertex attribute is `GL_UNSIGNED_BYTE` normalised, so the bytes are
     * the `vec4` components in order: red, green, blue, alpha. The frame packs
     * a colour with red in the low byte ([native frame.rs] `rgba`), so reading
     * it back is the same shift in reverse.
     */
    /**
     * Write a colour as four bytes in the order the shader reads them.
     *
     * Red, green, blue, alpha: the attribute is a normalised
     * `GL_UNSIGNED_BYTE`, so the byte order *is* the colour order. The frame
     * packs a colour with red in the low byte ([FrameFormat.packRgba]), which
     * is what makes the first byte here the red one.
     */
    private fun putColor(buffer: ByteBuffer, packed: Int) {
        buffer.put(packed.toByte())
        buffer.put((packed ushr 8).toByte())
        buffer.put((packed ushr 16).toByte())
        buffer.put((packed ushr 24).toByte())
    }

    private companion object {
        const val TAG = "FrameRenderer"

        /** `u16 column, i16 row, vec4 colour`. */
        /** The background record: the cell's position, its colour, its underline. */
        const val BACKGROUND_BYTES = 12

        /** A colour that draws nothing. */
        const val TRANSPARENT = 0

        /** The kitty protocol's RGB picture format. */
        const val KITTY_FORMAT_RGB = 0

        /** How long the visual bell's flash lasts, in milliseconds. */
        const val BELL_FLASH_MS = 140L

        /** How strong the bell's wash is at its strongest. */
        const val BELL_FLASH_ALPHA = 0.22f

        /** How much of the highlight colour goes over the cell's background. */
        const val HIGHLIGHT_TINT = 0.35f

        /** A bar cursor's width, as a fraction of a cell. */
        const val BAR_WIDTH = 0.1f

        /** An underline cursor's height, as a fraction of a cell. */
        const val UNDERLINE_HEIGHT = 0.1f

        /**
         * `u16×2 atlasRect, u16×2 glyphSize, i16×2 offset, u16×2 gridPos,
         * vec4 colour, u16 flags, u16 span`.
         */
        const val RECORD_BYTES = 24

        const val FLAG_NONE = 0

        /** The terminal's own ground, until the theme says otherwise. */
        const val DEFAULT_BACKGROUND_R = 0.094f
        const val DEFAULT_BACKGROUND_G = 0.094f
        const val DEFAULT_BACKGROUND_B = 0.094f
    }
}
