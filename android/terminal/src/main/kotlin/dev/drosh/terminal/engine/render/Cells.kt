package dev.drosh.terminal.engine.render

import dev.drosh.terminal.engine.protocol.Frame
import dev.drosh.terminal.engine.protocol.FrameFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The cells of one frame, in the shape the GPU draws them.
 *
 * ## Why this is its own thing
 *
 * Ghostty's renderer keeps exactly this: a flat array of cell backgrounds and
 * a list of sprites per row. The shape is not decoration — it is what makes a
 * *row* the unit of change. The engine says which of its rows changed, and a
 * row is precisely the piece that can be built on its own, uploaded on its
 * own, and left alone when it did not change.
 *
 * So the storage is per row, not per frame: the background of a row is a
 * contiguous run of records, the sprites of a row are a contiguous run of
 * records, and a row that did not change is a run nobody touches.
 *
 * ## The row space
 *
 * Every index here is the frame's own **captured row** — the first row the
 * engine captured, which is the overscan row above the viewport. The viewport's
 * first row is `overscanAbove` captured rows further down. Working in that
 * space is what keeps the arithmetic honest: the record of a cell is at
 * `row * columns + column`, and the byte is that times the record size. There
 * is no place where a viewport row and a captured row can be confused, which
 * is the off-by-one-row that a background drawn from the wrong place looks
 * like.
 *
 * ## What is built
 *
 * [build] writes the rows whose bit is set in `dirty`. A row that is not dirty
 * keeps the records it has: the buffers are the same ones from frame to frame,
 * and the engine guaranteed that the row's cells did not change.
 */
internal class Cells {

    /** The frame's shape, as the last build saw it. */
    var columns = 0
        private set
    var rows = 0
        private set
    var overscanAbove = 0
        private set
    var firstRowY = 0
        private set

    /**
     * The backgrounds: one record per cell, in the frame's cell order.
     *
     * `u16 column, u16 gridRow, vec4 colour, vec4 underline`.
     */
    var backgrounds: ByteBuffer = EMPTY
        private set

    /**
     * The sprites, packed in row order.
     *
     * The sprites of a row start where the row before it ended, so a row's
     * sprites move when the row above it grows or shrinks — which is why a
     * changed row is built together with the rows after it.
     */
    var sprites: ByteBuffer = EMPTY
        private set

    /** Where each row's sprites start, in bytes. */
    private var rowStart = IntArray(0)

    /** How many sprites the buffer holds, all rows together. */
    var spriteCount = 0
        private set

    /** The byte the last build started writing the sprites at. */
    var spriteUploadFrom = 0
        private set

    /** Whether the sprites were built at all in the last call. */
    var spritesRebuilt = false
        private set

    /**
     * Make the storage fit the frame's shape.
     *
     * A different shape means the whole grid is new: the buffers are made
     * again, and the next build is a full one.
     */
    fun resize(frame: Frame) {
        if (columns == frame.columns && rows == frame.rowCount) {
            firstRowY = frame.firstRowY
            overscanAbove = frame.overscanAbove
            return
        }
        columns = frame.columns
        rows = frame.rowCount
        overscanAbove = frame.overscanAbove
        firstRowY = frame.firstRowY

        backgrounds = ByteBuffer
            .allocateDirect(frame.cellCount * BACKGROUND_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        // One sprite per cell is the most a row can have: a wide character
        // covers two cells and is drawn as one glyph, so the count is under
        // `columns` in practice and exactly `columns` at worst.
        sprites = ByteBuffer
            .allocateDirect(frame.cellCount * SPRITE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        rowStart = IntArray(rows)
        spriteCount = 0
        spriteUploadFrom = 0
        spritesRebuilt = false
    }

    /** The byte a cell's background record starts at. */
    fun backgroundAt(row: Int, column: Int): Int =
        (row * columns + column) * BACKGROUND_BYTES

    /**
     * Build a row's background.
     *
     * @param row the captured row.
     * @param colour the colour a cell is painted with.
     * @param underline the colour of a cell's underline rule, or transparent.
     * @param strikethrough the colour of a cell's strikethrough, or transparent.
     * @param overline the colour of a cell's overline, or transparent.
     */
    fun buildBackgroundRow(
        frame: Frame,
        row: Int,
        colour: (Int) -> Int,
        underline: (Int) -> Int,
        strikethrough: (Int) -> Int,
        overline: (Int) -> Int,
    ) {
        val base = row * columns
        for (column in 0 until columns) {
            val index = base + column
            backgrounds.position(index * BACKGROUND_BYTES)
            backgrounds.putShort(column.toShort())
            backgrounds.putShort((row + firstRowY).toShort())
            putColour(backgrounds, colour(index))
            putColour(backgrounds, underline(index))
            putColour(backgrounds, strikethrough(index))
            putColour(backgrounds, overline(index))
        }
    }

    /**
     * Build the sprites from [firstRow] to the last row.
     *
     * A row's sprites move when the row above it changed size, so the rows
     * after the first changed one are built again even if their own cells did
     * not change. The rows before it are where they were, and are left alone.
     *
     * The writer is the caller's: it knows the atlas and the colours, and this
     * class knows where the record goes.
     *
     * @param firstRow the first row to build, or `rows` for none.
     * @param write writes one sprite at the buffer's position, and returns
     *   whether it wrote one. A blank cell writes nothing.
     */
    fun buildSprites(
        frame: Frame,
        firstRow: Int,
        write: (buffer: ByteBuffer, index: Int, column: Int, gridRow: Int) -> Boolean,
    ) {
        spritesRebuilt = false
        if (firstRow >= rows) {
            return
        }
        val from = if (rowStart.size < rows) 0 else firstRow
        var at = if (from == 0) 0 else rowStart[from]
        spriteUploadFrom = at

        for (row in from until rows) {
            rowStart[row] = at
            val gridRow = row + firstRowY
            val base = row * columns
            for (column in 0 until columns) {
                val index = base + column
                sprites.position(at)
                if (!write(sprites, index, column, gridRow)) {
                    continue
                }
                at += SPRITE_BYTES
            }
        }

        spriteCount = at / SPRITE_BYTES
        spritesRebuilt = true
    }

    /** The byte offset the sprite upload should start at. */
    fun spriteUploadStart(): Int = spriteUploadFrom

    private fun putColour(buffer: ByteBuffer, packed: Int) {
        buffer.put(packed.toByte())
        buffer.put((packed ushr 8).toByte())
        buffer.put((packed ushr 16).toByte())
        buffer.put((packed ushr 24).toByte())
    }

    companion object {
        /**
         * `u16 column, u16 gridRow, vec4 colour, vec4 underline,
         * vec4 strikethrough, vec4 overline`.
         *
         * A decoration is a rule that belongs to the cell rather than to the
         * character in it, so it travels with the cell's background. A rule the
         * cell does not have is transparent and the shader draws nothing.
         */
        const val BACKGROUND_BYTES = 20

        /**
         * `u16×2 atlasRect, u16×2 glyphSize, i16×2 offset, u16×2 gridPos,
         * vec4 colour, u16 flags, u16 span`.
         */
        const val SPRITE_BYTES = 24

        /** A colour that draws nothing. */
        const val TRANSPARENT = 0

        private val EMPTY: ByteBuffer = ByteBuffer.allocateDirect(0)
            .order(ByteOrder.LITTLE_ENDIAN)
    }
}