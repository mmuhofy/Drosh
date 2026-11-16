package dev.drosh.terminal.engine.native

/**
 * The engine's own search, bound to one terminal session.
 *
 * The search handle lives inside the native session and is created on first
 * use; [free] releases it again when the find bar closes. Every entry point
 * goes through [NativeLibraries], because a missing library is a normal state
 * on a build that has not staged the `.so`.
 */
object NativeSearch {

    /** Status: more progress is possible without touching the terminal. */
    const val STATUS_RUNNING = 0
    /** Status: blocked until [feed]. */
    const val STATUS_FEED_REQUIRED = 1
    /** Status: caught up with the terminal as of the last feed. */
    const val STATUS_COMPLETE = 2

    /** Scroll policy: scroll only when the match is not already visible. */
    const val SCROLL_IF_NEEDED = 0
    /** Scroll policy: never scroll. */
    const val SCROLL_NONE = 1

    fun setNeedle(handle: Long, query: String): Boolean {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return false
        return nativeSetNeedle(handle, query) != 0.toByte()
    }

    fun feed(handle: Long): Boolean {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return false
        return nativeFeed(handle) != 0.toByte()
    }

    fun tick(handle: Long): Int {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return STATUS_COMPLETE
        return nativeTick(handle)
    }

    fun run(handle: Long): Boolean {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return false
        return nativeRun(handle) != 0.toByte()
    }

    fun selectNext(handle: Long): Boolean {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return false
        return nativeSelectNext(handle) != 0.toByte()
    }

    fun selectPrev(handle: Long): Boolean {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return false
        return nativeSelectPrev(handle) != 0.toByte()
    }

    fun setSelectScroll(handle: Long, policy: Int): Boolean {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return false
        return nativeSetSelectScroll(handle, policy) != 0.toByte()
    }

    fun totalMatches(handle: Long): Int {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return 0
        return nativeTotalMatches(handle)
    }

    /** -1 when nothing is selected. */
    fun selectedIndex(handle: Long): Int {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return -1
        return nativeSelectedIndex(handle)
    }

    /** Viewport row, column, length of the selected match, or empty. */
    fun selectedMatch(handle: Long): IntArray {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return IntArray(0)
        return nativeSelectedMatch(handle)
    }

    fun selectedMatchText(handle: Long): String? {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return null
        return nativeSelectedMatchText(handle)
    }

    /**
     * The matches on the screen, as `(row, column, length)` triples.
     *
     * The engine's own list of the matches on the pages that cover the
     * viewport, in viewport coordinates. These are the matches the find bar
     * draws in the weaker colour; the one the user is on is drawn in its own.
     */
    fun viewportMatches(handle: Long): List<Triple<Int, Int, Int>> {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return emptyList()
        val flat = nativeViewportMatches(handle)
        val matches = ArrayList<Triple<Int, Int, Int>>(flat.size / 3)
        var index = 0
        while (index + 2 < flat.size) {
            matches.add(Triple(flat[index], flat[index + 1], flat[index + 2]))
            index += 3
        }
        return matches
    }

    fun free(handle: Long) {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return
        nativeFree(handle)
    }

    private external fun nativeSetNeedle(handle: Long, query: String): Byte
    private external fun nativeFeed(handle: Long): Byte
    private external fun nativeTick(handle: Long): Int
    private external fun nativeRun(handle: Long): Byte
    private external fun nativeSelectNext(handle: Long): Byte
    private external fun nativeSelectPrev(handle: Long): Byte
    private external fun nativeSetSelectScroll(handle: Long, policy: Int): Byte
    private external fun nativeTotalMatches(handle: Long): Int
    private external fun nativeSelectedIndex(handle: Long): Int
    private external fun nativeSelectedMatch(handle: Long): IntArray
    private external fun nativeSelectedMatchText(handle: Long): String?
    private external fun nativeViewportMatches(handle: Long): IntArray
    private external fun nativeFree(handle: Long)
}