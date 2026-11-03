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
     * The committed size, and only that: the atlas holds masks of it, and a
     * pinch scales them in the shader rather than re-rasterising. See
     * [setPinch].
     */
    private val fontPx: Float get() = fontSize.px

    /**
     * The terminal's own background, as the palette resolves it (0xRRGGBB).
     *
     * This is the clear colour, and it is the *terminal's* background — the one
     * `OSC 11` sets, or the theme's. It is not the average of what the program
     * has drawn: that average changes as the grid changes, so scrolling, a
     * resize or a full-screen program repainting turned the whole surface a
     * different colour. The chrome is tinted from the average elsewhere; the
     * terminal's own ground is one colour and stays one colour.
     */
    var backgroundOverride: Int? = null

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

    /** Whether a complete picture has been drawn since the surface was created. */
    private var paintedFrame = false

    /**
     * Draw the records already built, whatever they were built from.
     *
     * A frame that cannot pump or build — a resize in flight, a session still
     * starting — must still fill the buffer it is given: the swap happens
     * regardless, and a buffer filled with only the clear colour is the
     * one-frame wipe the screen showed on every state change.
     */
    private fun redrawLast() {
        if (!paintedFrame) {
            return
        }
        uploadStreams()
        drawBackgrounds()
        drawForegrounds()
        paintedFrame = true
    }

    /**
     * When the grid may be reflowed after a surface change, or 0 when none is
     * pending.
     *
     * See [onSurfaceChanged]: a moving surface defers the reflow, and the next
     * frame after this instant applies it once.
     */
    private var settleAtNanos = 0L

    /** Whether a surface change is waiting for the grid to catch up. */
    val settlePending: Boolean get() = settleAtNanos != 0L

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
    private var foregroundScale = 0
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
        foregroundScale = GLES20.glGetUniformLocation(foregroundProgram, "uScale")
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
        // A new surface has nothing on it: the first frame must clear, not
        // "redraw the last picture" of a surface that no longer exists.
        paintedFrame = false

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

        // The *drawing* follows the surface at once; the *grid* waits until the
        // surface stops moving.
        //
        // A keyboard rising is sixty surface changes in three hundred
        // milliseconds, and each one re-wraps every row of the engine's grid.
        // Doing that sixty times is what made the terminal stutter and flash
        // while the keyboard animated. Doing it once, at the end, is
        // imperceptible.
        //
        // The picture is correct in between: the step comes from the surface
        // and the row count from the grid, so the cells are stretched to the new
        // bounds rather than drawn at the old ones — which is what a terminal
        // does while a program has not redrawn yet.
        settleAtNanos = System.nanoTime() + RESIZE_SETTLE_NANOS
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
     * A live pinch: draw the glyphs at [scale] inside the cells they occupy.
     *
     * **Nothing is rasterised during the gesture.** An earlier version called
     * `atlas.beginSize` on every frame, which opened a new atlas generation
     * sixty times a second: every glyph on screen was re-rasterised from
     * scratch, the atlas was re-uploaded whole, and the masks landed on
     * different shelves — the text thickened and shook while the fingers moved.
     *
     * So the glyphs already in the atlas are drawn bigger or smaller by the
     * shader, which is what the vendored terminal's `canvas.scale` did and what
     * Rin does. The real size is applied once, in `commitZoom`.
     *
     * The grid does not move either: the cell keeps its size and its place, and
     * the letters grow inside it. That is what "make the text bigger" means —
     * the columns are the terminal's, not the font's.
     */
    fun setPinch(scale: Float, focusX: Float, focusY: Float) {
        size = size.copy(
            pinchScale = if (scale.isFinite() && scale > 0f) scale else 1f,
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
        updateSize()
    }

    /**
     * The one place the grid and the session learn their shape.
     *
     * Termux's `updateSize()`, with the same rule: `Term::resize` is called
     * from here and nowhere else. That is what keeps the cells and the buffer
     * they are copied from in step — every screen bug of the past weeks was the
     * two disagreeing for a frame, because four different places could write
     * one without the other.
     *
     * A pinch does **not** come through here. It changes the size the glyphs
     * are rasterised at, not the shape of the grid; Rin's model, and the reason
     * its zoom is continuous.
     */
    private fun updateSize() {
        if (size.width <= 0 || size.height <= 0) {
            return
        }

        val columns = size.columns
        val rows = size.rows
        if (columns == appliedColumns && rows == appliedRows) {
            return
        }

        appliedColumns = columns
        appliedRows = rows
        // One extra record row: the snapshot's first line is the line *above*
        // the viewport, and the sub-line shift reveals part of it. It is drawn
        // at grid row -1 by the build; see `CellRecords.build`.
        records.resize(columns, rows + 1)

        // The session is brought to the grid here, on the GL thread, in the
        // same breath. `EngineSession` is built for it: the grid lives behind
        // the engine's own mutex and its size fields are volatile, so a resize
        // from this thread cannot tear the parser's view of the grid.
        session.resize(columns, rows, metrics.width.toInt(), metrics.height.toInt())
    }

    // --- frame -------------------------------------------------------------

    override fun onDrawFrame(gl: GL10?) {
        val startedNanos = System.nanoTime()

        // The surface is painted before anything is decided.
        //
        // Termux's `onDraw` starts with `canvas.drawColor(...)` and only then
        // looks at whether there is an emulator; a `GLSurfaceView` keeps
        // whatever was in its buffer when a frame is not drawn, which after a
        // resize is nothing — the black rectangle a keyboard opening produced.
        // Painting first means the worst case is the terminal's own background
        // colour for one frame, never black.
        // The clear happens only when nothing has been painted yet: a fresh
        // surface must not show garbage. After that, a frame that cannot build
        // redraws the last picture instead — see `redrawLast`.
        if (!paintedFrame) {
            clearToBackground()
        }

        // A surface that has stopped moving gets its grid now. Every frame
        // checks, because the frame loop runs whether or not anything changed;
        // the check is a field read when nothing is pending.
        if (settleAtNanos != 0L && System.nanoTime() >= settleAtNanos) {
            settleAtNanos = 0L
            planGrid()
        }

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
                // The engine refused the size, so the cells this frame would
                // read are not the ones it is sized for. The surface has
                // already been painted; another frame is asked for rather than
                // waiting for output that may never come.
                return
            }
        }

        val damage = session.pump()
        if (damage < 0) {
            redrawLast()
            return
        }
        val pumpEndNanos = System.nanoTime()

        // The sub-line shift travels with the cells: one lock hold, one
        // position. A positive remainder moves the grid down — the user
        // dragging back into the history — and the snapshot's extra first line
        // fills the strip that reveals at the top.
        size = size.copy(scrollOffsetPx = session.subLinePixels.toFloat())

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
            // alternate screen. The last picture is drawn again rather than
            // leaving the background this frame cleared: a `GLSurfaceView`
            // swaps buffers whether or not anything was drawn, and a frame
            // that draws only the clear colour is the flash — the screen
            // wiping and coming back — that an Enter or a scroll produced.
            redrawLast()
            return
        }
        val buildEndNanos = System.nanoTime()

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
            background = backgroundOverride ?: Palette.background,
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
        val background = backgroundOverride ?: Palette.background
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
        GLES20.glUniform1f(foregroundScale, size.pinchScale)
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
        GLES30.glEnableVertexAttribArray(ATTRIB_SPAN)
        GLES30.glVertexAttribIPointer(ATTRIB_SPAN, 1, GLES30.GL_UNSIGNED_SHORT, stride, 22)

        for (attribute in ATTRIB_ATLAS_RECT..ATTRIB_SPAN) {
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
        // Linear, not nearest: a mask drawn at another size — a pinch, a
        // fractional cell step — is interpolated rather than pixel-doubled.
        // Nearest doubled every stroke, which is the text that grew bolder as
        // it grew bigger.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
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

        /**
         * How long the surface must be still before the grid reflows.
         *
         * Long enough to cover two frames of a slide, short enough that a
         * settled surface is laid out before the user can see it.
         */
        const val RESIZE_SETTLE_NANOS = 48_000_000L

        const val ATTRIB_BACKGROUND_POS = 0
        const val ATTRIB_BACKGROUND_COLOR = 1
        const val ATTRIB_DUMMY_BACKGROUND = 2

        const val ATTRIB_ATLAS_RECT = 0
        const val ATTRIB_GLYPH_SIZE = 1
        const val ATTRIB_OFFSET = 2
        const val ATTRIB_GRID_POS = 3
        const val ATTRIB_COLOR = 4
        const val ATTRIB_FLAGS = 5
        const val ATTRIB_SPAN = 6
        const val ATTRIB_DUMMY_FOREGROUND = 7

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
                // The grid row is signed: the snapshot's extra line above the
                // viewport is stored as row -1 (0xFFFF in the u16 record), and
                // the sub-line shift reveals part of it at the top.
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
            // The number of cells this glyph occupies: 1, 2 for a wide
            // character, more for a ligature.
            layout(location = 6) in uint aSpan;
            uniform vec4 uProjection;
            uniform vec2 uCellSize;
            uniform float uScale;
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
                int gridRow = int(aGridPos.y);
                if (gridRow > 32767) {
                    gridRow -= 65536;
                }
                vec2 cell = vec2(float(aGridPos.x), float(gridRow)) * uCellSize;
                vec2 natural = vec2(aGlyphSize);

                // The glyph grows about its own baseline origin, not about the
                // cell's corner.
                //
                // Scaling the in-cell offset as well moved the glyph *and* its
                // position, so the letters drifted apart as they grew — the
                // gaps widened with the font. The offset is where the glyph
                // sits inside its cell, and that does not change when the font
                // does: the pen position is the cell's.
                vec2 offset = vec2(aOffset);

                // A glyph wider than the cell it owns is fitted to it.
                //
                // CJK, an emoji and some symbols are drawn at two or three
                // times the cell width by the font; drawn at their natural size
                // they overlap their neighbours. The cell's width is the
                // terminal's — a column is a column — so the glyph is squeezed
                // into it, which is what Termux does when it measures a
                // mismatch (`fontWidthMismatch`).
                //
                // A wide character owns two columns, so its target is twice the
                // cell. Everything else targets one.
                float targetWidth = uCellSize.x * float(max(aSpan, 1u));
                float squeeze = (natural.x > 0.0) ? min(1.0, targetWidth / natural.x) : 1.0;

                // A pinch changes the *text* size, so the glyph grows by the
                // scale and the cell does not. The glyph's centre stays where
                // the cell puts it: growing from the top-left corner would push
                // every letter down and right as it got bigger, which reads as
                // the spacing changing rather than the type growing.
                vec2 naturalSize = vec2(natural.x * squeeze, natural.y);
                vec2 size = naturalSize * uScale;
                vec2 grow = (size - naturalSize) * 0.5;
                gl_Position = vec4(
                    uProjection.xy + uProjection.zw * (cell + offset - grow + corner * size),
                    0.0,
                    1.0
                );
                // The UV walks the *atlas* texels, which do not scale: a solid
                // quad samples one texel, a glyph samples its own rectangle.
                //
                // Inset by half a texel: `GL_LINEAR` blends the four texels
                // around every sample, and at a rectangle's edge those include
                // the *neighbouring* glyph's. The blend is faint but it is a
                // line — the faint outline at the letter edges. Half a texel in
                // keeps every sample inside the glyph's own texels.
                vTexCoord = (aFlags & 1u) != 0u
                    ? (vec2(aAtlasRect) + 0.5) / uAtlasSize
                    : (vec2(aAtlasRect) + 0.5 + corner * max(natural - 1.0, vec2(0.0))) / uAtlasSize;
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
