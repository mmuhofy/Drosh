package dev.drosh.terminal.engine.native

/**
 * The self-test that decides whether the whole migration is possible.
 *
 * `libghostty-vt` is somebody else's binary, built for an ABI we ship and
 * downloaded at build time. Before a renderer, a session or an input path is
 * written against it, it has to prove — on the device, with the exact bytes
 * the APK carries — that it can be created, fed a stream, and read back as
 * cells.
 *
 * The test returns a report rather than a boolean. A failure has to say which
 * step failed, because on a device the screen may be the only channel out.
 */
object NativeSmoke {

    /**
     * Run the pipeline and return the report.
     *
     * The report is multi-line and ends with `PASS` or a `FAIL:` line naming
     * the step that broke.
     */
    fun selfTest(): String {
        if (!NativeLibraries.ensureLoaded()) {
            return "FAIL: the native libraries did not load\n" +
                "reason: ${NativeLibraries.failure ?: "unknown"}"
        }
        return nativeSelfTest()
    }

    /**
     * A second check, on the frame path rather than the raw cell path.
     *
     * The self-test proves the engine works; this proves the *frame* the
     * renderer will actually draw is well-formed. A renderer that draws a
     * broken frame is harder to diagnose than one that draws nothing.
     */
    fun frameTest(): String {
        if (!NativeLibraries.ensureLoaded()) {
            return "FAIL: the native libraries did not load"
        }

        val handle = NativeSession.create(columns = 20, rows = 4)
        if (handle == 0L) {
            return "FAIL: the session could not be created"
        }

        return try {
            NativeSession.write(handle, "hi".toByteArray())
            val frame = NativeSession.frame(handle)
                ?: return "FAIL: no frame was produced"

            val first = frame.codepoint(0).toChar()
            val second = frame.codepoint(1).toChar()
            buildString {
                append("grid: ").append(frame.columns).append('x').append(frame.rows).append('\n')
                append("cells: '").append(first).append("' '").append(second).append("'\n")
                append(if (first == 'h' && second == 'i') "PASS" else "FAIL: expected 'h','i'")
            }
        } finally {
            NativeSession.free(handle)
        }
    }

    private external fun nativeSelfTest(): String
}
