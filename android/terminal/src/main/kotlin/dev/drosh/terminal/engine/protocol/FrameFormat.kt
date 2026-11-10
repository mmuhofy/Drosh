package dev.drosh.terminal.engine.protocol

/**
 * The frame's wire format, as Kotlin reads it.
 *
 * ## Why a frame and not a state array
 *
 * The previous engine had six separate JNI calls per frame — cells, state,
 * colours, damage, hyperlinks, prompt rows — each taking the engine's lock at a
 * different moment. A frame assembled from six moments is a frame whose parts
 * can disagree: the cells from before a scroll, the offset from after it. That
 * class of bug is not fixable by being careful; it is fixable by asking once.
 *
 * ## The layout
 *
 * Little-endian throughout, because every Android target is.
 *
 * ```
 * header (48 bytes)
 *   0  u32  magic          'DRSH'
 *   4  u16  version
 *   6  u16  headerBytes
 *   8  u32  frameSequence
 *  12  u16  columns
 *  14  u16  rows           viewport rows, overscan excluded
 *  16  u16  rowCount       rows actually written, overscan included
 *  18  i16  overscanAbove
 *  20  i16  overscanBelow
 *  22  u16  cursorX
 *  24  u16  cursorY        relative to the viewport
 *  26  u16  cursorStyle
 *  28  u16  cursorFlags    bit0 visible, bit1 blinking, bit2 thin rectangle
 *  30  u16  dirty          bit0 full, bit1 partial, bit2 none
 *  32  u32  cellCount
 *  36  u32  cellStride
 *  40  u32  cellsBytes
 *  44  u32  cursorColor    R,G,B,A little-endian
 * ```
 *
 * Cells follow, `cellCount` records of `cellStride` bytes:
 *
 * ```
 *  0  u32  codepoint (base)
 *  4  u32  foreground RGBA
 *  8  u32  background RGBA
 * 12  u32  attributes
 * 16  u8   width     1 narrow, 2 wide, 0 spacer tail
 * 17  u8   flags     bit0 selected, bit1 has grapheme
 * 18  u16  reserved
 * ```
 *
 * The colours are resolved: the library has already applied the palette, the
 * inverse flag, bold-brightening and the selection. Nothing here decides what
 * a colour means.
 */
object FrameFormat {

    /** `'DRSH'` little-endian. */
    const val MAGIC: Int = 0x48535244

    const val VERSION: Int = 1

    const val HEADER_BYTES: Int = 48
    const val CELL_BYTES: Int = 20

    // --- header offsets ---------------------------------------------------

    const val OFFSET_MAGIC = 0
    const val OFFSET_VERSION = 4
    const val OFFSET_HEADER_BYTES = 6
    const val OFFSET_SEQUENCE = 8
    const val OFFSET_COLUMNS = 12
    const val OFFSET_ROWS = 14
    const val OFFSET_ROW_COUNT = 16
    const val OFFSET_OVERSCAN_ABOVE = 18
    const val OFFSET_OVERSCAN_BELOW = 20
    const val OFFSET_CURSOR_X = 22
    const val OFFSET_CURSOR_Y = 24
    const val OFFSET_CURSOR_STYLE = 26
    const val OFFSET_CURSOR_FLAGS = 28
    const val OFFSET_DIRTY = 30
    const val OFFSET_CELL_COUNT = 32
    const val OFFSET_CELL_STRIDE = 36
    const val OFFSET_CELLS_BYTES = 40
    const val OFFSET_CURSOR_COLOR = 44

    // --- cell offsets -----------------------------------------------------

    const val CELL_CODEPOINT = 0
    const val CELL_FOREGROUND = 4
    const val CELL_BACKGROUND = 8
    const val CELL_ATTRIBUTES = 12
    const val CELL_WIDTH = 16
    const val CELL_FLAGS = 17

    // --- colours ----------------------------------------------------------

    /**
     * The one colour contract on the wire.
     *
     * A colour is four bytes — R, G, B, A — which is what a normalised
     * `GL_UNSIGNED_BYTE` vertex attribute hands the fragment shader as a
     * `vec4`. Packed into a little-endian `Int` that is red in the low byte and
     * alpha in the high one.
     *
     * Both sides build and read colours through these helpers, so "which byte
     * is red" has one answer.
     */
    fun packRgba(r: Int, g: Int, b: Int, a: Int = 255): Int =
        (r and 0xff) or
            ((g and 0xff) shl 8) or
            ((b and 0xff) shl 16) or
            ((a and 0xff) shl 24)

    fun red(color: Int): Int = color and 0xff
    fun green(color: Int): Int = (color ushr 8) and 0xff
    fun blue(color: Int): Int = (color ushr 16) and 0xff
    fun alpha(color: Int): Int = (color ushr 24) and 0xff

    // --- attributes -------------------------------------------------------

    const val ATTR_BOLD = 1 shl 0
    const val ATTR_ITALIC = 1 shl 1
    const val ATTR_FAINT = 1 shl 2
    const val ATTR_BLINK = 1 shl 3
    const val ATTR_STRIKETHROUGH = 1 shl 4
    const val ATTR_OVERLINE = 1 shl 5
    const val ATTR_INVISIBLE = 1 shl 6

    /** Underline style occupies bits 8..11: 0 none, 1 single, 2 double, … */
    const val ATTR_UNDERLINE_SHIFT = 8
    const val ATTR_UNDERLINE_MASK = 0xf shl ATTR_UNDERLINE_SHIFT

    // --- cell flags -------------------------------------------------------

    const val CELL_FLAG_SELECTED = 1 shl 0
    const val CELL_FLAG_GRAPHEME = 1 shl 1

    // --- dirty ------------------------------------------------------------

    const val DIRTY_NONE = 0
    const val DIRTY_FULL = 1
    const val DIRTY_PARTIAL = 2

    // --- cursor flags -----------------------------------------------------

    const val CURSOR_VISIBLE = 1 shl 0
    const val CURSOR_BLINKING = 1 shl 1

    /**
     * The cursor is a thin rectangle — a bar or an underline — rather than a
     * filled cell.
     *
     * A block cursor over a glyph is drawn by inverting that cell; a bar and an
     * underline are shapes of their own and the glyph under them stays legible.
     */
    const val CURSOR_IS_RECTANGLE = 1 shl 2

    /** Cursor visual styles, matching the engine's own enum. */
    const val CURSOR_STYLE_BAR = 0
    const val CURSOR_STYLE_BLOCK = 1
    const val CURSOR_STYLE_UNDERLINE = 2
    const val CURSOR_STYLE_BLOCK_HOLLOW = 3
}
