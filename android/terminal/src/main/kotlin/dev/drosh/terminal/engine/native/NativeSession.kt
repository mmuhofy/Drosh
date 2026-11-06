package dev.drosh.terminal.engine.native

import dev.drosh.terminal.engine.protocol.Frame

/**
 * A session: the terminal, its render state, and the frames it produces.
 *
 * One handle owns all of it. The caller asks for a frame and gets bytes; there
 * is no second question that could be answered from a different moment.
 *
 * ## The load check
 *
 * Every entry point goes through [NativeLibraries], because a missing library
 * is a normal state on a build that has not staged the `.so` — not a crash.
 * The check is a field read after the first call.
 */
object NativeSession {

    /**
     * Creates a session with a `columns` × `rows` terminal.
     *
     * Returns 0 when the library is missing or the terminal could not be made.
     */
    fun create(columns: Int, rows: Int): Long {
        if (!NativeLibraries.ensureLoaded()) return 0
        return nativeCreate(columns, rows)
    }

    /** Destroys a session. Zero is a no-op. */
    fun free(handle: Long) {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return
        nativeFree(handle)
    }

    /** Feeds PTY bytes to the parser. Returns 0 on success. */
    fun write(handle: Long, data: ByteArray, length: Int = data.size): Int {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return -1
        return nativeWrite(handle, data, length)
    }

    /** Resizes the grid and records the cell size. Returns 0 on success. */
    fun resize(handle: Long, columns: Int, rows: Int, cellWidth: Int, cellHeight: Int): Int {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return -1
        return nativeResize(handle, columns, rows, cellWidth, cellHeight)
    }

    /**
     * Produces the next frame, or null when there is nothing to draw.
     *
     * The bytes are a copy: the native side is free to reuse its buffer the
     * moment this returns.
     */
    fun frame(handle: Long): Frame? {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return null
        return Frame.parse(nativeFrame(handle))
    }

    /** Scrolls the viewport. `tag` is one of the `SCROLL_*` constants. */
    fun scroll(handle: Long, tag: Int, amount: Int): Int {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return -1
        return nativeScroll(handle, tag, amount)
    }

    // --- scroll viewport tags (GhosttyTerminalScrollViewportTag) ----------

    const val SCROLL_TOP = 0
    const val SCROLL_BOTTOM = 1

    /** Scroll by `amount` rows; negative is up. */
    const val SCROLL_DELTA = 2

    /** Scroll to the absolute row `amount`. */
    const val SCROLL_ROW = 3

    private external fun nativeCreate(columns: Int, rows: Int): Long
    private external fun nativeFree(handle: Long)
    private external fun nativeWrite(handle: Long, data: ByteArray, length: Int): Int
    private external fun nativeResize(
        handle: Long,
        columns: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
    ): Int
    private external fun nativeFrame(handle: Long): ByteArray?
    private external fun nativeScroll(handle: Long, tag: Int, amount: Int): Int
}
