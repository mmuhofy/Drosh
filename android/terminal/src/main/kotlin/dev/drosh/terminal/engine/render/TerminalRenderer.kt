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

    /** The last frame's numbers, or null before the first frame. */
    @Volatile
    var report: FrameReport? = null
        private set

    /** What the background pass was handed, for the overlay. */
    @Volatile
    private var backgroundProbe: String = ""

    private var frameCount = 0

    private var cellProgram = 0
    private var cellVao = 0
    private var cellVbo = 0
    private var glyphVao = 0
    private var glyphVbo = 0
    private var texture = 0

    private var cellProjection = 0
    private var cellSize = 0
    private var cellAtlasSize = 0
    private var cellPass = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: "unknown"
        // A fresh context has no glyphs in it: the atlas and every buffer are
        // recreated below, so the first frame after this draws in full.
        Log.i(TAG, "GL version: $glVersion")

        cellProgram = buildProgram(CELL_VERTEX, CELL_FRAGMENT)
        if (cellProgram == 0) {
            return
        }

        cellProjection = GLES20.glGetUniformLocation(cellProgram, "uProjection")
        cellSize = GLES20.glGetUniformLocation(cellProgram, "uCellSize")
        cellAtlasSize = GLES20.glGetUniformLocation(cellProgram, "uAtlasSize")
        cellPass = GLES20.glGetUniformLocation(cellProgram, "uPass")

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

        buildCellObjects()
        buildGlyphObjects()

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
        if (cells == null || cellProgram == 0) {
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

        drawCells()

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
            samples = records.colouredSamples.toList(),
            backgroundProbe = backgroundProbe,
        )
        onFrame(true)
    }

    /**
     * Both streams go up whole, with the old storage orphaned: a sub-range update
     * cannot be orphaned, and at these sizes one upload is cheaper than the
     * bookkeeping that would avoid it.
     */
    private fun uploadStreams() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, cellVbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            columns * rows * CellRecords.BACKGROUND_BYTES,
            records.backgroundBytes,
            GLES20.GL_STREAM_DRAW,
        )

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, glyphVbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            records.foregroundCount * CellRecords.RECORD_BYTES,
            records.foregroundBytes,
            GLES20.GL_STREAM_DRAW,
        )
    }

    /**
     * Both passes over the same instance stream.
     *
     * Pass 0 fills every cell's background, pass 1 draws the glyphs on top.
     * The stream is the cells themselves, so a cell that carries a colour the
     * program chose is drawn in it — which is what alacritty does and what two
     * separate buffers failed to guarantee here.
     */
    private fun drawCells() {
        val backgroundInstances = columns * rows
        if (backgroundInstances > 0) {
            GLES20.glUseProgram(cellProgram)
            // The whole screen moves as one: the projection's y origin carries
            // the sub-line offset, in the same clip units the scale uses.
            GLES20.glUniform4f(
                cellProjection,
                -1f,
                1f + scrollOffsetPx * 2f / height.coerceAtLeast(1),
                2f / width,
                -2f / height.coerceAtLeast(1),
            )
            GLES20.glUniform2f(cellSize, metrics.width, metrics.height)
            GLES20.glUniform2f(cellAtlasSize, atlas.size.toFloat(), atlas.size.toFloat())
            GLES20.glUniform1i(cellPass, 0)
            GLES30.glBindVertexArray(cellVao)
            GLES30.glDrawArraysInstanced(
                GLES30.GL_TRIANGLE_STRIP,
                0,
                QUAD_VERTICES,
                backgroundInstances,
            )

            if (frameCount < 200) {
                val first = records.backgroundBytes.getInt(0)
                backgroundProbe = "bgdraw " + backgroundInstances +
                    " first=#" + Integer.toHexString(first) +
                    " err=" + GLES20.glGetError()
            }
        }

        val instances = records.foregroundCount
        if (instances == 0) {
            GLES30.glBindVertexArray(0)
            return
        }

        GLES20.glUseProgram(cellProgram)
        GLES20.glUniform1i(cellPass, 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(cellProgram, "uAtlas"), 0)
        GLES30.glBindVertexArray(glyphVao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, QUAD_VERTICES, instances)
        GLES30.glBindVertexArray(0)
    }

    /**
     * One VAO binding both streams.
     *
     * The background records are per cell; the foreground records are the
     * glyphs those cells ask for. Both are instance attributes with divisor 1,
     * and there is no dummy attribute: every attribute the program declares is
     * fed, which is the one thing some drivers insist on.
     */
    private fun buildCellObjects() {
        val buffers = IntArray(2)
        GLES30.glGenVertexArrays(1, buffers, 0)
        cellVao = buffers[0]
        GLES20.glGenBuffers(1, buffers, 0)
        cellVbo = buffers[0]

        GLES30.glBindVertexArray(cellVao)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, cellVbo)
        GLES30.glEnableVertexAttribArray(ATTRIB_BACKGROUND_COLOR)
        GLES30.glVertexAttribIPointer(
            ATTRIB_BACKGROUND_COLOR,
            1,
            GLES30.GL_UNSIGNED_INT,
            CellRecords.BACKGROUND_BYTES,
            0,
        )
        GLES30.glVertexAttribDivisor(ATTRIB_BACKGROUND_COLOR, 1)
        GLES30.glBindVertexArray(0)
    }

    /** The ~24-byte record per drawn glyph, all of it per instance. */
    private fun buildGlyphObjects() {
        val buffers = IntArray(2)
        GLES30.glGenVertexArrays(1, buffers, 0)
        glyphVao = buffers[0]
        GLES20.glGenBuffers(1, buffers, 0)
        glyphVbo = buffers[0]

        GLES30.glBindVertexArray(glyphVao)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, glyphVbo)

        val stride = CellRecords.RECORD_BYTES
        GLES30.glEnableVertexAttribArray(ATTRIB_GRID_POS)
        GLES30.glVertexAttribIPointer(ATTRIB_GRID_POS, 2, GLES30.GL_UNSIGNED_SHORT, stride, 12)
        GLES30.glEnableVertexAttribArray(ATTRIB_COLOR)
        GLES30.glVertexAttribPointer(ATTRIB_COLOR, 4, GLES30.GL_UNSIGNED_BYTE, true, stride, 16)
        GLES30.glEnableVertexAttribArray(ATTRIB_GLYPH)
        GLES30.glVertexAttribIPointer(ATTRIB_GLYPH, 4, GLES30.GL_UNSIGNED_SHORT, stride, 0)
        GLES30.glEnableVertexAttribArray(ATTRIB_OFFSET)
        GLES30.glVertexAttribIPointer(ATTRIB_OFFSET, 2, GLES30.GL_SHORT, stride, 8)
        GLES30.glEnableVertexAttribArray(ATTRIB_FLAGS)
        GLES30.glVertexAttribIPointer(ATTRIB_FLAGS, 1, GLES30.GL_UNSIGNED_SHORT, stride, 20)

        for (attribute in ATTRIB_GRID_POS..ATTRIB_FLAGS) {
            GLES30.glVertexAttribDivisor(attribute, 1)
        }
        GLES30.glBindVertexArray(0)
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

        /** The background pass: one `uint` colour per cell. */
        const val ATTRIB_BACKGROUND_COLOR = 0

        /** The glyph pass: grid position, colour, atlas rect, offset, flags. */
        const val ATTRIB_GRID_POS = 0
        const val ATTRIB_COLOR = 1
        const val ATTRIB_GLYPH = 2
        const val ATTRIB_OFFSET = 3
        const val ATTRIB_FLAGS = 4

        /**
         * Both passes, one program.
         *
         * This is alacritty's shape (`res/glsl3/text.v.glsl`): the vertex
         * stream carries the grid position and *both* colours, and
         * `renderingPass` decides which quad the shader builds — the cell's
         * background, or the glyph inside it. Two programs over two streams
         * was the earlier layout, and it needed a dummy attribute and an
         * instance-id-derived position to work at all; the driver was entitled
         * to ignore the second stream, and on this device it did.
         */
        val CELL_VERTEX = """
            #version 300 es
            // Per instance, one record per drawn cell.
            layout(location = 0) in uvec2 aGridPos;
            // Per instance, a normalised u8x4 colour.
            layout(location = 1) in vec4 aColor;
            layout(location = 2) in vec4 aBackgroundColor;
            // Glyph: atlas rect (x, y, w, h), in-cell offset, flags.
            layout(location = 3) in uvec4 aGlyph;
            layout(location = 4) in ivec2 aOffset;
            layout(location = 5) in uint aFlags;

            uniform vec4 uProjection;
            uniform vec2 uCellSize;
            uniform vec2 uAtlasSize;
            uniform int uPass;

            out vec2 vTexCoord;
            out vec4 vColor;
            out vec4 vBackground;

            void main() {
                vec2 corner = vec2(
                    float(gl_VertexID == 1 || gl_VertexID == 3),
                    float(gl_VertexID == 2 || gl_VertexID == 3)
                );
                vec2 cell = vec2(aGridPos) * uCellSize;

                vColor = aColor;
                vBackground = aBackgroundColor;

                if (uPass == 0) {
                    // The cell's own quad. A background is a fill, not a glyph.
                    gl_Position = vec4(
                        uProjection.xy + uProjection.zw * (cell + corner * uCellSize),
                        0.0,
                        1.0
                    );
                    vTexCoord = vec2(0.0);
                } else {
                    vec2 size = vec2(aGlyph.zw);
                    gl_Position = vec4(
                        uProjection.xy + uProjection.zw * (cell + vec2(aOffset) + corner * size),
                        0.0,
                        1.0
                    );
                    // A solid quad samples one texel: walking the UV with its
                    // pixel size would smear across the atlas.
                    vTexCoord = (aFlags & 1u) != 0u
                        ? (vec2(aGlyph.xy) + 0.5) / uAtlasSize
                        : (vec2(aGlyph.xy) + corner * size) / uAtlasSize;
                }
            }
        """.trimIndent()

        val CELL_FRAGMENT = """
            #version 300 es
            precision mediump float;
            uniform sampler2D uAtlas;
            uniform int uPass;
            in vec2 vTexCoord;
            in vec4 vColor;
            in vec4 vBackground;
            out vec4 fragColor;
            void main() {
                if (uPass == 0) {
                    fragColor = vec4(vBackground.rgb, 1.0);
                } else {
                    // Single channel (GL_R8): the glyph coverage the Rust
                    // stack rasterised.
                    float coverage = texture(uAtlas, vTexCoord).r;
                    fragColor = vec4(vColor.rgb, vColor.a * coverage);
                }
            }
        """.trimIndent()
    }
}
