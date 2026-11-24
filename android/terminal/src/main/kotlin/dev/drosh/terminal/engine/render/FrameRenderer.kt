package dev.drosh.terminal.engine.render

import android.opengl.GLES20
import android.opengl.GLES30
import dev.drosh.terminal.engine.font.ColorAtlas
import dev.drosh.terminal.engine.font.Emoji
import dev.drosh.terminal.engine.font.LigaturePolicy
import dev.drosh.terminal.engine.font.TextAtlas
import dev.drosh.terminal.engine.gl.GlBuffer
import dev.drosh.terminal.engine.gl.GlProgram
import dev.drosh.terminal.engine.gl.GlTexture
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

    private val backgroundProgram =
        GlProgram(Shaders.BACKGROUND_VERTEX, Shaders.BACKGROUND_FRAGMENT)
    private val foregroundProgram =
        GlProgram(Shaders.FOREGROUND_VERTEX, Shaders.FOREGROUND_FRAGMENT)
    private val cursorProgram =
        GlProgram(Shaders.CURSOR_VERTEX, Shaders.CURSOR_FRAGMENT)
    private val imageProgram =
        GlProgram(Shaders.IMAGE_VERTEX, Shaders.IMAGE_FRAGMENT)

    private var backgroundProjection = 0
    private var backgroundCellSize = 0
    private var foregroundProjection = 0
    private var foregroundCellSize = 0
    private var foregroundAtlasSize = 0
    private var foregroundAtlas = 0

    /** The factor the atlas's masks are rasterised at. */
    private var foregroundAtlasScale = 0
    /** How much of its colour a blinking glyph keeps this frame. */
    private var foregroundBlinkAlpha = 0

    /** The text atlas's texture: one byte a texel, [TextAtlas.size] square. */
    private val atlasTexture = GlTexture()
    private val backgroundBuffer = GlBuffer()
    private val foregroundBuffer = GlBuffer()
    private val imageBuffer = GlBuffer()

    private var cursorProjection = 0
    private var cursorCellSize = 0
    private var cursorRect = 0
    private var cursorColorUniform = 0

    private var cursorHollow = 0
    private var cursorEdge = 0

    /** The glyph masks. Owned by the loop thread. */
    private val atlas = TextAtlas()

    /**
     * The emoji.
     *
     * A second atlas, because an emoji is not a mask: the platform draws it in
     * its own colours, and the text atlas holds one byte per texel. Ghostty
     * splits the same way — a grayscale atlas and a colour one — and the two
     * are sampled by different passes.
     */
    private val colorAtlas = ColorAtlas()

    /** The colour atlas's texture: four bytes a texel, [ColorAtlas.size] square. */
    private val colorAtlasTexture = GlTexture()

    /** The emoji of this frame: a square per cell, drawn over the text. */
    private val colorGlyphs = ArrayList<ImageDraw>()

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

    /**
     * The cells of the last frame: the backgrounds and the sprites.
     *
     * The storage is per row, so a row that did not change is a run of records
     * nobody touches — neither built nor uploaded.
     */
    private val cells = Cells()

    /** The global background, as Ghostty's first full-screen pass. */
    private val screenBackground = ScreenBackgroundPass()

    /** How many bytes the background buffer on the GPU holds. */
    private var glBackgroundBytes = 0

    /** How many bytes the sprite buffer on the GPU holds. */
    private var glSpriteBytes = 0

    /**
     * Create the GL resources. Called once, on the loop thread, after the
     * context is current.
     */
    fun create() {
        val backgroundOk = backgroundProgram.create()
        val foregroundOk = foregroundProgram.create()
        val cursorOk = cursorProgram.create()
        val imageOk = imageProgram.create()
        screenBackground.create()
        if (!backgroundOk || !foregroundOk || !cursorOk || !imageOk) {
            failure = shaderFailure() ?: "the shaders did not compile"
            return
        }
        imageProjection = imageProgram.uniform("uProjection")
        imageTexture = imageProgram.uniform("uTexture")
        cursorProjection = cursorProgram.uniform("uProjection")
        cursorCellSize = cursorProgram.uniform("uCellSize")
        cursorRect = cursorProgram.uniform("uRect")
        cursorColorUniform = cursorProgram.uniform("uColor")
        cursorHollow = cursorProgram.uniform("uHollow")
        cursorEdge = cursorProgram.uniform("uEdge")

        backgroundProjection = backgroundProgram.uniform("uProjection")
        backgroundCellSize = backgroundProgram.uniform("uCellSize")
        foregroundProjection = foregroundProgram.uniform("uProjection")
        foregroundCellSize = foregroundProgram.uniform("uCellSize")
        foregroundAtlasSize = foregroundProgram.uniform("uAtlasSize")
        foregroundAtlas = foregroundProgram.uniform("uAtlas")
        foregroundAtlasScale = foregroundProgram.uniform("uAtlasScale")
        foregroundBlinkAlpha = foregroundProgram.uniform("uBlinkAlpha")

        atlasTexture.create(atlas.size, atlas.size, GLES30.GL_R8)
        colorAtlasTexture.create(colorAtlas.size, colorAtlas.size, GLES20.GL_RGBA)
        backgroundBuffer.create()
        foregroundBuffer.create()
        imageBuffer.create()

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        created = true
    }

    /**
     * The newest shader failure, for the screen.
     *
     * The four programs are built in order, so the last one that failed is the
     * failure worth showing — the older ones are usually the same cause.
     */
    private fun shaderFailure(): String? =
        listOf(
            backgroundProgram.error,
            foregroundProgram.error,
            cursorProgram.error,
            imageProgram.error,
            screenBackground.failure,
        ).lastOrNull { it != null }

    /** Release every GL object. */
    fun release() {
        backgroundProgram.release()
        foregroundProgram.release()
        cursorProgram.release()
        imageProgram.release()
        screenBackground.release()
        atlasTexture.release()
        colorAtlasTexture.release()
        backgroundBuffer.release()
        foregroundBuffer.release()
        imageBuffer.release()
        for (image in imageTextures.values) {
            image.texture.release()
        }
        imageTextures.clear()
        images.clear()
        created = false
    }

    /**
     * The face the glyphs are drawn in.
     *
     * The host measures its cell with the same typeface, so the two agree
     * about the advance: a cell measured with one face and a mask drawn with
     * another is text that does not fit its own cell.
     */
    fun setTypeface(typeface: android.graphics.Typeface) {
        atlas.setTypeface(typeface)
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

        // The blend is the whole frame's state, not one pass's.
        //
        // A glyph is a mask multiplied by the cell's colour: without alpha
        // blending its quad is a solid rectangle in that colour, which is what
        // every character on the screen turned into the moment a pass that
        // disabled the blend ran before the text was drawn again.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    /**
     * What one frame cost and what it was made of.
     *
     * The phases are separate because a number nobody can attribute is a
     * number nobody can improve: a slow frame is either the snapshot (the
     * terminal lock), the vertex building (the cells walked), the upload (the
     * bytes) or the draw (the calls), and each of those has a different fix.
     */
    data class DrawResult(
        /** The cells the frame carried. */
        val cells: Int,
        /** The rows the renderer rebuilt this frame. */
        val dirtyRows: Int,
        /** The foreground instances drawn. */
        val sprites: Int,
        /** The pictures drawn. */
        val images: Int,
        /** The GL draw calls issued. */
        val drawCalls: Int,
        /** The atlas regions uploaded. */
        val atlasUploads: Int,
        /** The background vertex building, in nanoseconds. */
        val backgroundBuildNanos: Long,
        /** The foreground vertex building, in nanoseconds. */
        val foregroundBuildNanos: Long,
        /** The vertex building, in nanoseconds. */
        val buildNanos: Long,
        /** The uploads, in nanoseconds. */
        val uploadNanos: Long,
        /** The draw calls and the swap, in nanoseconds. */
        val drawNanos: Long,
    ) {
        companion object {
            /** A frame that drew nothing: the previous picture stays up. */
            val EMPTY = DrawResult(
                cells = 0,
                dirtyRows = 0,
                sprites = 0,
                images = 0,
                drawCalls = 0,
                atlasUploads = 0,
                backgroundBuildNanos = 0L,
                foregroundBuildNanos = 0L,
                buildNanos = 0L,
                uploadNanos = 0L,
                drawNanos = 0L,
            )
        }
    }

    /**
     * Draw one frame.
     *
     * Returns what the frame cost and what it was made of, which the loop
     * measures and the diagnostics report. A frame with no cells drew nothing:
     * the previous picture should stay on screen.
     */
    fun draw(frame: Frame?, size: SizeInfo): DrawResult {
        if (!created || frame == null || !frame.valid) {
            return DrawResult.EMPTY
        }

        cellWidth = size.stepX
        cellHeight = size.stepY

        val buildStart = System.nanoTime()

        // The clear is the terminal's own background, and it happens only when
        // a frame is actually drawn: a frame that draws nothing leaves the
        // previous picture alone, which is what stops the screen from wiping to
        // the clear colour and coming back.
        GLES20.glViewport(0, 0, size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
        val backgroundArgb = packBackground(
            backgroundR,
            backgroundG,
            backgroundB,
            if (transparentBackground) 0f else 1f,
        )
        if (screenBackground.available) {
            screenBackground.draw(backgroundArgb)
        } else {
            GLES20.glClearColor(backgroundR, backgroundG, backgroundB, if (transparentBackground) 0f else 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }

        // Decided once, before either pass reads it: the block cursor inverts
        // exactly one cell, and both the background and the glyph of that cell
        // have to know which one.
        //
        // The cell the cursor was on in the last frame matters too: it was
        // drawn inverted, so it has to be drawn again as it was.
        val previousInverted = invertedCell
        invertedCell = blockCursorCell(frame)

        // Which rows have to be drawn again.
        val dirty = dirtyRows(frame, previousInverted)

        // Whether any row changed its *content*, as opposed to only the
        // cursor's inversion. The cursor marks its rows dirty on every blink,
        // and the emoji are derived from the cells alone: rebuilding them on a
        // cursor-only frame is a walk of the whole grid for a picture that
        // cannot have changed.
        val contentDirty = rowsContentDirty

        // The cells are built here, once, and drawn by the passes below.
        // Building them in the passes would work too, but a buffer that nobody
        // fills is a pass that draws whatever was left in it — which is how the
        // coloured bars of a full-screen program went missing while the text
        // kept rendering.
        cells.resize(frame)

        val backgroundBuildStart = System.nanoTime()
        backgroundsRebuilt = dirty.any { it } || !frame.clean || forceFullFrame
        if (backgroundsRebuilt) buildBackgrounds(frame, dirty)
        val backgroundBuildEnd = System.nanoTime()

        val foregroundBuildStart = backgroundBuildEnd
        val foregroundCount = buildForegrounds(frame, dirty)
        val foregroundBuildEnd = System.nanoTime()

        buildColorGlyphs(frame, contentDirty)

        val buildEnd = System.nanoTime()

        // What the renderer is made of, for the diagnostics: the atlas's fill
        // is the number the atlas work is measured against.
        dev.drosh.terminal.engine.bench.RenderBench.setFacts(
            atlasFillPercent = atlas.usedPercent,
            fontPx = rasterPx,
            cellWidth = cellWidth,
            cellHeight = cellHeight,
        )

        // The uploads: the atlas first (a new glyph has to be on the GPU before
        // the pass that samples it), then the two vertex buffers.
        var atlasUploads = 0
        if (atlas.dirty) {
            if (uploadAtlas()) atlasUploads += 1
        }
        if (backgroundsRebuilt) uploadBackgrounds(frame, dirty)
        uploadForegrounds()
        if (colorAtlas.dirty) {
            uploadColorAtlas()
        }

        val uploadEnd = System.nanoTime()

        // The pictures, in Ghostty's order: some under the cell backgrounds,
        // some between the backgrounds and the text, some over the text.
        var drawCalls = 0
        drawCalls += drawImages(size, LAYER_BELOW_BACKGROUND)
        drawCalls += drawBackgrounds(frame, size)
        drawCalls += drawImages(size, LAYER_BELOW_TEXT)
        drawCalls += drawForegrounds(frame, size, foregroundCount)
        drawCalls += drawImages(size, LAYER_ABOVE_TEXT)
        drawCalls += drawColorGlyphs(size)
        drawCalls += drawCursor(frame, size)
        drawCalls += drawBellFlash(frame, size)

        val drawEnd = System.nanoTime()

        // The array is reused and may be wider than this frame's grid; only
        // the rows the frame has are this frame's dirty rows.
        var dirtyRowCount = 0
        for (row in 0 until frame.rowCount) {
            if (dirty[row]) dirtyRowCount += 1
        }

        return DrawResult(
            cells = frame.cellCount,
            dirtyRows = dirtyRowCount,
            sprites = foregroundCount,
            images = images.size,
            drawCalls = drawCalls,
            atlasUploads = atlasUploads,
            backgroundBuildNanos = backgroundBuildEnd - backgroundBuildStart,
            foregroundBuildNanos = foregroundBuildEnd - foregroundBuildStart,
            buildNanos = buildEnd - buildStart,
            uploadNanos = uploadEnd - buildEnd,
            drawNanos = drawEnd - uploadEnd,
        )
    }

    /** A float colour as the packed ARGB the background pass takes. */
    private fun packBackground(r: Float, g: Float, b: Float, a: Float): Int {
        val red = (r.coerceIn(0f, 1f) * 255f).toInt()
        val green = (g.coerceIn(0f, 1f) * 255f).toInt()
        val blue = (b.coerceIn(0f, 1f) * 255f).toInt()
        val alpha = (a.coerceIn(0f, 1f) * 255f).toInt()
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    // --- geometry ----------------------------------------------------------

    /**
     * Build the backgrounds of the rows that changed.
     *
     * One record per cell: `u16 column, i16 row, vec4 colour, vec4 underline,
     * vec4 strikethrough, vec4 overline`. The row is signed because the
     * frame's overscan rows sit above the viewport; the shader reinterprets
     * the top half of the unsigned range.
     *
     * The rows are the frame's captured rows: the first one is the overscan
     * row above the viewport, and the record of a cell is its row times the
     * columns plus its column. Nothing here translates a viewport row into a
     * captured one, because that translation is where a background ends up
     * drawn one row from the cells it belongs to.
     *
     * ## Why a row is written twice
     *
     * The buffer the GPU reads is a *direct* buffer, and every relative `put`
     * on one is a virtual call that checks a limit and stores through an
     * address. A cell is twenty bytes, so writing its fields one at a time is
     * eighteen of those calls a cell — on a full screen that is the whole of
     * this function's cost, and it is a cost of the buffer type, not of the
     * data. The row is written into an ordinary byte array first, where a
     * store is a store, and then handed to the direct buffer in one copy per
     * row.
     */
    private fun buildBackgrounds(frame: Frame, dirty: BooleanArray) {
        val columns = frame.columns
        if (columns <= 0) return
        val rowBytes = columns * Cells.BACKGROUND_BYTES
        if (backgroundScratch.size < rowBytes) {
            backgroundScratch = ByteArray(rowBytes)
        }
        val scratch = backgroundScratch
        val backgrounds = cells.backgrounds
        val firstRowY = frame.firstRowY
        val rowCount = frame.rowCount
        for (row in 0 until rowCount) {
            if (!dirty[row]) continue
            val base = row * columns
            val gridRow = row + firstRowY
            val rowLow = gridRow.toByte()
            val rowHigh = (gridRow ushr 8).toByte()
            var at = 0
            for (column in 0 until columns) {
                val index = base + column
                // `u16 column, i16 row`: the two little-endian halves the
                // shader reads as one word.
                scratch[at] = column.toByte()
                scratch[at + 1] = (column ushr 8).toByte()
                scratch[at + 2] = rowLow
                scratch[at + 3] = rowHigh

                // One flags read, not one per highlight question: the cell's
                // two highlight bits live in the same byte, and a search that
                // matches nothing must not pay for two lookups a cell.
                val flags = frame.flags(index)
                putColourBytes(scratch, at + 4, backgroundColour(frame, index, flags))

                val attributes = frame.attributes(index)
                val underlineStyle = (attributes and FrameFormat.ATTR_UNDERLINE_MASK) shr
                    FrameFormat.ATTR_UNDERLINE_SHIFT
                // Most cells carry no rule. The foreground is only read when
                // one of the three rules needs it, which is what keeps the
                // common cell to three frame reads instead of four.
                val hasRule = underlineStyle != 0 ||
                    attributes and (FrameFormat.ATTR_STRIKETHROUGH or FrameFormat.ATTR_OVERLINE) != 0
                if (hasRule) {
                    val foreground = foregroundOf(frame, index)
                    val underline = if (underlineStyle == 0) {
                        Cells.TRANSPARENT
                    } else {
                        (foreground and 0x00ffffff) or (underlineStyle shl 24)
                    }
                    putColourBytes(scratch, at + 8, underline)
                    putColourBytes(
                        scratch,
                        at + 12,
                        if (attributes and FrameFormat.ATTR_STRIKETHROUGH != 0) {
                            foreground
                        } else {
                            Cells.TRANSPARENT
                        },
                    )
                    putColourBytes(
                        scratch,
                        at + 16,
                        if (attributes and FrameFormat.ATTR_OVERLINE != 0) {
                            foreground
                        } else {
                            Cells.TRANSPARENT
                        },
                    )
                } else {
                    // The three rule records are transparent, which draws
                    // nothing. A run of zeros is the cheapest way to say so.
                    scratch.fill(0.toByte(), at + 8, at + Cells.BACKGROUND_BYTES)
                }
                at += Cells.BACKGROUND_BYTES
            }
            backgrounds.position(base * Cells.BACKGROUND_BYTES)
            backgrounds.put(scratch, 0, rowBytes)
        }
    }

    /**
     * A row's background records, written as bytes before they reach the GPU.
     *
     * Sized to the widest row seen, so a steady terminal never allocates here.
     */
    private var backgroundScratch = ByteArray(0)

    /** Write a packed colour's four bytes into a heap array. */
    private fun putColourBytes(array: ByteArray, at: Int, packed: Int) {
        array[at] = packed.toByte()
        array[at + 1] = (packed ushr 8).toByte()
        array[at + 2] = (packed ushr 16).toByte()
        array[at + 3] = (packed ushr 24).toByte()
    }

    /** The colour a cell's own background is painted with. */
    private fun backgroundColour(frame: Frame, index: Int, flags: Int): Int {
        val background = backgroundOf(frame, index)
        return when {
            flags and FrameFormat.CELL_FLAG_HIGHLIGHT != 0 ->
                mix(background, highlightR, highlightG, highlightB, HIGHLIGHT_TINT)
            flags and FrameFormat.CELL_FLAG_HIGHLIGHT_ALL != 0 ->
                mix(background, highlightAllR, highlightAllG, highlightAllB, HIGHLIGHT_ALL_TINT)
            else -> background
        }
    }

    /** A cell's colour, tinted with a highlight. */
    private fun mix(background: Int, r: Float, g: Float, b: Float, alpha: Float): Int {
        val br = FrameFormat.red(background) / 255f
        val bg = FrameFormat.green(background) / 255f
        val bb = FrameFormat.blue(background) / 255f
        val mixed = (br * (1f - alpha) + r * alpha) * 255f
        val mixedG = (bg * (1f - alpha) + g * alpha) * 255f
        val mixedB = (bb * (1f - alpha) + b * alpha) * 255f
        return (0xff shl 24) or
            (mixed.toInt().coerceIn(0, 255) shl 16) or
            (mixedG.toInt().coerceIn(0, 255) shl 8) or
            mixedB.toInt().coerceIn(0, 255)
    }

    /**
     * The rows that have to be drawn again, as the frame's captured rows.
     *
     * The engine's own dirty state says which of its rows changed, and the
     * frame carries that answer. The app's markings — the search's highlight,
     * the user's selection — are in it too, because the app marks those rows
     * dirty on the engine's side.
     *
     * The cursor is the app's: a block cursor inverts a cell's colours, so the
     * cell it was on and the cell it is on now both have to be drawn again.
     */
    private fun dirtyRows(frame: Frame, previousInverted: Int): BooleanArray {
        if (dirtyRowFlags.size < frame.rowCount) {
            dirtyRowFlags = BooleanArray(frame.rowCount)
        }
        val dirty = dirtyRowFlags
        var contentDirty = false
        if (forceFullFrame) {
            forceFullFrame = false
            for (row in 0 until frame.rowCount) dirty[row] = true
            contentDirty = true
        } else {
            for (row in 0 until frame.rowCount) {
                val changed = frame.isRowDirty(row)
                dirty[row] = changed
                if (changed) contentDirty = true
            }
        }
        rowsContentDirty = contentDirty
        markCursorRow(dirty, frame, previousInverted)
        markCursorRow(dirty, frame, invertedCell)
        return dirty
    }

    /**
     * Whether the engine reported a row whose cells changed.
     *
     * The cursor marks its own rows dirty after this is decided, because a
     * block cursor is drawn by inverting a cell and that is a change to the
     * *picture* rather than to the cell's content. The emoji are derived from
     * the cells alone, so this is the flag that says whether they have to be
     * rebuilt at all.
     */
    private var rowsContentDirty = false

    /** The row a cell is on, marked as needing to be drawn again. */
    private fun markCursorRow(dirty: BooleanArray, frame: Frame, cell: Int) {
        if (cell < 0 || frame.columns <= 0) return
        val row = cell / frame.columns
        if (row in 0 until frame.rowCount) {
            dirty[row] = true
        }
    }

    /** The rows that have to be drawn again, reused from frame to frame. */
    private var dirtyRowFlags = BooleanArray(0)

    /** Force every row to be rebuilt on the next frame. */
    var forceFullFrame = false

    /** Whether backgrounds were rebuilt this frame. */
    private var backgroundsRebuilt = false

    /** The cell the sprites were last built at. */
    private var spriteCellWidth = -1f
    private var spriteCellHeight = -1f

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

    private fun drawCursor(frame: Frame, size: SizeInfo): Int {
        if (cursorProgram.handle == 0) return 0
        if (!frame.cursorVisible) return 0
        if (frame.cursorColumn !in 0 until frame.columns) return 0
        if (frame.cursorRow !in 0 until frame.rows) return 0

        // A blinking cursor is hidden half the time. The engine says that the
        // cursor blinks; whether it is in its visible phase is the clock's
        // business, and the same clock the render loop already wakes on.
        if (frame.cursorBlinking && !blinkVisible()) return 0

        // The cursor's shape. A block is the cell inverted; a bar and an
        // underline are rectangles of their own.
        //
        // The focus does not change it: this terminal is the app, and a cursor
        // that turned into an outline the moment the app went to the background
        // read as a bug, not as a hint about where the keystrokes go.
        val hollow = frame.cursorStyle == FrameFormat.CURSOR_STYLE_BLOCK_HOLLOW
        val block = frame.cursorStyle == FrameFormat.CURSOR_STYLE_BLOCK && !hollow

        if (block) return 0 // drawn by inverting the cell

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

        cursorProgram.use()
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
        return 1
    }

    /**
     * The visual bell: the accent colour over the terminal, fading away.
     *
     * The cursor's program draws a rectangle, and the grid is a rectangle, so
     * the wash is the same draw with the whole grid as the rectangle.
     */
    private fun drawBellFlash(frame: Frame, size: SizeInfo): Int {
        if (cursorProgram.handle == 0) return 0
        val elapsed = System.currentTimeMillis() - bellStartedAt
        if (elapsed >= BELL_FLASH_MS) return 0
        // The wash fades: it is at its strongest at the first frame and gone by
        // the last, so it reads as a pulse and not as a blink.
        val strength = 1f - elapsed.toFloat() / BELL_FLASH_MS

        val projection = FloatArray(4)
        size.projection(projection)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        cursorProgram.use()
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
        return 1
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

    /** The colour the search's other matches are drawn in. */
    private var highlightAllR = 0.3f
    private var highlightAllG = 0.5f
    private var highlightAllB = 1f

    /** Set the colour a search match is drawn in, from the theme. */
    fun setHighlightColour(argb: Int) {
        highlightR = ((argb shr 16) and 0xff) / 255f
        highlightG = ((argb shr 8) and 0xff) / 255f
        highlightB = (argb and 0xff) / 255f
    }

    /** Set the colour the search's other matches are drawn in, from the theme. */
    fun setHighlightAllColour(argb: Int) {
        highlightAllR = ((argb shr 16) and 0xff) / 255f
        highlightAllG = ((argb shr 8) and 0xff) / 255f
        highlightAllB = (argb and 0xff) / 255f
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
        val texture: GlTexture,
        val width: Int,
        val height: Int,
    )

    private val imageTextures = HashMap<Int, ImageTexture>()

    /** The pictures of the last frame, in the order they are drawn. */
    private val images = ArrayList<ImageDraw>()

    /**
     * The pictures, sorted the way they are drawn.
     *
     * By the layer first and then by the z, which is Ghostty's order: a
     * picture under the cell backgrounds is under the text, and a picture over
     * the text is over it. The order the engine hands them out is not the order
     * they are drawn in.
     */
    private fun sortedImages(): List<ImageDraw> =
        images.sortedWith(compareBy({ it.layer }, { it.z }))

    /** One picture: where it is, and the texture it is drawn from. */
    private class ImageDraw(
        val texture: GlTexture,
        val layer: Int,
        val z: Int,
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

    private var imageProjection = 0
    private var imageTexture = 0
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
            if (at + 69 > buffer.size) break
            // The record starts where the cursor says, not where the last
            // read left the buffer: a record's pixels follow it, and a read
            // that did not skip them starts the next record inside a picture
            // — a length of -1 and a crash in the copy.
            little.position(at)
            val id = little.int
            val generation = little.long
            val layer = little.int
            val z = little.int
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
            // The record is sixty-nine bytes: four and eight for the id and
            // the generation, fourteen fours, then the flag's single byte. A
            // stride that is one byte out reads the pixels one byte early and
            // every picture is garbage — which is what a kitty image looked
            // like.
            at += 69

            // The pixels, when they are new.
            if (hasPixels && dataLength > 0 && at + dataLength <= buffer.size) {
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
                    layer = layer,
                    z = z,
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
        // RGB has no alpha; the picture is drawn over the terminal, so it is
        // opaque.
        val internalFormat =
            if (format == KITTY_FORMAT_RGB) GLES20.GL_RGB else GLES20.GL_RGBA
        // The same texture name across generations: a picture that changes size
        // re-allocates its storage rather than taking a new name.
        val texture = imageTextures[id]?.texture ?: GlTexture()
        // A picture's rows are not padded to four bytes, so the unpack is one
        // byte at a time. Nearest, not linear: a picture is a picture.
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        texture.create(width, height, internalFormat, nearest = true)
        val data = java.nio.ByteBuffer.wrap(pixels)
        texture.subImage(0, 0, width, height, internalFormat, data)
        imageTextures[id] = ImageTexture(generation, texture, width, height)
    }

    /**
     * Draws the pictures, over the text.
     *
     * A picture is *on* the grid: it covers the cells it is placed on, so it
     * is drawn after the glyphs and before the cursor.
     *
     * Returns the number of draw calls it took: one per picture today, which
     * is the number the image batching work has to beat.
     */
    private fun drawImages(size: SizeInfo, layer: Int): Int {
        if (images.isEmpty() || imageProgram.handle == 0) return 0
        val drawn = sortedImages().filter { it.layer == layer }
        if (drawn.isEmpty()) return 0

        val floats = drawn.size * 8
        if (imageVertices == null || imageVertices!!.capacity() < floats * 4) {
            imageVertices = java.nio.ByteBuffer
                .allocateDirect(floats * 4)
                .order(java.nio.ByteOrder.nativeOrder())
        }
        val buffer = imageVertices!!

        val projection = FloatArray(4)
        size.projection(projection)

        imageProgram.use()
        GLES20.glUniform4fv(imageProjection, 1, projection, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(imageTexture, 0)

        imageBuffer.bind()
        // The pictures are the instances: one per picture, four vertices each.
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 32, 0)
        GLES30.glVertexAttribDivisor(0, 1)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 4, GLES20.GL_FLOAT, false, 32, 16)
        GLES30.glVertexAttribDivisor(1, 1)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // The pictures are drawn in runs of the same texture.
        //
        // A run is one instanced draw: the texture is bound once for it and
        // the pictures of the run are its instances. The runs keep the order
        // the pictures were sorted in, so two pictures that overlap are still
        // drawn in the order they were placed.
        var calls = 0
        var start = 0
        while (start < drawn.size) {
            var end = start + 1
            while (end < drawn.size && drawn[end].texture == drawn[start].texture) {
                end += 1
            }

            buffer.clear()
            for (index in start until end) {
                val image = drawn[index]
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
            imageBuffer.data(buffer.remaining(), buffer)

            drawn[start].texture.bind()
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, end - start)
            calls += 1
            start = end
        }

        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
        return calls
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

    /**
     * Whether adjacent operator cells may shape into ligatures.
     *
     * Off by default would be the conservative choice, but the setting's
     * default is on and the renderer is told the setting through
     * [setLigatures]. The per-cell path is always available, so a font without
     * ligature tables simply draws the same glyphs it drew before.
     */
    private var ligatures: Boolean = true

    /** Follow the terminal setting. The atlas keeps both shaped and plain masks. */
    fun setLigatures(enabled: Boolean) {
        ligatures = enabled
        forceFullFrame = true
    }

    /** A shaped run: its text and how many cells it replaces. */
    private data class LigatureRun(val cells: Int, val text: String)

    private fun backgroundOf(frame: Frame, index: Int): Int =
        if (index == invertedCell) frame.foreground(index) else frame.background(index)

    private fun foregroundOf(frame: Frame, index: Int): Int =
        if (index == invertedCell) frame.background(index) else frame.foreground(index)

    /**
     * Build the sprites of the rows that changed.
     *
     * A row's sprites are packed behind the rows before it, so a row that
     * changed *size* moves every sprite after it. A row that kept its count —
     * a cursor's inversion, a colour change, a letter replaced by another
     * letter — is written where it already was, and the rows below it are left
     * alone. That is the difference between a cursor blink costing one row and
     * costing half the screen.
     *
     * @return how many sprites the buffer holds.
     */
    private fun buildForegrounds(frame: Frame, dirty: BooleanArray): Int {
        // The cell's size is part of a sprite: a glyph is rasterised at the
        // font's size, and the quad it is drawn in is the cell's. A pinch
        // changes the size without changing a single cell of the engine's
        // grid, so a pinch is a frame where no row is dirty and every sprite
        // is new. Without this the cells grew and the masks kept the old size
        // — the letters' spacing opening and closing under the fingers.
        val cellSizeChanged = cellWidth != spriteCellWidth || cellHeight != spriteCellHeight
        if (cellSizeChanged) {
            spriteCellWidth = cellWidth
            spriteCellHeight = cellHeight
        }
        cells.buildSprites(frame, dirty, cellSizeChanged) { buffer, index, column, gridRow ->
            val run = ligatureRunAt(frame, index, column)
            val attributes = frame.attributes(index)
            val bold = attributes and FrameFormat.ATTR_BOLD != 0
            val italic = attributes and FrameFormat.ATTR_ITALIC != 0
            val blink = attributes and FrameFormat.ATTR_BLINK != 0
            val entry = if (run != null) {
                atlas.run(run.text, bold, italic, rasterPx)
            } else {
                val codepoint = frame.codepoint(index)
                if (codepoint == 0 || codepoint == ' '.code || Emoji.isEmoji(codepoint)) {
                    // An emoji is the colour pass's: a mask of it is a silhouette.
                    null
                } else {
                    atlas.glyph(codepoint, bold, italic, rasterPx)
                }
            }
            if (entry == null || entry.isBlank) {
                if (run != null) run.cells else 0
            } else {
                putSprite(
                    buffer = buffer,
                    entry = entry,
                    column = column,
                    gridRow = gridRow,
                    color = foregroundOf(frame, index),
                    blink = blink,
                    span = run?.cells ?: if (frame.width(index) == 2) 2 else 1,
                )
                run?.cells ?: 1
            }
        }
        return cells.spriteCount
    }

    /**
     * A run that can be shaped as one ligature, or null for a single cell.
     *
     * The scan stops at the cursor, a style or colour change, a wide cell, an
     * emoji, a blank, or the end of the row. Those are the same breaks Ghostty
     * uses: a run is the largest span whose glyphs may legally share a shape.
     */
    private fun ligatureRunAt(frame: Frame, index: Int, column: Int): LigatureRun? {
        if (!ligatures) return null

        val first = frame.codepoint(index)
        if (!isShapable(first)) return null

        val attributes = frame.attributes(index)
        val color = foregroundOf(frame, index)
        val base = index - column
        val limit = minOf(frame.columns, column + LigaturePolicy.MAX_CELLS)
        // The scan is asked about the run's own column: `column` is where this
        // run starts in the row, and the cells are read from there.
        val count = LigaturePolicy.runLength(column, limit) { at ->
            val cell = base + at
            cell != invertedCell &&
                isShapable(frame.codepoint(cell)) &&
                frame.width(cell) == 1 &&
                frame.attributes(cell) == attributes &&
                foregroundOf(frame, cell) == color
        }
        if (count < 2) return null
        val shaped = IntArray(count) { frame.codepoint(base + column + it) }
        if (!LigaturePolicy.canShape(shaped)) return null
        return LigatureRun(count, String(shaped, 0, count))
    }

    private fun isShapable(codepoint: Int): Boolean =
        codepoint > 0 && codepoint != ' '.code && !Emoji.isEmoji(codepoint)

    /** Write one sprite record; the layout is the shader's vertex layout. */
    private fun putSprite(
        buffer: java.nio.ByteBuffer,
        entry: dev.drosh.terminal.engine.font.GlyphMask,
        column: Int,
        gridRow: Int,
        color: Int,
        blink: Boolean,
        span: Int,
    ) {
        // The mask's line box, centred in the cell it is drawn in.
        //
        // The cell is the font's line box, so the two have the same height and
        // the remainder is zero — almost. The atlas rasterises a mask in whole
        // rows (`ceil`) at a size that may be supersampled, while the cell is a
        // measured float, so the mask can end up a pixel taller than its cell.
        // Ghostty puts exactly that remainder half above and half below the
        // face (font/Metrics.zig derives `cell_baseline` from
        // `(cell_height - face_height) / 2`), so a glyph never sits a whole
        // pixel low in its cell.
        val lineBox = cellHeight * atlas.scale - entry.height
        buffer.putShort(entry.x.toShort())
        buffer.putShort(entry.y.toShort())
        buffer.putShort(entry.width.toShort())
        buffer.putShort(entry.height.toShort())
        buffer.putShort(entry.left.toShort())
        buffer.putShort((entry.top + lineBox / 2f).toInt().toShort())
        buffer.putShort(column.toShort())
        buffer.putShort(gridRow.toShort())
        putColor(buffer, color)
        buffer.putShort((if (blink) FLAG_BLINK else FLAG_NONE).toShort())
        buffer.putShort(span.toShort())
    }

    // --- drawing -----------------------------------------------------------

    private fun drawBackgrounds(frame: Frame, size: SizeInfo): Int {
        if (frame.cellCount == 0) return 0

        val projection = FloatArray(4)
        size.projection(projection)

        backgroundProgram.use()
        GLES20.glUniform4fv(backgroundProjection, 1, projection, 0)
        GLES20.glUniform2f(backgroundCellSize, cellWidth, cellHeight)

        backgroundBuffer.bind()

        val stride = Cells.BACKGROUND_BYTES
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribIPointer(0, 2, GLES30.GL_UNSIGNED_SHORT, stride, 0)
        GLES30.glVertexAttribDivisor(0, 1)

        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_UNSIGNED_BYTE, true, stride, 4)
        GLES30.glVertexAttribDivisor(1, 1)

        // The decorations: transparent when the cell has none.
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_UNSIGNED_BYTE, true, stride, 8)
        GLES30.glVertexAttribDivisor(2, 1)

        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_UNSIGNED_BYTE, true, stride, 12)
        GLES30.glVertexAttribDivisor(3, 1)

        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 4, GLES30.GL_UNSIGNED_BYTE, true, stride, 16)
        GLES30.glVertexAttribDivisor(4, 1)

        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, frame.cellCount)
        return 1
    }

    /**
     * Hand the changed rows of the background to the GPU.
     *
     * The buffer on the GPU is the same one from frame to frame, and the rows
     * that did not change are already on it. The rows that did are written
     * over the top of them — but the whole buffer has to be allocated on the
     * GPU first, and again whenever the grid changes shape, because a
     * sub-update has nowhere to write into a buffer that was never sized.
     */
    private fun uploadBackgrounds(frame: Frame, dirty: BooleanArray) {
        if (frame.cellCount == 0) return
        backgroundBuffer.bind()
        val buffer = cells.backgrounds
        val bytes = buffer.capacity()

        if (glBackgroundBytes != bytes) {
            // The first frame, or a grid the GPU has not seen before: the
            // whole buffer goes up.
            glBackgroundBytes = bytes
            buffer.position(0)
            backgroundBuffer.data(bytes, buffer)
            return
        }

        // Only the rows that changed, in runs of neighbouring rows.
        //
        // The limit is never touched: a GL upload reads `size` bytes from the
        // position and nothing else, and shrinking the limit for one run makes
        // the next run's position illegal — `position > limit` is the crash a
        // second dirty run caused.
        var row = 0
        while (row < frame.rowCount) {
            if (!dirty[row]) {
                row += 1
                continue
            }
            val from = row
            while (row < frame.rowCount && dirty[row]) {
                row += 1
            }
            val offset = from * frame.columns * Cells.BACKGROUND_BYTES
            val size = (row - from) * frame.columns * Cells.BACKGROUND_BYTES
            buffer.position(offset)
            backgroundBuffer.subData(offset, size, buffer)
        }
        buffer.position(0)
    }

    private fun drawForegrounds(frame: Frame, size: SizeInfo, count: Int): Int {
        if (count == 0) return 0

        val projection = FloatArray(4)
        size.projection(projection)

        foregroundProgram.use()
        GLES20.glUniform4fv(foregroundProjection, 1, projection, 0)
        GLES20.glUniform2f(foregroundCellSize, cellWidth, cellHeight)
        GLES20.glUniform2f(foregroundAtlasSize, atlas.size.toFloat(), atlas.size.toFloat())
        // The masks may be rasterised larger than the font, so that a small
        // font's strokes survive being sampled between texels. The shader
        // divides the mask's size by this to get the size to draw at.
        GLES20.glUniform1f(foregroundAtlasScale, atlas.scale)
        // A blinking glyph is dimmed, not hidden: the cell still says where a
        // word is while it blinks. The loop wakes on the blink clock, so this
        // uniform is re-read on the frame that flips the phase.
        GLES20.glUniform1f(
            foregroundBlinkAlpha,
            if (RenderClock.blinkVisible()) 1f else BLINK_DIM_ALPHA,
        )

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        atlasTexture.bind()
        GLES20.glUniform1i(foregroundAtlas, 0)

        foregroundBuffer.bind()

        // atlasRect, glyphSize, offset, gridPos, colour, flags, span
        val stride = Cells.SPRITE_BYTES
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
        return 1
    }

    /**
     * Hand the sprites to the GPU.
     *
     * A row's sprites move when the row above it changes size, so the rows
     * after the first changed one are written again as well. The sprites
     * before it are where they were, and the GPU has them. A frame that only
     * rewrote some rows in place uploads exactly those rows — the range the
     * build recorded — rather than everything from the first changed row to
     * the bottom of the screen.
     */
    private fun uploadForegrounds() {
        if (!cells.spritesRebuilt) return
        foregroundBuffer.bind()
        val buffer = cells.sprites
        val bytes = buffer.capacity()

        if (glSpriteBytes != bytes) {
            glSpriteBytes = bytes
            buffer.position(0)
            foregroundBuffer.data(bytes, buffer)
            return
        }

        val offset = cells.spriteUploadStart()
        val size = cells.spriteUploadEnd() - offset
        if (size <= 0) return
        buffer.position(offset)
        foregroundBuffer.subData(offset, size, buffer)
        buffer.position(0)
    }

    /**
     * Upload the atlas rows that changed.
     *
     * The atlas is 2048x2048 — four megabytes. Uploading all of it on every
     * frame it changed is four megabytes a frame, and a pinch changes it on
     * every step of the gesture: the text got bigger, every mask was
     * rasterised again, and the whole megabyte went to the GPU each time. That
     * is the pinch's stutter.
     *
     * Only the rows that changed go up, and the buffer's position says where
     * they start.
     *
     * @return true when a region went up.
     */
    private fun uploadAtlas(): Boolean {
        val rows = atlas.dirtyRows()
        if (rows == null) {
            atlas.dirty = false
            return false
        }
        val height = rows.count()
        if (height <= 0) {
            atlas.dirty = false
            atlas.clearDirty()
            return false
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        atlasTexture.bind()

        val pixels = atlas.directPixels()
        val start = rows.first * atlas.size
        // The limit is only ever the capacity: the upload reads a window of the
        // buffer, but the atlas writes into the whole of it with absolute
        // puts, and an absolute put is checked against the limit. Shrinking it
        // for the upload left the buffer refusing the next glyph.
        pixels.limit(pixels.capacity())
        pixels.position(start)

        atlasTexture.subImage(0, rows.first, atlas.size, height, GLES30.GL_RED, pixels)
        atlas.clearDirty()
        atlas.dirty = false
        return true
    }

    /**
     * Build the emoji of the frame.
     *
     * The list is kept from frame to frame: an emoji is derived from the
     * cell's codepoint and the cell's size, so a frame whose rows did not
     * change — the cursor's blink, a redraw of the same picture — already has
     * the list it would build. Rebuilding it there is a walk of every cell for
     * a picture that cannot have moved.
     *
     * A frame whose *content* changed, or whose geometry did, rebuilds the
     * whole list. A per-row list would rebuild fewer cells, but the walk is a
     * codepoint read and a range check per cell, and the dirty rows are
     * usually the ones the foreground pass is walking anyway.
     */
    private fun buildColorGlyphs(frame: Frame, contentDirty: Boolean) {
        val geometryChanged = !colorGlyphsBuilt ||
            frame.columns != colorGlyphColumns ||
            frame.rowCount != colorGlyphRows ||
            frame.firstRowY != colorGlyphFirstRowY ||
            cellWidth != colorGlyphCellWidth ||
            cellHeight != colorGlyphCellHeight
        if (!geometryChanged && !contentDirty) return

        colorGlyphsBuilt = true
        colorGlyphColumns = frame.columns
        colorGlyphRows = frame.rowCount
        colorGlyphFirstRowY = frame.firstRowY
        colorGlyphCellWidth = cellWidth
        colorGlyphCellHeight = cellHeight

        colorGlyphs.clear()
        for (row in 0 until frame.rowCount) {
            val gridRow = row + frame.firstRowY
            val base = row * frame.columns
            for (column in 0 until frame.columns) {
                val index = base + column
                val codepoint = frame.codepoint(index)
                if (codepoint == 0 || !Emoji.isEmoji(codepoint)) continue
                val span = if (frame.width(index) == 2) 2 else 1
                val glyph = colorAtlas.glyph(codepoint, span, cellHeight) ?: continue
                if (glyph.isBlank) continue

                // The atlas slot is rasterised at span×1, so the source UV
                // covers the whole slot and the quad maps it 1:1 — no stretch.
                colorGlyphs.add(
                    ImageDraw(
                        texture = colorAtlasTexture,
                        layer = LAYER_ABOVE_TEXT,
                        z = 0,
                        col = 0,
                        row = 0,
                        offsetX = (column * cellWidth).toInt(),
                        offsetY = (gridRow * cellHeight).toInt(),
                        width = (span * cellWidth).toInt(),
                        height = cellHeight.toInt(),
                        sourceU = glyph.x.toFloat() / colorAtlas.size.toFloat(),
                        sourceV = glyph.y.toFloat() / colorAtlas.size.toFloat(),
                        sourceU2 = (glyph.x + glyph.width).toFloat() / colorAtlas.size.toFloat(),
                        sourceV2 = (glyph.y + glyph.height).toFloat() / colorAtlas.size.toFloat(),
                    ),
                )
            }
        }
    }

    /** The emoji of the last build, and the geometry it was built for. */
    private var colorGlyphsBuilt = false
    private var colorGlyphColumns = 0
    private var colorGlyphRows = 0
    private var colorGlyphFirstRowY = 0
    private var colorGlyphCellWidth = 0f
    private var colorGlyphCellHeight = 0f

    /** Hand the emoji to the GPU: the rows that changed, or all of them. */
    private fun uploadColorAtlas() {
        val rows = colorAtlas.dirtyRows()
        if (rows == null) {
            colorAtlas.dirty = false
            return
        }
        val height = rows.count()
        if (height <= 0) {
            colorAtlas.dirty = false
            colorAtlas.clearDirty()
            return
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        colorAtlasTexture.bind()
        val pixels = colorAtlas.directPixels()
        pixels.limit(pixels.capacity())
        pixels.position(rows.first * colorAtlas.size * 4)
        colorAtlasTexture.subImage(0, rows.first, colorAtlas.size, height, GLES20.GL_RGBA, pixels)
        colorAtlas.clearDirty()
        colorAtlas.dirty = false
    }

    /** Draw the emoji, over the glyphs and under the cursor. */
    private fun drawColorGlyphs(size: SizeInfo): Int {
        if (colorGlyphs.isEmpty() || imageProgram.handle == 0) return 0
        val floats = colorGlyphs.size * 8
        if (imageVertices == null || imageVertices!!.capacity() < floats * 4) {
            imageVertices = java.nio.ByteBuffer
                .allocateDirect(floats * 4)
                .order(java.nio.ByteOrder.nativeOrder())
        }
        val buffer = imageVertices!!
        buffer.clear()
        for (image in colorGlyphs) {
            buffer.putFloat(image.col * cellWidth + image.offsetX)
            buffer.putFloat(image.row * cellHeight + image.offsetY)
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

        imageProgram.use()
        GLES20.glUniform4fv(imageProjection, 1, projection, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(imageTexture, 0)
        colorAtlasTexture.bind()

        imageBuffer.bind()
        imageBuffer.data(buffer.remaining(), buffer)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 4, GLES20.GL_FLOAT, false, 32, 0)
        GLES30.glVertexAttribDivisor(0, 1)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 4, GLES20.GL_FLOAT, false, 32, 16)
        GLES30.glVertexAttribDivisor(1, 1)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, colorGlyphs.size)
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glDisableVertexAttribArray(1)
        return 1
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
        /** A picture under the cell backgrounds. */
        const val LAYER_BELOW_BACKGROUND = 0

        /** A picture between the cell backgrounds and the text. */
        const val LAYER_BELOW_TEXT = 1

        /** A picture over the text. */
        const val LAYER_ABOVE_TEXT = 2

        /** The kitty protocol's RGB picture format. */
        const val KITTY_FORMAT_RGB = 0

        /** How long the visual bell's flash lasts, in milliseconds. */
        const val BELL_FLASH_MS = 140L

        /** How strong the bell's wash is at its strongest. */
        const val BELL_FLASH_ALPHA = 0.22f

        /** How much of the highlight colour goes over the cell's background. */
        const val HIGHLIGHT_TINT = 0.35f

        /** How much of the all-matches colour goes over the cell's background. */
        const val HIGHLIGHT_ALL_TINT = 0.2f

        /** A bar cursor's width, as a fraction of a cell. */
        const val BAR_WIDTH = 0.1f

        /** An underline cursor's height, as a fraction of a cell. */
        const val UNDERLINE_HEIGHT = 0.1f

        const val FLAG_NONE = 0

        /** The sprite's glyph is a blinking one. Bit zero is the UV inset. */
        const val FLAG_BLINK = 1 shl 1

        /** A blinking glyph at half brightness in the dark phase. */
        const val BLINK_DIM_ALPHA = 0.18f

        /** The terminal's own ground, until the theme says otherwise. */
        const val DEFAULT_BACKGROUND_R = 0.094f
        const val DEFAULT_BACKGROUND_G = 0.094f
        const val DEFAULT_BACKGROUND_B = 0.094f
    }
}
