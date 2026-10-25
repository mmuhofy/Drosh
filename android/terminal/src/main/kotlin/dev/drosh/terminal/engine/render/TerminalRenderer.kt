package dev.drosh.terminal.engine.render

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import dev.drosh.terminal.engine.bench.FrameStats
import dev.drosh.terminal.engine.core.EngineSession
import dev.drosh.terminal.engine.core.NativeCore
import dev.drosh.terminal.engine.input.Selection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import dev.drosh.terminal.engine.core.Palette

/**
 * The renderer, reshaped after reading alacritty's GLES2 path and Ghostty's cell
 * shader (PLAN.md decision history, 2026-10-16 — the sources are named there):
 *
 * - **one instance per cell**, and the quad is built in the vertex shader from
 *   `gl_VertexID`, so no vertex carries a position — the record *is* the cell,
 *   which is what took Ghostty's per-cell cost down to ~20 bytes;
 * - **two passes over two streams**: a dense background colour per cell (the
 *   shader derives the cell from `gl_InstanceID`, so backgrounds store no
 *   position either) and a compact list of foreground records;
 * - both buffers are streamed per frame (`GL_STREAM_DRAW`, orphaning the old
 *   storage), which is three buffer updates and two draw calls for a whole
 *   screen — against up to five quads × six vertices × eight floats per cell
 *   before, which is where the device captures found the frame time;
 * - the glyph atlas is a single channel; the fragment shader tints it.
 *
 * GL ES 3.0 as decided in D3 (instancing and `glVertexAttribDivisor` are why);
 * the test device reports 3.2. A driver that refuses a draw with no per-vertex
 * attribute is covered by a dummy four-vertex buffer bound at a free location.
 */
internal class TerminalRenderer(
    private val session: EngineSession,
    private val atlas: GlyphAtlas,
    initialFontSize: FontSize,
    /** The current selection, drawn inverted like alacritty's default. */
    private val selection: Selection,
    /** Frame timings for the acceptance numbers. */
    private val stats: FrameStats,
    /**
     * Called once a frame that actually drew is done, and once more when a
     * frame was refused. The host counts them: a renderer that draws nothing
     * is otherwise invisible from outside, and a blank terminal with no
     * number behind it is the hardest kind of bug to read.
     */
    private val onFrame: (drawn: Boolean) -> Unit = {},
) : GLSurfaceView.Renderer {

    /**
     * Set by the view; called when the surface needs a different grid.
     *
     * The size the grid was laid out with travels with the report: the grid
     * and the cell size belong together, and the session is resized with
     * both.
     */
    var onGridChanged: ((columns: Int, rows: Int, fontSize: FontSize) -> Unit)? = null

    /** Last GL failure, read and cleared by the activity. */
    @Volatile
    var lastGlError: String? = null

    @Volatile
    var cursorVisible = true

    /** Logged once; useful when a device cannot do GL ES 3. */
    var glVersion: String = "unknown"
        private set

    private var records = CellRecords(MIN_COLUMNS, MIN_ROWS)
    private var columns = 0
    private var rows = 0
    private var width = 0
    private var height = 0

    /**
     * The cell geometry the frame is drawn with.
     *
     * GL-thread state: the view hands a new size over with `queueEvent`, and
     * the frame reads this field while it draws.
     */
    private var fontSize = initialFontSize

    /** The cell geometry, at the size the glyphs are rasterised at. */
    private val metrics: CellMetrics get() = fontSize.metrics

    /** Pixel size the glyphs are rasterised at. */
    private val fontPx: Float get() = fontSize.px

    /**
     * Ambient tint from Rust (0xRRGGBB), applied as the clear colour so the
     * terminal and its chrome read as one surface. `null` falls back to the
     * palette default (an `OSC 11` override still wins when set).
     */
    var ambientTint: Int? = null

    /**
     * Whether the font's ligatures are used.
     *
     * The application's setting: the font decides what a ligature is, and
     * this only says whether to ask it.
     */
    var ligatures: Boolean = false


    /**
     * The sub-line part of the scroll for the frame being drawn, in pixels.
     *
     * Read from the session at the top of `onDrawFrame`; the engine owns the
     * value, this is only the copy the two draw calls share.
     */
    private var scrollOffsetPx = 0f

    /** The `session.colorsStamp` whose table is currently installed. */
    private var appliedColorsStamp = 0

    /**
     * A frame's numbers, for the debug overlay.
     *
     * Read on the GL thread, written straight through: the overlay reads it a
     * few times a second and a torn read only means one stale number, which is
     * what an overlay is for anyway.
     */
    data class FrameReport(
        val frames: Int,
        val frameMillis: Float,
        val buildMillis: Float,
        val drawMillis: Float,
        val columns: Int,
        val rows: Int,
        val glyphs: Int,
        val coloredCells: Int,
        val atlasPercent: Int,
        val damage: Int,
        val background: Int,
        val gl: String,
    )

    /** The last frame's numbers, or null before the first frame. */
    @Volatile
    var report: FrameReport? = null
        private set

    private var frameCount = 0

    private var backgroundProgram = 0
    private var foregroundProgram = 0
    private var backgroundVao = 0
    private var backgroundVbo = 0
    private var foregroundVao = 0
    private var foregroundVbo = 0
    private var texture = 0

    private var backgroundProjection = 0
    private var backgroundCellSize = 0
    private var backgroundColumns = 0
    private var foregroundProjection = 0
    private var foregroundCellSize = 0
    private var foregroundAtlasSize = 0
    private var foregroundAtlas = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: "unknown"
        // A fresh context has no glyphs in it: the atlas and every buffer are
        // recreated below, so the first frame after this draws in full.
        Log.i(TAG, "GL version: $glVersion")

        backgroundProgram = buildProgram(BACKGROUND_VERTEX, BACKGROUND_FRAGMENT)
        foregroundProgram = buildProgram(FOREGROUND_VERTEX, FOREGROUND_FRAGMENT)
        if (backgroundProgram == 0 || foregroundProgram == 0) {
            return
        }

        backgroundProjection = GLES20.glGetUniformLocation(backgroundProgram, "uProjection")
        backgroundCellSize = GLES20.glGetUniformLocation(backgroundProgram, "uCellSize")
        backgroundColumns = GLES20.glGetUniformLocation(backgroundProgram, "uColumns")
        foregroundProjection = GLES20.glGetUniformLocation(foregroundProgram, "uProjection")
        foregroundCellSize = GLES20.glGetUniformLocation(foregroundProgram, "uCellSize")
        foregroundAtlasSize = GLES20.glGetUniformLocation(foregroundProgram, "uAtlasSize")
        foregroundAtlas = GLES20.glGetUniformLocation(foregroundProgram, "uAtlas")

        // The atlas belongs to the context that filled it. A new context means
        // the texture this renderer will bind has never been given a byte, and
        // the atlas's own caches still describe the old one — including its
        // `dirty` flag, which says "nothing changed since the last upload".
        // Without this reset every glyph drew as an empty mask: the frame was
        // a grid of backgrounds with no text on it, which is exactly what the
        // screen showed after every pause.
        atlas.reset()
        buildTexture()

        // Rasterise ASCII before the first frame: a burst should not pay for
        // glyphs one JNI call at a time (device run 2).
        atlas.warmUp(fontPx, false)

        buildBackgroundObjects()
        buildForegroundObjects()

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    override fun onSurfaceChanged(gl: GL10?, surfaceWidth: Int, surfaceHeight: Int) {
        width = surfaceWidth
        height = surfaceHeight
        GLES20.glViewport(0, 0, width, height)
        // The surface is new, so nothing is on it: the next frame draws even if
        // the engine says nothing changed.
        updateGrid()
    }

    /**
     * Draw the terminal at another size.
     *
     * The cell is re-measured by the caller — the font stack is the only
     * thing that knows how wide a cell is — and the grid is laid out again:
     * a bigger cell means fewer columns and fewer rows.
     *
     * Called on the GL thread, because the frame reads this state; the view
     * hands the request over with `queueEvent`.
     */
    fun setFontSize(size: FontSize) {
        if (size.px <= 0f || size.metrics.width <= 0f || size.metrics.height <= 0f) {
            return
        }
        if (size.px == fontSize.px && size.metrics == fontSize.metrics) {
            // The same size and the same cell again: the grid is already laid
            // out for it. The metrics are compared too, because a font can
            // change without the pixel size changing.
            return
        }

        fontSize = size
        // Nothing is invalidated here. The frame that follows is drawn because
        // the grid changed — `updateGrid` reports the new size when it differs —
        // and a size that produces the same grid changes nothing on screen.
        // The atlas keeps the previous size's masks as well as this one's: a
        // pinch changes size every frame, and throwing the masks away each time
        // re-rasterised every character on the frame after it — the text shook
        // during a pinch. Two generations are enough; the third drops the
        // first.
        atlas.beginSize(size.px)
        // Lay the grid out **now**, and report it, before the next frame.
        //
        // `onDrawFrame` builds the cell records for the session's grid, and
        // that build reads a buffer sized for the columns and rows of the
        // frame. A renderer whose grid was laid out for one size while the
        // session still held another was reading past the end of its own cell
        // buffer — the index out of bounds a fast pinch produced. The two are
        // kept in step here, at the moment the size changes, rather than a
        // frame later.
        updateGrid()
    }

    /**
     * Drop the atlas's masks.
     *
     * The font changed: every mask in the atlas was drawn from the old face,
     * and a mask of the wrong face is the wrong mask.
     */
    fun invalidateAtlas() {
        atlas.reset()
    }

    /**
     * Lay the grid out for the current surface and cell size, and report it.
     *
     * The report is how a layout change reaches the session: the renderer
     * knows the surface size, the session is only ever touched from the UI
     * thread.
     */
    private fun updateGrid() {
        if (width <= 0 || height <= 0) {
            // No surface yet: `onSurfaceChanged` lays the grid out when there
            // is one.
            return
        }

        val nextColumns = (width / metrics.width).toInt().coerceAtLeast(MIN_COLUMNS)
        val nextRows = (height / metrics.height).toInt().coerceAtLeast(MIN_ROWS)

        // Report a grid the session does not already have, and nothing else.
        //
        // This used to fire on every call, including the ones where neither the
        // size nor the cell had changed — and it is called from `setFontSize`,
        // which the host calls on every recomposition through
        // `applyAppearance`. The result was a resize posted to the UI thread and
        // a redraw requested several times a second on an idle terminal, which
        // is what made the picture blink.
        val changed = nextColumns != columns || nextRows != rows
        columns = nextColumns
        rows = nextRows
        records.resize(columns, rows)
        if (changed) {
            onGridChanged?.invoke(columns, rows, fontSize)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        val startedNanos = System.nanoTime()

        val cells = session.cells
        if (cells == null || backgroundProgram == 0 || foregroundProgram == 0) {
            return
        }
        if (session.columns != columns || session.rows != rows) {
            // The session has not caught up with the grid yet. Returning without
            // clearing leaves the previous frame on screen, which is a terminal
            // rather than the background colour.
            return
        }

        // One frame, in alacritty's order (`display/mod.rs`): clear the whole
        // surface, build every cell, draw. Nothing here is conditional — no
        // damage check, no scissor, no "nothing changed" shortcut.
        //
        // Every one of those was tried and each was wrong in its own way: a
        // scissor left the undamaged rows on a ground that was never repainted;
        // the idle check cleared the surface and then declined to draw on it;
        // the force flag made every recomposition a full redraw. A terminal is
        // a few thousand cells, and a phone's GPU fills them in well under a
        // millisecond. Correctness first; the numbers come from the overlay and
        // the work is only optimised once they say it needs to be.
        val damage = session.pump()
        if (damage < 0) {
            return
        }
        val pumpEndNanos = System.nanoTime()

        // `pump` refreshed the engine's colour table; install it only when the
        // engine says it changed.
        if (session.colorsStamp != appliedColorsStamp) {
            appliedColorsStamp = session.colorsStamp
            Palette.setOverrides(session.colors)
        }

        // The sub-line part of the scroll, as the engine holds it (kitty's
        // `pixel_scroll_offset_y`). Read from the same snapshot as the rows, so
        // the grid and the offset cannot disagree.
        scrollOffsetPx = session.stateArray[NativeCore.STATE_SUB_LINE_PX].toFloat()

        records.build(
            cells,
            session.stateArray,
            metrics,
            selection,
            cursorVisible,
            atlas,
            fontPx,
            ligatures,
        )
        val buildEndNanos = System.nanoTime()

        val background = ambientTint ?: Palette.background
        GLES20.glClearColor(channel(background, 16), channel(background, 8), channel(background, 0), 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // The atlas is uploaded after the build, not before it: a glyph the
        // build had to rasterise is in the atlas by now, and the texture has to
        // carry it before the frame draws it.
        if (atlas.dirty) {
            uploadAtlas()
        }

        uploadStreams()
        val uploadEndNanos = System.nanoTime()

        drawBackgrounds()
        drawForegrounds()

        val drawnAt = System.nanoTime()
        stats.record(
            startedNanos,
            pumpEndNanos,
            buildEndNanos,
            uploadEndNanos,
            drawnAt,
            records.foregroundCount,
        )
        frameCount += 1
        report = FrameReport(
            frames = frameCount,
            frameMillis = (drawnAt - startedNanos) / 1_000_000f,
            buildMillis = (buildEndNanos - pumpEndNanos) / 1_000_000f,
            drawMillis = (drawnAt - uploadEndNanos) / 1_000_000f,
            columns = columns,
            rows = rows,
            glyphs = records.foregroundCount,
            coloredCells = records.coloredCells,
            atlasPercent = atlas.usedPercent(),
            damage = damage,
            background = ambientTint ?: Palette.background,
            gl = glVersion,
        )
        onFrame(true)
    }

    /**
     * Both streams go up whole, with the old storage orphaned: a sub-range update
     * cannot be orphaned, and at these sizes one upload is cheaper than the
     * bookkeeping that would avoid it.
     */
    private fun uploadStreams() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, backgroundVbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            columns * rows * CellRecords.BACKGROUND_BYTES,
            records.backgroundBytes,
            GLES20.GL_STREAM_DRAW,
        )

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, foregroundVbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            records.foregroundCount * CellRecords.RECORD_BYTES,
            records.foregroundBytes,
            GLES20.GL_STREAM_DRAW,
        )
    }

    private fun drawBackgrounds() {
        val instances = columns * rows
        if (instances == 0) {
            return
        }

        GLES20.glUseProgram(backgroundProgram)
        // The whole screen moves as one: the projection's y origin carries the
        // sub-line offset, in the same clip units the scale uses.
        GLES20.glUniform4f(
            backgroundProjection,
            -1f,
            1f + scrollOffsetPx * 2f / height.coerceAtLeast(1),
            2f / width,
            -2f / height.coerceAtLeast(1),
        )
        GLES20.glUniform2f(backgroundCellSize, metrics.width, metrics.height)
        GLES20.glUniform1i(backgroundColumns, columns)
        GLES30.glBindVertexArray(backgroundVao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, QUAD_VERTICES, instances)
        GLES30.glBindVertexArray(0)
    }

    private fun drawForegrounds() {
        val instances = records.foregroundCount
        if (instances == 0) {
            return
        }

        GLES20.glUseProgram(foregroundProgram)
        GLES20.glUniform4f(
            foregroundProjection,
            -1f,
            1f + scrollOffsetPx * 2f / height.coerceAtLeast(1),
            2f / width,
            -2f / height.coerceAtLeast(1),
        )
        GLES20.glUniform2f(foregroundCellSize, metrics.width, metrics.height)
        GLES20.glUniform2f(foregroundAtlasSize, atlas.size.toFloat(), atlas.size.toFloat())
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(foregroundProgram, "uAtlas"), 0)
        GLES30.glBindVertexArray(foregroundVao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, QUAD_VERTICES, instances)
        GLES30.glBindVertexArray(0)
    }

    /** One `uint` colour per cell, fetched once per instance. */
    private fun buildBackgroundObjects() {
        val buffers = IntArray(2)
        GLES30.glGenVertexArrays(1, buffers, 0)
        backgroundVao = buffers[0]
        GLES20.glGenBuffers(1, buffers, 0)
        backgroundVbo = buffers[0]

        GLES30.glBindVertexArray(backgroundVao)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, backgroundVbo)
        GLES30.glEnableVertexAttribArray(ATTRIB_BACKGROUND_COLOR)
        GLES30.glVertexAttribIPointer(
            ATTRIB_BACKGROUND_COLOR,
            1,
            GLES30.GL_UNSIGNED_INT,
            CellRecords.BACKGROUND_BYTES,
            0,
        )
        GLES30.glVertexAttribDivisor(ATTRIB_BACKGROUND_COLOR, 1)
        bindDummyAttribute(ATTRIB_DUMMY_BACKGROUND)
        GLES30.glBindVertexArray(0)
    }

    /** The ~24-byte record per drawn thing, all of it per instance. */
    private fun buildForegroundObjects() {
        val buffers = IntArray(2)
        GLES30.glGenVertexArrays(1, buffers, 0)
        foregroundVao = buffers[0]
        GLES20.glGenBuffers(1, buffers, 0)
        foregroundVbo = buffers[0]

        GLES30.glBindVertexArray(foregroundVao)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, foregroundVbo)

        val stride = CellRecords.RECORD_BYTES
        GLES30.glEnableVertexAttribArray(ATTRIB_ATLAS_RECT)
        GLES30.glVertexAttribIPointer(ATTRIB_ATLAS_RECT, 2, GLES30.GL_UNSIGNED_SHORT, stride, 0)
        GLES30.glEnableVertexAttribArray(ATTRIB_GLYPH_SIZE)
        GLES30.glVertexAttribIPointer(ATTRIB_GLYPH_SIZE, 2, GLES30.GL_UNSIGNED_SHORT, stride, 4)
        GLES30.glEnableVertexAttribArray(ATTRIB_OFFSET)
        GLES30.glVertexAttribIPointer(ATTRIB_OFFSET, 2, GLES30.GL_SHORT, stride, 8)
        GLES30.glEnableVertexAttribArray(ATTRIB_GRID_POS)
        GLES30.glVertexAttribIPointer(ATTRIB_GRID_POS, 2, GLES30.GL_UNSIGNED_SHORT, stride, 12)
        GLES30.glEnableVertexAttribArray(ATTRIB_COLOR)
        GLES30.glVertexAttribPointer(
            ATTRIB_COLOR,
            4,
            GLES30.GL_UNSIGNED_BYTE,
            true,
            stride,
            16,
        )
        GLES30.glEnableVertexAttribArray(ATTRIB_FLAGS)
        GLES30.glVertexAttribIPointer(ATTRIB_FLAGS, 1, GLES30.GL_UNSIGNED_SHORT, stride, 20)

        for (attribute in ATTRIB_ATLAS_RECT..ATTRIB_FLAGS) {
            GLES30.glVertexAttribDivisor(attribute, 1)
        }
        bindDummyAttribute(ATTRIB_DUMMY_FOREGROUND)
        GLES30.glBindVertexArray(0)
    }

    /**
     * A four-vertex buffer at a location the shaders ignore.
     *
     * Some drivers refuse a draw where every enabled attribute is per-instance;
     * one dummy attribute with divisor 0 costs 16 bytes and removes the question.
     */
    private fun bindDummyAttribute(location: Int) {
        val buffers = IntArray(1)
        GLES20.glGenBuffers(1, buffers, 0)

        val vertices = ByteBuffer
            .allocateDirect(QUAD_VERTICES * 2 * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (vertex in 0 until QUAD_VERTICES) {
            vertices.put(vertex.toFloat())
            vertices.put(0f)
        }
        vertices.flip()

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffers[0])
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            QUAD_VERTICES * 2 * FLOAT_BYTES,
            vertices,
            GLES20.GL_STATIC_DRAW,
        )
        GLES30.glEnableVertexAttribArray(location)
        GLES30.glVertexAttribPointer(location, 2, GLES20.GL_FLOAT, false, 2 * FLOAT_BYTES, 0)
        GLES30.glVertexAttribDivisor(location, 0)
    }

    /** The atlas is a single-channel texture; the shader tints its coverage. */
    private fun buildTexture() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        // The field is filled before the first upload: the upload binds the
        // texture itself, and a texture that has not been named yet is not
        // the one the atlas belongs to.
        texture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // One byte per row is not a multiple of four: without this, rows would be
        // read with padding and every glyph would shear.
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        uploadAtlas()
    }

    private fun uploadAtlas() {
        // The texture is bound here rather than left to the caller: the
        // upload happens between two draw calls, and the binding is what
        // tells `glTexImage2D` which texture it fills.
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES30.GL_R8,
            atlas.size,
            atlas.size,
            0,
            GLES30.GL_RED,
            GLES20.GL_UNSIGNED_BYTE,
            atlas.directPixels(),
        )
    }

    private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (vertexShader == 0 || fragmentShader == 0) {
            return 0
        }

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            report("link failed: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            report("shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun report(message: String) {
        Log.e(TAG, message)
        lastGlError = message
    }

    private fun channel(color: Int, shift: Int): Float = (color shr shift and 0xff) / 255f

    private companion object {
        const val TAG = "DroshLab"
        const val MIN_COLUMNS = 2
        const val MIN_ROWS = 1
        const val FLOAT_BYTES = 4
        const val QUAD_VERTICES = 4

        const val ATTRIB_BACKGROUND_COLOR = 0
        const val ATTRIB_DUMMY_BACKGROUND = 1

        const val ATTRIB_ATLAS_RECT = 0
        const val ATTRIB_GLYPH_SIZE = 1
        const val ATTRIB_OFFSET = 2
        const val ATTRIB_GRID_POS = 3
        const val ATTRIB_COLOR = 4
        const val ATTRIB_FLAGS = 5
        const val ATTRIB_DUMMY_FOREGROUND = 6

        val BACKGROUND_VERTEX = """
            #version 300 es
            layout(location = 0) in uint aColor;
            uniform vec4 uProjection;
            uniform vec2 uCellSize;
            uniform int uColumns;
            out vec4 vColor;
            void main() {
                int column = gl_InstanceID % uColumns;
                int row = gl_InstanceID / uColumns;
                vec2 corner = vec2(
                    float(gl_VertexID == 1 || gl_VertexID == 3),
                    float(gl_VertexID == 2 || gl_VertexID == 3)
                );
                vec2 cell = vec2(float(column), float(row)) * uCellSize;
                gl_Position = vec4(uProjection.xy + uProjection.zw * (cell + corner * uCellSize), 0.0, 1.0);
                // The record is `0xAARRGGBB` in the CPU's Int, which is
                // little-endian on the wire: the shader sees it as a uint whose
                // low byte is the blue channel.
                vColor = vec4(
                    float((aColor >> 16) & 0xffu),
                    float((aColor >> 8) & 0xffu),
                    float(aColor & 0xffu),
                    1.0
                ) / 255.0;
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

        val FOREGROUND_VERTEX = """
            #version 300 es
            layout(location = 0) in uvec2 aAtlasRect;
            layout(location = 1) in uvec2 aGlyphSize;
            layout(location = 2) in ivec2 aOffset;
            layout(location = 3) in uvec2 aGridPos;
            layout(location = 4) in vec4 aColor;
            layout(location = 5) in uint aFlags;
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
                vec2 size = vec2(aGlyphSize);
                // The record's offset is *inside* the cell; the cell origin comes
                // from the grid position. Both together, once.
                vec2 cell = vec2(aGridPos) * uCellSize;
                gl_Position = vec4(
                    uProjection.xy + uProjection.zw * (cell + vec2(aOffset) + corner * size),
                    0.0,
                    1.0
                );
                // A solid quad is a fill, not a glyph: its width/height are pixels,
                // so walking the UV with them would sample across the atlas.
                vTexCoord = (aFlags & 1u) != 0u
                    ? (vec2(aAtlasRect) + 0.5) / uAtlasSize
                    : (vec2(aAtlasRect) + corner * size) / uAtlasSize;
                vColor = aColor;
            }
        """.trimIndent()

        val FOREGROUND_FRAGMENT = """
            #version 300 es
            precision mediump float;
            // Single channel (GL_R8): the glyph coverage the Rust stack rasterised.
            uniform sampler2D uAtlas;
            in vec2 vTexCoord;
            in vec4 vColor;
            out vec4 fragColor;
            void main() {
                float coverage = texture(uAtlas, vTexCoord).r;
                fragColor = vec4(vColor.rgb, vColor.a * coverage);
            }
        """.trimIndent()
    }
}
