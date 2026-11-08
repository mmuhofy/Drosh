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
    private var cursorProgram = 0

    private var backgroundProjection = 0
    private var backgroundCellSize = 0
    private var foregroundProjection = 0
    private var foregroundCellSize = 0
    private var foregroundAtlasSize = 0
    private var foregroundAtlas = 0
    private var foregroundScale = 0
    private var cursorProjection = 0
    private var cursorCellSize = 0
    private var cursorCell = 0
    private var cursorStyle = 0
    private var cursorColor = 0
    private var cursorCellPx = 0

    private var atlasTexture = 0
    private var backgroundBuffer = 0
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
        if (backgroundProgram == 0 || foregroundProgram == 0) {
            failure = lastShaderError ?: "the shaders did not compile"
            return
        }
        if (cursorProgram != 0) {
            cursorProjection = GLES20.glGetUniformLocation(cursorProgram, "uProjection")
            cursorCellSize = GLES20.glGetUniformLocation(cursorProgram, "uCellSize")
            cursorCell = GLES20.glGetUniformLocation(cursorProgram, "uCell")
            cursorStyle = GLES20.glGetUniformLocation(cursorProgram, "uStyle")
            cursorColor = GLES20.glGetUniformLocation(cursorProgram, "uColor")
            cursorCellPx = GLES20.glGetUniformLocation(cursorProgram, "uCellPx")
        }

        backgroundProjection = GLES20.glGetUniformLocation(backgroundProgram, "uProjection")
        backgroundCellSize = GLES20.glGetUniformLocation(backgroundProgram, "uCellSize")
        foregroundProjection = GLES20.glGetUniformLocation(foregroundProgram, "uProjection")
        foregroundCellSize = GLES20.glGetUniformLocation(foregroundProgram, "uCellSize")
        foregroundAtlasSize = GLES20.glGetUniformLocation(foregroundProgram, "uAtlasSize")
        foregroundAtlas = GLES20.glGetUniformLocation(foregroundProgram, "uAtlas")
        foregroundScale = GLES20.glGetUniformLocation(foregroundProgram, "uScale")

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
        GLES20.glClearColor(DEFAULT_BACKGROUND_R, DEFAULT_BACKGROUND_G, DEFAULT_BACKGROUND_B, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

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
    /**
     * The cursor, if there is one.
     *
     * Ghostty resolves the cursor's geometry to a cell and reports a visual
     * style; it does not paint it. The styles are 0 bar, 1 block, 2 underline
     * and 3 hollow block — the last draws its border and nothing else.
     *
     * Drawn after the glyphs, so a block cursor occludes the cell the engine
     * says it is sitting on, the way a terminal does.
     */
    private fun drawCursor(frame: Frame, size: SizeInfo) {
        if (cursorProgram == 0) return
        if (!frame.cursorVisible) return

        val projection = FloatArray(4)
        size.projection(projection)

        // The engine reports the colour as bytes R,G,B,A in little-endian.
        val argb = frame.cursorColor
        val a = (argb ushr 24 and 0xff) / 255f
        val b = (argb ushr 16 and 0xff) / 255f
        val g = (argb ushr 8 and 0xff) / 255f
        val r = (argb and 0xff) / 255f

        GLES20.glUseProgram(cursorProgram)
        GLES20.glUniform4fv(cursorProjection, 1, projection, 0)
        GLES20.glUniform2f(cursorCellSize, cellWidth, cellHeight)
        // Viewport-relative coordinates are the same rows text is drawn on.
        GLES20.glUniform2i(cursorCell, frame.cursorColumn, frame.cursorRow)
        GLES20.glUniform1i(cursorStyle, frame.cursorStyle)
        GLES20.glUniform4f(cursorColor, r, g, b, a)
        GLES20.glUniform2f(cursorCellPx, cellWidth, cellHeight)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

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
            putColor(backgrounds, frame.background(index))
        }
        backgrounds.flip()
    }

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
            foregrounds.putShort(entry.top.toShort())
            foregrounds.putShort(column.toShort())
            foregrounds.putShort(row.toShort())
            putColor(foregrounds, frame.foreground(index))
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

        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, frame.cellCount)
    }

    private fun drawForegrounds(frame: Frame, size: SizeInfo, count: Int) {
        if (count == 0) return

        val projection = FloatArray(4)
        size.projection(projection)

        GLES20.glUseProgram(foregroundProgram)
        GLES20.glUniform4fv(foregroundProjection, 1, projection, 0)
        GLES20.glUniform2f(foregroundCellSize, cellWidth, cellHeight)
        GLES20.glUniform1f(foregroundScale, size.pinchScale)
        GLES20.glUniform2f(foregroundAtlasSize, atlas.size.toFloat(), atlas.size.toFloat())

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

    private fun uploadAtlas() {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexture)
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            0,
            0,
            atlas.size,
            atlas.size,
            GLES30.GL_RED,
            GLES20.GL_UNSIGNED_BYTE,
            atlas.directPixels(),
        )
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

    private fun putColor(buffer: ByteBuffer, argb: Int) {
        // The bytes in order: R, G, B, A. The attribute is normalised
        // GL_UNSIGNED_BYTE, so the byte order is the colour order — what
        // used to be written was G, B, A, R, which drew every colour as
        // whatever the terminal thought black was and stayed hidden under
        // the white-on-black background.
        buffer.put(argb.toByte())
        buffer.put((argb ushr 8).toByte())
        buffer.put((argb ushr 16).toByte())
        buffer.put((argb ushr 24).toByte())
    }

    private companion object {
        const val TAG = "FrameRenderer"

        /** `u16 column, i16 row, vec4 colour`. */
        const val BACKGROUND_BYTES = 8

        /**
         * `u16×2 atlasRect, u16×2 glyphSize, i16×2 offset, u16×2 gridPos,
         * vec4 colour, u16 flags, u16 span`.
         */
        const val RECORD_BYTES = 24

        const val FLAG_NONE = 0

        /** The terminal's own ground, matching the palette's default. */
        const val DEFAULT_BACKGROUND_R = 0.094f
        const val DEFAULT_BACKGROUND_G = 0.094f
        const val DEFAULT_BACKGROUND_B = 0.094f
    }
}
