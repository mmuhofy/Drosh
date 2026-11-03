package dev.drosh.terminal.engine.render

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import dev.drosh.terminal.engine.bench.FrameStats
import dev.drosh.terminal.engine.core.EngineSession
import dev.drosh.terminal.engine.core.NativeCore
import dev.drosh.terminal.engine.core.Palette
import dev.drosh.terminal.engine.input.Selection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The renderer.
 *
 * ## One frame, in alacritty's order
 *
 * `display/mod.rs`: clear the surface, build every cell, draw. Nothing here is
 * conditional — no damage check, no scissor, no "nothing changed" shortcut.
 * Each of those was tried and each was wrong in its own way: a scissor left the
 * undamaged rows on a ground that was never repainted; the idle check cleared
 * the surface and then declined to draw on it. A terminal is a few thousand
 * cells and a phone's GPU fills them in well under a millisecond.
 *
 * ## One size, in alacritty's shape
 *
 * [size] is the only description of how big anything is. The surface, the cell
 * and the grid are derived from it, so they cannot drift apart — see
 * [SizeInfo]. Six independent fields used to hold those facts, written from
 * four places, and every screen bug of the past weeks was one of them being one
 * update behind.
 *
 * ## The pinch scales the text, not the grid
 *
 * A pinch changes [SizeInfo.pinchScale], and the glyph shader multiplies the
 * glyph's own size and offset by it. The cells stay where they are and the
 * projection is a plain pixel-to-clip map: the letters grow inside the cells
 * they already occupy.
 *
 * Scaling the cell step as well — which an earlier version did — zooms the
 * whole picture like an image: fewer columns fit, the cursor stops lining up,
 * and the terminal is a different size rather than a bigger one. Scaling the
 * projection has the same fault twice over.
 *
 * Nothing is re-measured, re-rasterised or reflowed during a gesture. The real
 * size is applied once, when the fingers lift.
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

    private var records = CellRecords(SizeInfo.MIN_COLUMNS, SizeInfo.MIN_ROWS)

    /**
     * Everything about the size, in one value.
     *
     * GL-thread state: the view hands changes over with `queueEvent`, and the
     * frame reads this while it draws.
     */
    private var size = SizeInfo.empty(initialFontSize.metrics.width, initialFontSize.metrics.height)

    /**
     * The cell geometry the frame is drawn with.
     *
     * GL-thread state: the view hands a new size over with `queueEvent`, and
     * the frame reads this field while it draws.
     */
    private var fontSize = initialFontSize

    /**
     * The cell geometry, at the size the glyphs are rasterised at.
     *
     * The *committed* cell — the one the grid is laid out with. A pinch changes
     * the size the glyphs are drawn at, not the cell they sit in: the columns
     * belong to the terminal, not to the font.
     */
    private val metrics: CellMetrics get() = fontSize.metrics

    /**
     * Pixel size the glyphs are rasterised at.
     *
     * The committed size, times the live pinch. A pinch re-rasterises at the
     * size the fingers have reached rather than scaling a bitmap, because a
     * scaled bitmap doubles its strokes: the text grew bolder as it grew, which
     * is a blurry font rather than a large one.
     */
    private val fontPx: Float get() = fontSize.px * size.pinchScale

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
     * this only asks it.
     */
    var ligatures: Boolean = false

    /**
     * The grid the session currently has, as the renderer last applied it.
     *
     * Kept apart from [size] because they are allowed to differ for the length
     * of an animation: the picture is drawn at [size] while the session is
     * still at [appliedColumns]/[appliedRows]. The reflow happens once, when
     * the surface stops.
     */
    private var appliedColumns = 0
    private var appliedRows = 0

    /** The `session.colorsStamp` whose table is currently installed. */
    private var appliedColorsStamp = 0

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
    private var foregroundProjection = 0
    private var foregroundCellSize = 0
    private var foregroundAtlasSize = 0
    private var foregroundAtlas = 0

    /** Scratch for the projection; a frame builds it twice, and neither allocates. */
    private val projectionScratch = FloatArray(4)

    // --- lifecycle ---------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glVersion = GLES20.glGetString(GLES20.GL_VERSION) ?: "unknown"
        Log.i(TAG, "GL version: $glVersion")

        backgroundProgram = buildProgram(BACKGROUND_VERTEX, BACKGROUND_FRAGMENT)
        foregroundProgram = buildProgram(FOREGROUND_VERTEX, FOREGROUND_FRAGMENT)
        if (backgroundProgram == 0 || foregroundProgram == 0) {
            return
        }

        backgroundProjection = GLES20.glGetUniformLocation(backgroundProgram, "uProjection")
        backgroundCellSize = GLES20.glGetUniformLocation(backgroundProgram, "uCellSize")
        foregroundProjection = GLES20.glGetUniformLocation(foregroundProgram, "uProjection")
        foregroundCellSize = GLES20.glGetUniformLocation(foregroundProgram, "uCellSize")
        foregroundAtlasSize = GLES20.glGetUniformLocation(foregroundProgram, "uAtlasSize")
        foregroundAtlas = GLES20.glGetUniformLocation(foregroundProgram, "uAtlas")

        // The atlas belongs to the context that filled it. A new context means
        // the texture this renderer will bind has never been given a byte, and
        // the atlas's own caches still describe the old one — including its
        // `dirty` flag, which says "nothing changed since the last upload".
        // Without this reset every glyph drew as an empty mask: the frame was
        // a grid of backgrounds with no text on it, which is what the screen
        // showed after every pause.
        atlas.reset()
        buildTexture()

        // No ASCII warm-up here.
        //
        // It rasterised ninety-five glyphs on the GL thread before the first
        // frame could be drawn, and `onSurfaceCreated` runs again whenever the
        // surface is recreated — which a keyboard opening does. The cost was
        // visible: a black rectangle for as long as the warm-up took, then the
        // terminal appearing. The glyphs are cached per size anyway, so the
        // first frame after a context loss pays for only what is on screen.

        buildBackgroundObjects()
        buildForegroundObjects()

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    override fun onSurfaceChanged(gl: GL10?, surfaceWidth: Int, surfaceHeight: Int) {
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        size = size.withSurface(surfaceWidth, surfaceHeight)

        // The grid follows the surface on the same frame, always.
        //
        // Holding it — which an earlier version did to avoid reflowing on every
        // frame of a keyboard animation — meant the cells were drawn at the new
        // step over the old row count. The text did not move with the surface:
        // rows overlapped and columns ran past the edge, which is the "text
        // inside itself" a keyboard opening produced.
        //
        // One reflow per frame of an animation is the cheaper of the two
        // problems: the engine re-wraps a few thousand cells, which is a
        // fraction of a millisecond, and the alternative is a picture that is
        // visibly wrong for three hundred milliseconds.
        planGrid()
    }


    // --- size --------------------------------------------------------------

    /**
     * Draw the terminal at another cell size.
     *
     * The cell is re-measured by the caller — the font stack is the only thing
     * that knows how wide a cell is. Called on the GL thread; the view hands
     * the request over with `queueEvent`.
     */
    fun setFontSize(next: FontSize) {
        if (next.px <= 0f || next.metrics.width <= 0f || next.metrics.height <= 0f) {
            return
        }
        if (next.px == fontSize.px && next.metrics == fontSize.metrics) {
            return
        }

        fontSize = next
        // The masks of the old size are wrong, and the atlas has to be asked for
        // the new one. Nothing is thrown away: the atlas and the per-cell cache
        // key themselves by the size a mask was *rasterised* at, so a gesture
        // that moves a tenth of a pixel asks for masks that are already there.
        atlas.beginSize(next.px)
        size = size.withCell(next.metrics.width, next.metrics.height)

        // A committed size is the thing the user asked to see, so the grid is
        // laid out now rather than waiting for the surface to settle.
        planGrid()
    }

    /** Drop the atlas's masks. The font changed, and a mask of the wrong face is the wrong mask. */
    fun invalidateAtlas() {
        atlas.reset()
    }

    /**
     * A live pinch: rasterise the glyphs at the size the fingers have reached,
     * inside the cells they already occupy.
     *
     * **The glyphs are re-rasterised, not scaled.** A bitmap scaled up by
     * nearest-neighbour doubles its strokes — a pinch made the text bolder the
     * more it grew, which is not a bigger font but a blurrier one. Rasterising
     * at the new size is the same cost per glyph as any other size change, and
     * the atlas already caches by size, so a gesture that passes through a size
     * twice pays for it once.
     *
     * The grid does not move: the cell keeps its size and its place, and the
     * letters grow inside it. That is what "make the text bigger" means — the
     * columns are the terminal's, not the font's.
     */
    fun setPinch(scale: Float, focusX: Float, focusY: Float) {
        val safe = if (scale.isFinite() && scale > 0f) scale else 1f
        // The size the masks are rasterised at, scaled. `atlas.beginSize` keys
        // its generations by this, so a gesture that wanders back over a size it
        // just left finds its glyphs again.
        val pinchPx = fontSize.px * safe
        if (pinchPx > 0f) {
            atlas.beginSize(pinchPx)
        }
        size = size.copy(
            pinchScale = safe,
            pinchFocusX = focusX,
            pinchFocusY = focusY,
        )
    }

    /**
     * End a pinch: apply the size the fingers reached, once.
     *
     * The only frame of a gesture that measures, rasterises and reflows, and it
     * happens after the fingers have stopped.
     */
    fun commitZoom(next: FontSize) {
        size = size.copy(pinchScale = 1f, pinchFocusX = 0f, pinchFocusY = 0f)
        setFontSize(next)
    }

    // --- grid --------------------------------------------------------------

    /**
     * Work out the grid the surface asks for and apply it.
     *
     * Applied immediately, always. An earlier version held the reflow while the
     * surface was moving — a keyboard rising is sixty surface changes in three
     * hundred milliseconds, and each is a `Term::resize` — but holding it left
     * the cells drawn at the new step over the old row count, which is text
     * overlapping itself. A reflow of a few thousand cells is a fraction of a
     * millisecond; a picture that is wrong for three hundred is not.
     */
    private fun planGrid() {
        if (size.width <= 0 || size.height <= 0) {
            return
        }
        applyGrid(size.columns, size.rows)
    }

    /**
     * Lay the grid out and tell the session.
     *
     * The one place the session learns its shape, so the grid and the session
     * cannot disagree.
     */
    private fun applyGrid(columns: Int, rows: Int) {
        if (columns == appliedColumns && rows == appliedRows) {
            return
        }
        appliedColumns = columns
        appliedRows = rows
        records.resize(columns, rows)
        onGridChanged?.invoke(columns, rows, fontSize)
    }

    // --- frame -------------------------------------------------------------

    override fun onDrawFrame(gl: GL10?) {
        val startedNanos = System.nanoTime()

        val cells = session.cells
        if (cells == null || backgroundProgram == 0 || foregroundProgram == 0) {
            return
        }

        // The session's shape and the grid being drawn must agree: the cell
        // buffer is sized for the session. They can differ for the length of an
        // animation, and then the session is brought to the grid here rather
        // than waited for.
        if (session.columns != appliedColumns || session.rows != appliedRows) {
            session.resize(
                appliedColumns,
                appliedRows,
                metrics.width.toInt(),
                metrics.height.toInt(),
            )
            if (session.columns != appliedColumns || session.rows != appliedRows) {
                // The engine refused the size. Clear rather than leave a stale
                // picture over a resized surface, and ask for another frame
                // instead of waiting for output that may never come.
                clearToBackground()
                return
            }
        }

        val damage = session.pump()
        if (damage < 0) {
            return
        }
        val pumpEndNanos = System.nanoTime()

        // The sub-line part of the scroll, from the same snapshot as the cells:
        // one lock hold, one position.
        size = size.copy(scrollOffsetPx = session.stateArray[NativeCore.STATE_SUB_LINE_PX].toFloat())

        // `pump` refreshed the engine's colour table; install it only when the
        // engine says it changed.
        if (session.colorsStamp != appliedColorsStamp) {
            appliedColorsStamp = session.colorsStamp
            Palette.setOverrides(session.colors)
        }

        val built = records.build(
            cells,
            session.stateArray,
            metrics,
            selection,
            cursorVisible,
            atlas,
            fontPx,
            ligatures,
        )
        if (!built) {
            // The cell buffer does not hold this grid, so nothing was built.
            // Uploading would read past the end of an empty buffer — the
            // `remaining() < size` crash a program hit when it switched to the
            // alternate screen.
            //
            // The surface is still painted, because a `GLSurfaceView` that is
            // not drawn to keeps whatever was in its buffer — which, after a
            // resize, is nothing. Leaving it alone is the black rectangle a
            // keyboard opening produced; painting the background is at least
            // the terminal's own colour, and the next frame draws the text.
            clearToBackground()
            return
        }
        val buildEndNanos = System.nanoTime()

        clearToBackground()

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
            columns = appliedColumns,
            rows = appliedRows,
            glyphs = records.foregroundCount,
            coloredCells = records.coloredCells,
            atlasPercent = atlas.usedPercent(),
            damage = damage,
            background = ambientTint ?: Palette.background,
            gl = glVersion,
            samples = records.colouredSamples.toList(),
            pinchScale = size.pinchScale,
            cellStepX = size.stepX,
            cellStepY = size.stepY,
            surfaceWidth = size.width,
            surfaceHeight = size.height,
        )
        onFrame(true)
    }

    private fun clearToBackground() {
        val background = ambientTint ?: Palette.background
        GLES20.glClearColor(
            channel(background, 16),
            channel(background, 8),
            channel(background, 0),
            1f,
        )
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    /**
     * Both streams go up whole, with the old storage orphaned: a sub-range
     * update cannot be orphaned, and at these sizes one upload is cheaper than
     * the bookkeeping that would avoid it.
     */
    private fun uploadStreams() {
        // A buffer with nothing in it is not uploaded at all: `glBufferData`
        // takes the size from the caller and the bytes from the buffer, and a
        // cleared buffer has no bytes.
        if (records.backgroundBytes.limit() <= 0 || records.foregroundBytes.limit() <= 0) {
            return
        }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, backgroundVbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER,
            appliedColumns * appliedRows * CellRecords.BACKGROUND_BYTES,
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
        val instances = appliedColumns * appliedRows
        if (instances == 0) {
            return
        }

        GLES20.glUseProgram(backgroundProgram)
        size.projection(projectionScratch)
        GLES20.glUniform4f(
            backgroundProjection,
            projectionScratch[0],
            projectionScratch[1],
            projectionScratch[2],
            projectionScratch[3],
        )
        // The step *without* the pinch, and the pinch as its own uniform: the
        // shader multiplies them, so the scale is applied once and to the cell
        // origin and the cell size alike.
        GLES20.glUniform2f(
            backgroundCellSize,
            size.width.toFloat() / appliedColumns.coerceAtLeast(1),
            size.height.toFloat() / appliedRows.coerceAtLeast(1),
        )

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
        size.projection(projectionScratch)
        GLES20.glUniform4f(
            foregroundProjection,
            projectionScratch[0],
            projectionScratch[1],
            projectionScratch[2],
            projectionScratch[3],
        )
        GLES20.glUniform2f(
            foregroundCellSize,
            size.width.toFloat() / appliedColumns.coerceAtLeast(1),
            size.height.toFloat() / appliedRows.coerceAtLeast(1),
        )
        GLES20.glUniform2f(foregroundAtlasSize, atlas.size.toFloat(), atlas.size.toFloat())
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(foregroundAtlas, 0)
        GLES30.glBindVertexArray(foregroundVao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, QUAD_VERTICES, instances)
        GLES30.glBindVertexArray(0)
    }

    // --- GL objects --------------------------------------------------------

    /** One `uint` colour per cell, fetched once per instance. */
    private fun buildBackgroundObjects() {
        val buffers = IntArray(2)
        GLES30.glGenVertexArrays(1, buffers, 0)
        backgroundVao = buffers[0]
        GLES20.glGenBuffers(1, buffers, 0)
        backgroundVbo = buffers[0]

        GLES30.glBindVertexArray(backgroundVao)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, backgroundVbo)
        GLES30.glEnableVertexAttribArray(ATTRIB_BACKGROUND_POS)
        GLES30.glVertexAttribIPointer(
            ATTRIB_BACKGROUND_POS,
            2,
            GLES30.GL_UNSIGNED_SHORT,
            CellRecords.BACKGROUND_BYTES,
            0,
        )
        GLES30.glEnableVertexAttribArray(ATTRIB_BACKGROUND_COLOR)
        GLES30.glVertexAttribPointer(
            ATTRIB_BACKGROUND_COLOR,
            4,
            GLES30.GL_UNSIGNED_BYTE,
            true,
            CellRecords.BACKGROUND_BYTES,
            4,
        )
        GLES30.glVertexAttribDivisor(ATTRIB_BACKGROUND_POS, 1)
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
     * one dummy attribute with divisor 0 costs 16 bytes and removes the
     * question.
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
        texture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // One byte per row is not a multiple of four: without this, rows would
        // be read with padding and every glyph would shear.
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        uploadAtlas()
    }

    private fun uploadAtlas() {
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
        const val FLOAT_BYTES = 4
        const val QUAD_VERTICES = 4

        const val ATTRIB_BACKGROUND_POS = 0
        const val ATTRIB_BACKGROUND_COLOR = 1
        const val ATTRIB_DUMMY_BACKGROUND = 2

        const val ATTRIB_ATLAS_RECT = 0
        const val ATTRIB_GLYPH_SIZE = 1
        const val ATTRIB_OFFSET = 2
        const val ATTRIB_GRID_POS = 3
        const val ATTRIB_COLOR = 4
        const val ATTRIB_FLAGS = 5
        const val ATTRIB_DUMMY_FOREGROUND = 6

        /**
         * The cell quad.
         *
         * `uCellSize` is the step the cells are placed at, in pixels. There is
         * no scale here: a pinch changes the *text* size, not the grid, and
         * zooming the cells would change how many fit on the screen.
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
                vec2 cell = vec2(aGridPos) * uCellSize;
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
         * The glyph quad.
         *
         * The record's `aOffset` and `aGlyphSize` are in pixels *at the size the
         * mask was rasterised* — they describe the glyph, not the cell. A pinch
         * re-rasterises at the new size rather than scaling these, because a
         * scaled bitmap doubles its strokes: the text grew bolder as it grew
         * bigger, which is a blurry font rather than a large one.
         */
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
                // The cell is where it is; only what is drawn inside it grows.
                // Scaling the step as well would zoom the whole grid, which is
                // not what a text size is: the columns would change and the
                // screen would stop being a terminal.
                vec2 cell = vec2(aGridPos) * uCellSize;
                vec2 size = vec2(aGlyphSize);
                vec2 offset = vec2(aOffset);
                gl_Position = vec4(
                    uProjection.xy + uProjection.zw * (cell + offset + corner * size),
                    0.0,
                    1.0
                );
                // The UV walks the *atlas* texels, which do not scale: a solid
                // quad samples one texel, a glyph samples its own rectangle.
                vTexCoord = (aFlags & 1u) != 0u
                    ? (vec2(aAtlasRect) + 0.5) / uAtlasSize
                    : (vec2(aAtlasRect) + corner * vec2(aGlyphSize)) / uAtlasSize;
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
