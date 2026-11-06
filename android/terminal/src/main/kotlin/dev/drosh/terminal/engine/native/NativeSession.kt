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

    /**
     * The shell's phase, as its `OSC 133` marks report it.
     *
     * 0 unknown, 1 prompt, 2 typing, 3 running, 4 finished. A shell that has
     * not opted in stays at 0, and the UI must not assume otherwise.
     */
    fun shellPhase(handle: Long): Int {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return -1
        return nativeShellPhase(handle)
    }

    /** The working directory the shell last reported, or "". */
    fun workingDirectory(handle: Long): String {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return ""
        return nativeWorkingDirectory(handle)
    }

    /**
     * The scrollbar's position: `(history rows, viewport offset)`.
     *
     * The engine reports both as one struct; splitting it here keeps the caller
     * from needing to know that.
     */
    fun scrollbar(handle: Long): Pair<Int, Int>? {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return null
        val values = nativeScrollbar(handle)
        if (values.size < 2) return null
        return values[0] to values[1]
    }

    /** True when the alternate screen is on: a program, not a shell. */
    fun alternateScreen(handle: Long): Boolean {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return false
        return nativeAlternateScreen(handle)
    }

    /** The engine's mode bits. */
    fun modes(handle: Long): Int {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return 0
        return nativeModes(handle)
    }

    /** Search the grid; results are `(row, column, length)` triples. */
    fun search(handle: Long, query: String, limit: Int): List<Triple<Int, Int, Int>> {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return emptyList()
        val raw = nativeSearch(handle, query, limit)
        val out = ArrayList<Triple<Int, Int, Int>>(raw.size / 3)
        var index = 0
        while (index + 2 < raw.size) {
            out.add(Triple(raw[index], raw[index + 1], raw[index + 2]))
            index += 3
        }
        return out
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
    private external fun nativeScrollbar(handle: Long): IntArray
    private external fun nativeAlternateScreen(handle: Long): Boolean
    private external fun nativeModes(handle: Long): Int
    private external fun nativeSearch(handle: Long, query: String, limit: Int): IntArray
    private external fun nativeShellPhase(handle: Long): Int
    private external fun nativeWorkingDirectory(handle: Long): String
}
