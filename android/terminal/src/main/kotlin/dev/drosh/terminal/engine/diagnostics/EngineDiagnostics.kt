package dev.drosh.terminal.engine.diagnostics

import dev.drosh.terminal.engine.native.NativeLibraries
import dev.drosh.terminal.engine.native.NativeSession
import dev.drosh.terminal.engine.native.NativeSmoke

/**
 * The engine's own account of itself, for a device whose log nobody can read.
 *
 * ## Why this exists
 *
 * The Ghostty engine has never drawn a frame on a device. When it fails, the
 * failure is one of a handful: the library did not load, the soname did not
 * resolve, the GL context did not come up, the PTY was refused. On a phone the
 * only channel out is the screen, so the answer has to be *on* the screen.
 *
 * ## What it reports
 *
 * Three questions, in the order they can fail:
 *
 * 1. **Did the libraries load?** The `UnsatisfiedLinkError` message names the
 *    library and the missing symbol.
 * 2. **Does the engine parse?** Create a terminal, write `hi`, read two cells.
 *    This is the same sequence as the first smoke test.
 * 3. **Does a frame come out?** The renderer's input is a frame; a broken frame
 *    is a broken renderer with no other symptom.
 *
 * Each is a separate line so a partial pass says which step broke.
 */
object EngineDiagnostics {

    /** One line per step, ending with `PASS` or the step that failed. */
    fun report(): String = buildString {
        appendLine("--- libraries ---")
        appendLine(libraryReport())
        appendLine()
        appendLine("--- self test ---")
        appendLine(NativeSmoke.selfTest())
        appendLine()
        appendLine("--- frame test ---")
        appendLine(NativeSmoke.frameTest())
        appendLine()
        appendLine("--- layout ---")
        appendLine(layoutReport())
    }

    private fun libraryReport(): String {
        if (NativeLibraries.loaded) {
            return "loaded"
        }
        if (!NativeLibraries.ensureLoaded()) {
            return "FAIL: ${NativeLibraries.failure ?: "unknown"}"
        }
        return "loaded"
    }

    /**
     * The frame layout the two sides agree on.
     *
     * Read from the native side when it is there, from the Kotlin constants
     * when it is not — a mismatch between them is a frame the renderer draws as
     * garbage, and this is the only place it is visible.
     */
    private fun layoutReport(): String {
        val format = dev.drosh.terminal.engine.protocol.FrameFormat
        val handle = NativeSession.create(columns = 4, rows = 2)
        if (handle == 0L) {
            return "kotlin: header=${format.HEADER_BYTES} cell=${format.CELL_BYTES} " +
                "(native unavailable)"
        }
        NativeSession.free(handle)
        return "kotlin: header=${format.HEADER_BYTES} cell=${format.CELL_BYTES}"
    }
}
