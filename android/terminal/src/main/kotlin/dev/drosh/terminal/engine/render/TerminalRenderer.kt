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

    /**
     * A grid is being held back while the surface moves; ask for a settle.
     *
     * The surface is drawn on demand (`RENDERMODE_WHEN_DIRTY`), so when an
     * animation ends there is no next frame to notice the surface stopped. The
     * host schedules one instead — see `TerminalView`.
     */
    var onGridHeld: (() -> Unit)? = null

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
     * Read from the session at the top of `onDrawFrame`, in the same lock hold
     * as the cells it describes — which is the whole reason this is safe. When
     * the two were read separately a frame could draw one position's cells with
     * the next position's offset, and that one-frame shift was the shake a drag
     * showed at every row boundary.
     */
    private var scrollOffsetPx = 0f

    /**
     * The cell *step* the grid is drawn with, in pixels.
     *
     * [metrics] is the cell the font measured — the size a glyph is rasterised
     * at — and this is how far apart the cells are placed. The two differ by
     * less than a pixel: a surface is not a whole number of cells wide, and
     * `floor(width / cellWidth)` columns leave the remainder as a dead strip on
     * the right (and the same at the bottom). Spreading that remainder across
     * the columns instead makes the grid fill the surface exactly, which is
     * what every terminal does and why none of them has a gap at the edge.
     *
     * The stretch is always < 1 cell / columns, so a glyph is never visibly
     * distorted; what it removes is a strip of background that belonged to no
     * cell.
     */
    private var cellStepX = 0f
    private var cellStepY = 0f

    /**
     * A live pinch: the scale the glyphs are drawn at, over a grid that does
     * not change.
     *
     * This is the vendored terminal's `canvas.scale` and Termux's model in
     * fact, not in spirit: while two fingers move, the terminal is *drawn*
     * bigger or smaller and nothing else happens. No measurement, no
     * rasterisation, no `Term::resize`, no reflow — one uniform and one draw
     * call per frame, which is what makes a pinch continuous.
     *
     * The alternative — resizing the font every frame — was tried and is what
     * made the zoom step. A pinch reports a size every frame, and every size
     * change is a reflow of every row in the engine: at sixty frames that is
     * sixty reflows of a screen whose text is being rewrapped while the user
     * watches. Even with the size quantised to a tenth of a sp the grid jumps
     * from one shape to the next, because a shape is a whole number of cells.
     *
     * The real size is applied once, when the fingers lift — see
     * [commitZoom]. One reflow, at a moment when nobody is looking at a moving
     * picture.
     */
    private var pinchScale = 1f

    /** Where the pinch is anchored, in surface pixels. */
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f


    /**
     * Draw the picture at a live pinch's scale, about the fingers.
     *
     * Called on every frame of a pinch. Nothing else changes: the grid, the
     * glyph masks and the atlas all stay exactly as they are.
     */
    fun setPinch(scale: Float, focusX: Float, focusY: Float) {
        pinchScale = if (scale.isFinite() && scale > 0f) scale else 1f
        pinchFocusX = focusX
        pinchFocusY = focusY
    }

    /**
     * End a pinch: apply the size the fingers reached, once.
     *
     * This is the only frame of a gesture that measures, rasterises and
     * reflows, and it happens after the fingers have stopped, so nothing is
     * waiting on it.
     */
    fun commitZoom(size: FontSize) {
        pinchScale = 1f
        setFontSize(size)
    }

    /**
     * The projection for one pass: clip-space origin and scale, with the pinch
     * applied about its focus and the sub-line scroll folded in.
     *
     * The anchor is converted here rather than in the shader so the two passes
     * cannot disagree about it.
     */
    private fun projection(out: FloatArray) {
        val scale = pinchScale
        // A point p maps to focus + (p - focus) * scale, in pixels. In clip
        // space that is `clip * s + anchor * (1 - s)`.
        val anchorX = -1f + 2f * pinchFocusX / width.coerceAtLeast(1)
        val anchorY = 1f - 2f * pinchFocusY / height.coerceAtLeast(1)
        out[0] = -scale + anchorX * (1f - scale)
        out[1] = (1f - scrollOffsetPx * 2f / height.coerceAtLeast(1)) * scale +
            anchorY * (1f - scale)
        out[2] = 2f * scale / width.coerceAtLeast(1)
        out[3] = -2f * scale / height.coerceAtLeast(1)
    }

    /** Scratch for [projection]; a frame builds it twice, and neither allocates. */
    private val projectionScratch = FloatArray(4)

    /**
     * The grid a moving surface is heading for, and when it last moved.
     *
     * A sidebar sliding in, a keyboard rising, a split being dragged: the
     * surface changes size on every animation frame, and each change is a
     * `Term::resize` — which re-wraps every line of the grid. Eighteen reflows
     * over three hundred milliseconds is text that rewraps and un-rewraps while
     * the user watches, and that is the jitter. The grid is held at its current
     * shape until the surface has stopped moving, then applied once.
     */
    private var pendingGrid: Pair<Int, Int>? = null
    private var lastResizeNanos = 0L

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

        // The cell step follows the surface immediately; the *grid* does not.
        //
        // A keyboard rising is sixty surface changes in three hundred
        // milliseconds, and every one of them would be a `Term::resize` — every
        // row re-wrapped, eighteen times, while the user watches. That is the
        // flicker. So the step is recomputed now (the picture fills the new
        // bounds at once, no black strip) and the reflow waits for the surface
        // to stop, which `settleGrid` applies.
        //
        // The surface and the grid therefore disagree for the length of the
        // animation, and that is deliberate: `onDrawFrame` draws at the
        // session's shape and lets the step stretch it, which is exactly what
        // a terminal does when it is resized and the program has not redrawn
        // yet.
        updateGrid(holdWhileMoving = true)
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
        // The atlas and the per-cell cache key themselves by the size a mask
        // was *rasterised* at — the pixel size rounded — so a pinch that moves
        // a tenth of a pixel asks for masks that are already there. Nothing is
        // thrown away here on purpose: clearing the cache on every frame of a
        // pinch is what made the text shake.
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
        //
        // Not held: a pinch is the size changing, so the reflow is the thing
        // the user is looking at.
        updateGrid(holdWhileMoving = false)
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
     *
     * [holdWhileMoving] is false for a font change and true for a surface
     * change, and the difference is the whole point. A pinch *is* the size
     * changing, so its reflow must happen on the frame that asked for it — that
     * is what makes the terminal grow. A sidebar sliding or a keyboard rising
     * is the *surface* changing while the size the user chose stays the same,
     * and reflowing on every frame of that animation rewraps the text eighteen
     * times in three hundred milliseconds. That is the jitter.
     */
    private fun updateGrid(holdWhileMoving: Boolean = true) {
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
        if (nextColumns == columns && nextRows == rows) {
            // Same shape: only the step changes, which is free.
            cellStepX = width.toFloat() / columns
            cellStepY = height.toFloat() / rows
            pendingGrid = null
            return
        }

        // The shape changed. If it changed again a moment ago the surface is
        // still moving — a sidebar sliding, a keyboard rising — and reflowing
        // the grid on every frame of that animation is the jitter: each frame
        // rewraps the text and the next one rewraps it back.
        //
        // So the target is recorded and the grid is left alone until the
        // surface has been still for a frame or two. The step still follows the
        // surface, so the picture fills the new bounds immediately; only the
        // reflow waits.
        val now = System.nanoTime()
        val moving = holdWhileMoving &&
            lastResizeNanos != 0L && now - lastResizeNanos < RESIZE_SETTLE_NANOS
        lastResizeNanos = now

        if (moving) {
            // The step is computed from the grid that is *drawn*, not from the
            // grid that is coming. Mixing them — new step over the old cell
            // count — is a picture that stops short of the surface on one axis
            // and overruns the other, which is the strip of nothing at the
            // edge that made a keyboard opening look broken.
            cellStepX = width.toFloat() / columns.coerceAtLeast(MIN_COLUMNS)
            cellStepY = height.toFloat() / rows.coerceAtLeast(MIN_ROWS)
            pendingGrid = nextColumns to nextRows
            // Nothing else will ask for a frame once the animation stops, so
            // the host is asked to schedule the settle.
            onGridHeld?.invoke()
            return
        }

        cellStepX = width.toFloat() / nextColumns
        cellStepY = height.toFloat() / nextRows

        pendingGrid = null
        applyGrid(nextColumns, nextRows)
    }

    /**
     * Lay the grid out and tell the session.
     *
     * Separate from [updateGrid] because a moving surface records its target
     * and applies it later, once the animation has stopped.
     */
    private fun applyGrid(nextColumns: Int, nextRows: Int) {
        columns = nextColumns
        rows = nextRows
        records.resize(columns, rows)
        onGridChanged?.invoke(columns, rows, fontSize)
    }

    /**
     * Apply a grid that was held back while the surface moved.
     *
     * Called once the surface has been still long enough to be the size it is
     * going to stay at.
     */
    fun settleGrid() {
        val (nextColumns, nextRows) = pendingGrid ?: return
        // Still moving: the animation has not finished, and applying the grid
        // now is the reflow that would be undone by the next frame.
        if (lastResizeNanos != 0L &&
            System.nanoTime() - lastResizeNanos < RESIZE_SETTLE_NANOS
        ) {
            return
        }
        pendingGrid = null
        if (nextColumns == columns && nextRows == rows) {
            // The shape came back to what is already drawn: only the step has
            // to catch up with the surface, and the frame that follows does it.
            cellStepX = width.toFloat() / columns.coerceAtLeast(MIN_COLUMNS)
            cellStepY = height.toFloat() / rows.coerceAtLeast(MIN_ROWS)
            return
        }
        applyGrid(nextColumns, nextRows)
    }

    override fun onDrawFrame(gl: GL10?) {
        val startedNanos = System.nanoTime()

        // A surface that has stopped moving gets its grid now. Every frame
        // checks, because the frame loop runs whether or not anything changed;
        // the check is a field read when nothing is pending.
        settleGrid()

        val cells = session.cells
        if (cells == null || backgroundProgram == 0 || foregroundProgram == 0) {
            return
        }
        if (session.columns != columns || session.rows != rows) {
            // The grid the renderer laid out and the session's are not the same
            // one. That is a frame the build cannot read — the cell buffer is
            // sized for the session — so the session is brought to the grid
            // here rather than waited for.
            //
            // Waiting was the earlier behaviour, and it is what a pinch showed:
            // the size reported a new grid, the session kept the old one, every
            // frame was refused, and the picture on screen stayed at whatever
            // was there before the fingers landed.
            session.resize(columns, rows, metrics.width.toInt(), metrics.height.toInt())
            if (session.columns != columns || session.rows != rows) {
                // The engine refused the size, so the cells this frame would
                // read are not the ones it is sized for. The surface is still
                // cleared, because a stale picture over a resized surface is a
                // smear rather than a terminal — and a request is made so the
                // frame is retried rather than waiting for output that may
                // never come.
                val fallback = ambientTint ?: Palette.background
                GLES20.glClearColor(
                    channel(fallback, 16),
                    channel(fallback, 8),
                    channel(fallback, 0),
                    1f,
                )
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                onGridHeld?.invoke()
                return
            }
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

        // The sub-line part of the scroll, from the same snapshot as the cells
        // above: one lock hold, one position.
        scrollOffsetPx = session.stateArray[NativeCore.STATE_SUB_LINE_PX].toFloat()

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
            // alternate screen. The session is resized at the top of the next
            // frame and the picture is drawn then.
            return
        }
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
        // A buffer with nothing in it is not uploaded at all: `glBufferData`
        // takes the size from the caller and the bytes from the buffer, and a
        // cleared buffer has no bytes — the `remaining() < size` exception.
        // The build already refuses a frame it could not fill, so this is the
        // second lock on the same door.
        if (records.backgroundBytes.limit() <= 0 || records.foregroundBytes.limit() <= 0) {
            return
        }

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
        projection(projectionScratch)
        GLES20.glUniform4f(
            backgroundProjection,
            projectionScratch[0],
            projectionScratch[1],
            projectionScratch[2],
            projectionScratch[3],
        )
        GLES20.glUniform2f(backgroundCellSize, cellStepX, cellStepY)
        GLES30.glBindVertexArray(backgroundVao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, QUAD_VERTICES, instances)
        GLES30.glBindVertexArray(0)

        // What the pass was actually given: the instance count, the first
        // record as the GPU will read it, and the error GL reported. A block
        // that is in the records but not on the screen is one of these three.
        if (frameCount < 200) {
            val first = records.backgroundBytes.getInt(0)
            val error = GLES20.glGetError()
            backgroundProbe = "bgdraw " + instances +
                " first=#" + Integer.toHexString(first) +
                " err=" + error
        }
    }

    private fun drawForegrounds() {
        val instances = records.foregroundCount
        if (instances == 0) {
            return
        }

        GLES20.glUseProgram(foregroundProgram)
        projection(projectionScratch)
        GLES20.glUniform4f(
            foregroundProjection,
            projectionScratch[0],
            projectionScratch[1],
            projectionScratch[2],
            projectionScratch[3],
        )
        GLES20.glUniform2f(foregroundCellSize, cellStepX, cellStepY)
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

        /**
         * How long the surface must be still before the grid reflows.
         *
         * Long enough to cover two frames of a slide (a sidebar, a keyboard),
         * short enough that a settled surface is laid out before the user can
         * see it. The animation itself is sixty frames; this is the difference
         * between one reflow and eighteen.
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
        const val ATTRIB_DUMMY_FOREGROUND = 6

        val BACKGROUND_VERTEX = """
            #version 300 es
            // The cell's place in the grid, and its colour.
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
                gl_Position = vec4(uProjection.xy + uProjection.zw * (cell + corner * uCellSize), 0.0, 1.0);
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
