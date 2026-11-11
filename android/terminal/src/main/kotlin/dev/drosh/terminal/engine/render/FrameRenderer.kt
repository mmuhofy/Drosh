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
     */
    var fontPx: Float = 0f

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
        if (backgroundProgram == 0 || foregroundProgram == 0 || cursorProgram == 0) {
            failure = lastShaderError ?: "the shaders did not compile"
            return
        }
        cursorProjection = GLES20.glGetUniformLocation(cursorProgram, "uProjection")
        cursorCellSize = GLES20.glGetUniformLocation(cursorProgram, "uCellSize")
        cursorRect = GLES20.glGetUniformLocation(cursorProgram, "uRect")
        cursorColorUniform = GLES20.glGetUniformLocation(cursorProgram, "uColor")
        cursorHollow = GLES20.glGetUniformLocation(cursorProgram, "uHollow")

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
        atlas.beginSize(if (fontPx > 0f) fontPx else cellHeight)
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
        GLES20.glClearColor(backgroundR, backgroundG, backgroundB, 1f)
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
        GLES20.glClearColor(backgroundR, backgroundG, backgroundB, 1f)
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
            val row = index / frame.columns - frame.overscanAbove
            backgrounds.putShort((index % frame.columns).toShort())
            backgrounds.putShort(row.toShort())
            putColor(backgrounds, backgroundOf(frame, index))
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

    private fun drawCursor(frame: Frame, size: SizeInfo) {
        if (cursorProgram == 0) return
        if (!frame.cursorVisible) return
        if (frame.cursorColumn !in 0 until frame.columns) return
        if (frame.cursorRow !in 0 until frame.rows) return

        // A blinking cursor is hidden half the time. The engine says that the
        // cursor blinks; whether it is in its visible phase is the clock's
        // business, and the same clock the render loop already wakes on.
        if (frame.cursorBlinking && !blinkVisible()) return

        // The cursor's shape, and what Ghostty does about the focus:
        //
        //  - a block cursor is the cell's own colours swapped, so the text
        //    under it stays readable;
        //  - a terminal that does not have the focus shows a hollow block
        //    instead, so the cursor says where it is without pretending to be
        //    the place the user is typing.
        val hollow = !focused || frame.cursorStyle == FrameFormat.CURSOR_STYLE_BLOCK_HOLLOW
        val block = frame.cursorStyle == FrameFormat.CURSOR_STYLE_BLOCK && !hollow

        if (block) return // drawn by inverting the cell

        val isUnderline = frame.cursorStyle == FrameFormat.CURSOR_STYLE_UNDERLINE
        // A bar hugs the left edge of the cell; an underline the bottom.
        //
        // The row is the viewport's, not the grid's: the glyph pass subtracts
        // the overscan rows before it draws a cell, so a cursor that *added*
        // them landed exactly as many rows too low as the frame carried overscan
        // above the viewport — a cursor that is near the prompt but never on it.
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
        val cellIndex =
            (frame.cursorRow + frame.overscanAbove) * frame.columns + frame.cursorColumn
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
        if (!focused || frame.cursorStyle == FrameFormat.CURSOR_STYLE_BLOCK_HOLLOW) return -1
        if (frame.cursorColumn !in 0 until frame.columns) return -1
        if (frame.cursorRow !in 0 until frame.rows) return -1
        return (frame.cursorRow + frame.overscanAbove) * frame.columns + frame.cursorColumn
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
            val entry = atlas.glyph(codepoint, isBold, if (fontPx > 0f) fontPx else cellHeight) ?: continue
            if (entry.isBlank) continue

            val row = index / frame.columns - frame.overscanAbove
            val column = index % frame.columns
            val span = if (frame.width(index) == 2) 2 else 1

            foregrounds.putShort(entry.x.toShort())
            foregrounds.putShort(entry.y.toShort())
            foregrounds.putShort(entry.width.toShort())
            foregrounds.putShort(entry.height.toShort())
            foregrounds.putShort(entry.left.toShort())
            // The mask's line box, centred in the cell it is drawn in.
            //
            // A font's line box is its ascent plus its descent; a *cell* is
            // the font's line *spacing*, which also carries the leading. The
            // mask was placed at the cell's top, so the leading fell at the
            // bottom of every row and the text sat that much high — a pixel or
            // two on a plain line, and a visible band in a full-screen program
            // that draws its own rows. Centring is what every terminal does
            // with the leftover: both `top` and `height` are in the mask's own
            // (possibly supersampled) pixels, and the cell is in device ones.
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
