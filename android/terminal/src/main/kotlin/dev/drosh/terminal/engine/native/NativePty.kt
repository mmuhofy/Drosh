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

    /**
     * The child's process id, or -1 when there is none.
     *
     * The session's default name is the child's own name, and that is read
     * from `/proc/<pid>/comm` — which needs the pid, and only the PTY knows
     * it.
     */
    fun pid(handle: Long): Int {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return -1
        return nativePid(handle)
    }

    /**
     * The process group in the terminal's foreground, or -1 when there is none.
     *
     * This is what Ctrl+C would signal: for a shell it is the job that owns the
     * terminal right now, and for an idle prompt it is the shell's own group.
     * It is the one signal that says "a command is running here" without the
     * shell having to report anything.
     */
    fun foregroundPid(handle: Long): Int {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return -1
        return nativeForegroundPid(handle)
    }

    /**
     * The slave side's path, e.g. `/dev/pts/3`, or null when it cannot be read.
     *
     * A process the user asks about names this to say where it is attached.
     */
    fun ttyName(handle: Long): String? {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return null
        return nativeTtyName(handle)
    }

    /**
     * The terminal's local modes, packed: bit 0 canonical, bit 1 echo, or -1.
     *
     * A password prompt clears echo; a full-screen program clears canonical
     * mode. The UI reads the two to mirror what the program is asking for.
     */
    fun mode(handle: Long): Int {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return -1
        return nativeMode(handle)
    }

    /**
     * The size the kernel currently has, `[rows, cols, xpixel, ypixel]`, or null.
     *
     * Not the size this side last set: the kernel is the record, and it is what
     * the child's own `TIOCGWINSZ` would answer.
     */
    fun size(handle: Long): IntArray? {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return null
        return nativeSize(handle)
    }

    /**
     * Waits up to [timeoutMs] for the child to write or close its side, or — when
     * [writable] — for its input buffer to drain.
     *
     * A blocking wait on the kernel's own `poll`: the reader thread uses it
     * instead of sleeping in a loop, and the child closing its side is one of
     * the events it wakes on, so a hangup is seen at once instead of on the next
     * tick. Returns 1 when the master is ready, 0 on timeout or a bare wake, and
     * -1 on a bad handle or an error.
     */
    fun poll(handle: Long, timeoutMs: Int, writable: Boolean = false): Int {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return -1
        return nativePoll(handle, timeoutMs, writable)
    }

    /**
     * Writes as much of [data] as the kernel takes without blocking.
     *
     * Returns the bytes written, 0 when the input buffer is full, or -1 on a bad
     * handle or an error. The caller keeps the rest and retries when the PTY is
     * writable again, so a full paste never stops the reader from draining the
     * child's output — which is what frees the buffer.
     */
    fun writeSome(handle: Long, data: ByteArray, offset: Int, length: Int): Int {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return -1
        return nativeWriteSome(handle, data, offset, length)
    }

    /**
     * Pokes the reader awake so it drains a queued write now rather than at the
     * end of its wait. False when the PTY has no wake channel.
     */
    fun wake(handle: Long): Boolean {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return false
        return nativeWake(handle)
    }

    /**
     * True when the PTY has a wake channel.
     *
     * Without one a queued write would wait out the reader's poll, so the
     * caller writes straight through instead.
     */
    fun hasWake(handle: Long): Boolean {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return false
        return nativeHasWake(handle)
    }

    /** The canonical-mode bit of [mode]: the line discipline collects a line. */
    const val MODE_CANONICAL = 1

    /** The echo bit of [mode]: the terminal writes typed input back. */
    const val MODE_ECHO = 2

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
    private external fun nativePid(handle: Long): Int
    private external fun nativeForegroundPid(handle: Long): Int
    private external fun nativeTtyName(handle: Long): String?
    private external fun nativeMode(handle: Long): Int
    private external fun nativeSize(handle: Long): IntArray?
    private external fun nativePoll(handle: Long, timeoutMs: Int, writable: Boolean): Int
    private external fun nativeWriteSome(handle: Long, data: ByteArray, offset: Int, length: Int): Int
    private external fun nativeWake(handle: Long): Boolean
    private external fun nativeHasWake(handle: Long): Boolean
}
