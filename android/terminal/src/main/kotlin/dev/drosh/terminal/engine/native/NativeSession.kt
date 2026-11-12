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
    data class Scrollbar(
        /** Total scrollable rows: the history plus the viewport. */
        val total: Int,
        /** Where the viewport sits, in that row space. */
        val offset: Int,
        /** How many rows the viewport shows. */
        val len: Int,
    ) {
        /** The rows of history above the live edge. */
        val historyRows: Int get() = maxOf(total - len, 0)
    }

    /**
     * Hand the theme's colours to the engine.
     *
     * A cell with no colour of its own is painted in the engine's two defaults,
     * and a palette index is resolved through the engine's palette. Left unset
     * they are the library's own — black on white — so a light theme drew on
     * the library's black and SGR 7 produced white on white.
     *
     * @param foreground packed 0xRRGGBB.
     * @param background packed 0xRRGGBB.
     * @param palette 256 entries, packed the same way.
     */
    fun setColors(handle: Long, foreground: Int, background: Int, palette: IntArray): Boolean {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return false
        return nativeSetColors(handle, foreground, background, palette) != 0.toByte()
    }

    /**
     * The default cursor's shape and whether it blinks.
     *
     * A program can still change its cursor with DECSCUSR; this is what the
     * terminal falls back to, which is the setting the user chose.
     */
    fun setCursor(handle: Long, style: Int, blink: Boolean): Boolean {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return false
        return nativeSetCursor(handle, style, if (blink) 1 else 0) != 0.toByte()
    }

    /**
     * Scroll to the prompt above or below the viewport.
     *
     * The engine needs the shell's semantic prompt markers, which a shell that
     * emits them (OSC 133) provides; without them there is no prompt to go to
     * and the call is a no-op.
     */
    fun scrollToPrompt(handle: Long, delta: Int): Boolean {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return false
        return nativeScroll(handle, SCROLL_PROMPT, delta) == 0
    }

    /**
     * Highlight the range the search matched.
     *
     * The range is in viewport rows: the find bar scrolls the viewport to the
     * match, and a range in the scrollback's coordinates would be a different
     * place by the time it is drawn. A length of zero clears the highlight.
     */
    fun setHighlight(handle: Long, row: Int, column: Int, length: Int): Boolean {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return false
        return nativeSetHighlight(handle, row, column, length) != 0.toByte()
    }

    /** The title the program asked for with OSC 0/2, or null when it set none. */
    fun title(handle: Long): String? {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return null
        return nativeTitle(handle)?.takeIf { it.isNotBlank() }
    }

    fun scrollbar(handle: Long): Scrollbar? {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return null
        val values = nativeScrollbar(handle)
        if (values.size < 3) return null
        return Scrollbar(total = values[0], offset = values[1], len = values[2])
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

    /**
     * The session's text, as the engine holds it.
     *
     * The grid and the scrollback, formatted the way the engine formats them.
     * It does not go through the render state, so it can be read while the
     * render thread is drawing — which is what the preview ticker does.
     */
    fun transcript(handle: Long): String {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return ""
        return nativeTranscript(handle)
    }

    /**
     * The colours of a region of the grid, as `(foreground, background)` pairs.
     *
     * The values are the frame's own packed colours — red in the low byte —
     * so a caller reads them with [FrameFormat.red] and friends.
     */
    fun cellColors(
        handle: Long,
        firstRow: Int,
        firstColumn: Int,
        rows: Int,
        columns: Int,
    ): IntArray {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return IntArray(0)
        return nativeCellColors(handle, firstRow, firstColumn, rows, columns)
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

    /**
     * The URI of the hyperlink the cell at a viewport position belongs to, or
     * null when the cell carries none.
     *
     * The engine resolves the cell and reads its `OSC 8` link, so a link that
     * runs across several cells answers the same for each of them, and the
     * bounds of the run stay the engine's business. Null is the common case: a
     * cell with no link is not an error, it is an answer.
     */
    fun hyperlink(handle: Long, row: Int, column: Int): String? {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return null
        return nativeHyperlink(handle, row, column)
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

    /** Scroll by prompts: negative for the one above, positive for the one below. */
    const val SCROLL_PROMPT = 4

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
    private external fun nativeSetColors(
        handle: Long,
        foreground: Int,
        background: Int,
        palette: IntArray,
    ): Byte
    private external fun nativeTitle(handle: Long): String?
    private external fun nativeSetCursor(handle: Long, style: Int, blink: Int): Byte
    private external fun nativeSetHighlight(
        handle: Long,
        row: Int,
        column: Int,
        length: Int,
    ): Byte
    private external fun nativeAlternateScreen(handle: Long): Boolean
    private external fun nativeModes(handle: Long): Int
    private external fun nativeTranscript(handle: Long): String
    private external fun nativeCellColors(
        handle: Long,
        firstRow: Int,
        firstColumn: Int,
        rows: Int,
        columns: Int,
    ): IntArray
    private external fun nativeSearch(handle: Long, query: String, limit: Int): IntArray
    private external fun nativeHyperlink(handle: Long, row: Int, column: Int): String?
    private external fun nativeShellPhase(handle: Long): Int
    private external fun nativeWorkingDirectory(handle: Long): String
}
