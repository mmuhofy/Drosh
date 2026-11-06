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

    private external fun nativeSelfTest(): String
}
