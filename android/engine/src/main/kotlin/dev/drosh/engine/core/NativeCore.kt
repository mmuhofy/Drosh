package dev.drosh.engine.core

/**
 * JNI surface of `libdrosh_core.so`.
 *
 * Every numeric value is mirrored from the Rust side (`rust/core/src/ffi/`,
 * `bridge/cell.rs`, `bridge/snapshot.rs`) — both sides change together. Nothing
 * throws across the boundary: a function returns either an `ErrorCode` or a
 * handle (`> 0`).
 *
 * The declarations follow the pattern Termi runs on device (`object` +
 * `external fun`, no `@JvmStatic`); the Rust side ignores the receiver
 * argument.
 */
internal object NativeCore {

    init {
        // Cargo turns the crate name `drosh-core` into `libdrosh_core.so`.
        System.loadLibrary("drosh_core")
    }

    // --- error codes (ffi::ErrorCode) ---
    const val OK = 0
    const val INVALID_HANDLE = -1
    const val TOO_MANY_SESSIONS = -2
    const val SPAWN_FAILED = -3
    const val INVALID_ARGUMENT = -4
    const val IO_ERROR = -5
    const val INTERNAL = -6
    const val BUFFER_TOO_SMALL = -7
    const val NOT_SUPPORTED = -8

    // --- cell record (bridge::cell), 16 bytes little-endian ---
    const val CELL_BYTES = 16
    const val CELL_OFFSET_CODEPOINT = 0
    const val CELL_OFFSET_FG = 4
    const val CELL_OFFSET_BG = 8
    const val CELL_OFFSET_FLAGS = 12

    const val COLOR_TAG_NAMED = 0
    const val COLOR_TAG_INDEXED = 1
    const val COLOR_TAG_SPEC = 2
    const val COLOR_TAG_SHIFT = 30
    const val COLOR_VALUE_MASK = 0x00ffffff

    const val FLAG_INVERSE = 1 shl 0
    const val FLAG_BOLD = 1 shl 1
    const val FLAG_ITALIC = 1 shl 2
    const val FLAG_UNDERLINE = 1 shl 3
    const val FLAG_STRIKEOUT = 1 shl 4
    const val FLAG_DIM = 1 shl 5
    const val FLAG_HIDDEN = 1 shl 6
    const val FLAG_WIDE_CHAR = 1 shl 7
    const val FLAG_WIDE_CHAR_SPACER = 1 shl 8
    const val FLAG_LEADING_WIDE_CHAR_SPACER = 1 shl 9
    const val FLAG_WRAPLINE = 1 shl 10
    const val FLAG_DOUBLE_UNDERLINE = 1 shl 11
    const val FLAG_UNDERCURL = 1 shl 12
    const val FLAG_DOTTED_UNDERLINE = 1 shl 13
    const val FLAG_DASHED_UNDERLINE = 1 shl 14
    const val FLAG_ANY_UNDERLINE = FLAG_UNDERLINE or FLAG_DOUBLE_UNDERLINE or FLAG_UNDERCURL or
        FLAG_DOTTED_UNDERLINE or FLAG_DASHED_UNDERLINE

    // --- state array (bridge::snapshot::state_index) ---
    const val STATE_LEN = 8
    const val STATE_COLUMNS = 0
    const val STATE_ROWS = 1
    const val STATE_CURSOR_ROW = 2
    const val STATE_CURSOR_COL = 3
    const val STATE_CURSOR_SHAPE = 4
    const val STATE_DISPLAY_OFFSET = 5
    const val STATE_MODE_BITS = 6
    const val STATE_COLORS = 7

    // --- dynamic colour table (ffi::colors) ---
    /** Entries in the engine's colour table (`term::color::COUNT`). */
    const val COLOR_COUNT = 269

    /** `nativeColors` reports this for "not overridden by the application". */
    const val COLOR_UNSET = -1

    /** `NamedColor::Cursor` — a cursor query is only answered when overridden. */
    const val COLOR_INDEX_CURSOR = 258

    // --- terminal modes (bridge::snapshot::mode) ---
    const val MODE_SHOW_CURSOR = 1 shl 0
    const val MODE_APP_CURSOR = 1 shl 1
    const val MODE_APP_KEYPAD = 1 shl 2
    const val MODE_MOUSE_REPORT_CLICK = 1 shl 3
    const val MODE_MOUSE_MOTION = 1 shl 4
    const val MODE_MOUSE_DRAG = 1 shl 5
    const val MODE_SGR_MOUSE = 1 shl 6
    const val MODE_BRACKETED_PASTE = 1 shl 7
    const val MODE_FOCUS_IN_OUT = 1 shl 8
    const val MODE_ALT_SCREEN = 1 shl 9
    const val MODE_INSERT = 1 shl 10
    const val MODE_LINE_WRAP = 1 shl 11
    const val MODE_LINE_FEED_NEW_LINE = 1 shl 12
    const val MODE_ORIGIN = 1 shl 13
    const val MODE_UTF8_MOUSE = 1 shl 14
    const val MODE_ALTERNATE_SCROLL = 1 shl 15

    /** Any mouse-tracking mode is on (`TermMode::MOUSE_MODE`). */
    const val MODE_MOUSE_MODE = MODE_MOUSE_REPORT_CLICK or MODE_MOUSE_MOTION or MODE_MOUSE_DRAG

    const val CURSOR_BLOCK = 0
    const val CURSOR_UNDERLINE = 1
    const val CURSOR_BEAM = 2
    const val CURSOR_HOLLOW = 3
    const val CURSOR_HIDDEN = 4

    // --- damage list (bridge::snapshot) ---
    const val DAMAGE_FULL = -1
    const val DAMAGE_STRIDE = 3

    fun damageLength(rows: Int): Int = 1 + DAMAGE_STRIDE * rows

    // --- font (ffi::font) ---
    const val GLYPH_HEADER_BYTES = 24
    const val FONT_METRICS_LEN = 5
    const val GLYPH_DIRECT = 0
    const val GLYPH_SUBSTITUTED = 1
    const val GLYPH_MISSING = 2

    /** Metrics float array: cell width, cell height, ascent, descent, underline. */
    const val METRIC_CELL_WIDTH = 0
    const val METRIC_CELL_HEIGHT = 1
    const val METRIC_ASCENT = 2
    const val METRIC_DESCENT = 3
    const val METRIC_UNDERLINE = 4

    // --- native methods (ffi::session, ffi::snapshot, ffi::events) ---

    external fun nativeVersion(): String

    external fun nativeCreateSession(
        columns: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
        program: String,
        args: String,
        env: String,
        cwd: String?,
    ): Long

    external fun nativeCreateBridgedSession(
        columns: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
    ): Long

    external fun nativeCloseSession(id: Long): Int

    /** Output bytes for a bridged session (no PTY; the host owns the transport). */
    external fun nativeFeed(id: Long, data: ByteArray): Int

    external fun nativeWrite(id: Long, data: ByteArray): Int

    external fun nativeResize(
        id: Long,
        columns: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
    ): Int

    external fun nativeScroll(id: Long, delta: Int): Int

    external fun nativeSnapshot(id: Long, cells: java.nio.ByteBuffer, damageOut: IntArray): Int

    external fun nativeState(id: Long, stateOut: IntArray): Int

    external fun nativeColors(id: Long, out: IntArray): Int

    external fun nativePollWakeup(id: Long): Boolean

    external fun nativeDrainEvents(id: Long): String

    // --- capabilities (ffi::text, ffi::search, ffi::selection) ---

    /** Strip ANSI escape sequences from `data` (ffi::text). */
    external fun nativeStripAnsi(id: Long, data: ByteArray): String

    /** Find `query` in the visible grid; returns `row:col:len` records (ffi::search). */
    external fun nativeSearch(id: Long, query: String, limit: Int): String

    /** Currently selected text, or "" (ffi::selection). */
    external fun nativeSelectionText(id: Long): String

    /** Select the word under (row, col); returns the selected text (ffi::selection). */
    external fun nativeSelectWord(id: Long, row: Int, col: Int): String

    /** Select the line under (row, col); returns the selected text (ffi::selection). */
    external fun nativeSelectLine(id: Long, row: Int, col: Int): String

    /** Clear any active selection (ffi::selection). */
    external fun nativeClearSelection(id: Long): Int

    // --- font (ffi::font) ---

    external fun nativeInitFont(regular: ByteArray, bold: ByteArray?): Int

    external fun nativeFontMetrics(px: Float, out: FloatArray): Int

    external fun nativeRasterize(
        codepoint: Int,
        bold: Boolean,
        px: Float,
        out: java.nio.ByteBuffer,
    ): Int
}
