package dev.drosh.terminal.engine.native

/**
 * The process on the far end of the pseudoterminal.
 *
 * A terminal is two things: a grid that parses bytes, and a process that
 * produces them. This is the second half. It is separate from [NativeSession]
 * because the two fail for different reasons — a PTY that will not open is a
 * rootfs or a permissions problem, a grid that will not draw is a render
 * problem — and a diagnostic that names the wrong half is worse than none.
 *
 * ## Reading
 *
 * [read] returns 0 when there is nothing to read. That is the normal answer
 * between keystrokes and must not be treated as an error or as an end of
 * stream; the child's exit is [tryWait].
 */
object NativePty {

    /**
     * Spawn a child on a new pseudoterminal.
     *
     * `argv` and `environment` are lists; they are joined with newlines for the
     * crossing, which is the same shape the previous engine used, so the guest
     * launcher's arguments do not change.
     *
     * Returns 0 when the PTY could not be opened.
     */
    fun spawn(
        argv: List<String>,
        environment: Map<String, String>,
        cwd: String,
        columns: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
    ): Long {
        if (!NativeLibraries.ensureLoaded() || argv.isEmpty()) return 0
        return nativeSpawn(
            argv.joinToString("\n"),
            environment.entries.joinToString("\n") { (key, value) -> "$key=$value" },
            cwd,
            columns,
            rows,
            cellWidth,
            cellHeight,
        )
    }

    /** Close the PTY and reap the child. Zero is a no-op. */
    fun close(handle: Long) {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return
        nativeClose(handle)
    }

    /**
     * Read into [out], returning how many bytes were written.
     *
     * Zero means "nothing right now", not "end of stream".
     */
    fun read(handle: Long, out: ByteArray, capacity: Int = out.size): Int {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return -1
        return nativeRead(handle, out, capacity)
    }

    /** Write to the child. Returns 0 on success. */
    fun write(handle: Long, data: ByteArray, length: Int = data.size): Int {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return -1
        return nativeWrite(handle, data, length)
    }

    /** Tell the kernel the terminal changed size. */
    fun resize(handle: Long, columns: Int, rows: Int, cellWidth: Int, cellHeight: Int): Int {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return -1
        return nativeResize(handle, columns, rows, cellWidth, cellHeight)
    }

    /** The child's exit status, or -1 while it is still running. */
    fun tryWait(handle: Long): Int {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return -1
        return nativeTryWait(handle)
    }

    private external fun nativeSpawn(
        argv: String,
        environment: String,
        cwd: String,
        columns: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
    ): Long

    private external fun nativeClose(handle: Long)
    private external fun nativeRead(handle: Long, out: ByteArray, capacity: Int): Int
    private external fun nativeWrite(handle: Long, data: ByteArray, length: Int): Int
    private external fun nativeResize(
        handle: Long,
        columns: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
    ): Int
    private external fun nativeTryWait(handle: Long): Int
}
