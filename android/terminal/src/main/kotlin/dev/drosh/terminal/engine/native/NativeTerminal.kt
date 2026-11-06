package dev.drosh.terminal.engine.native

/**
 * The terminal handle, as the native library sees it.
 *
 * A handle is an opaque `long`: a pointer on aarch64, valid until
 * [nativeFree]. It is never inspected on this side — the only things done with
 * it are passing it back and comparing it to zero.
 */
object NativeTerminal {

    /** Creates a `columns` × `rows` terminal; 0 when it could not be created. */
    external fun nativeCreate(columns: Int, rows: Int): Long

    /** Destroys a terminal. Zero is a no-op, so a double free is safe. */
    external fun nativeFree(handle: Long)

    /** Feeds PTY bytes to the parser. Returns 0 on success. */
    external fun nativeWrite(handle: Long, data: ByteArray, length: Int): Int

    /** Resizes the grid and records the cell size. Returns 0 on success. */
    external fun nativeResize(
        handle: Long,
        columns: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
    ): Int

    /** Reads a terminal field; the field ids are `GhosttyTerminalData`. */
    external fun nativeGet(handle: Long, field: Int): Long

    /**
     * Scrolls the viewport.
     *
     * [tag] is one of the `SCROLL_*` constants; [amount] is the row delta for
     * [SCROLL_DELTA] and the absolute row for [SCROLL_ROW].
     */
    external fun nativeScroll(handle: Long, tag: Int, amount: Int): Int

    // --- field ids (GhosttyTerminalData) ---------------------------------

    const val DATA_COLS = 1
    const val DATA_ROWS = 2
    const val DATA_CURSOR_X = 3
    const val DATA_CURSOR_Y = 4
    const val DATA_SCROLLBAR = 11

    // --- scroll viewport tags (GhosttyTerminalScrollViewportTag) ----------

    /** Scroll to the top of the scrollback. */
    const val SCROLL_TOP = 0

    /** Scroll to the bottom (the active area). */
    const val SCROLL_BOTTOM = 1

    /** Scroll by [amount] rows; negative is up. */
    const val SCROLL_DELTA = 2

    /** Scroll to the absolute row [amount]. */
    const val SCROLL_ROW = 3
}
