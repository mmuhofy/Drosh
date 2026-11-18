package dev.drosh.terminal.engine.bench

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * A second-by-second record of what the renderer and the input did.
 *
 * The diagnostics panel is a picture: useful while the phone is in front of
 * you, gone the moment it is locked. A benchmark is a run, and a run has to be
 * kept somewhere the shell can read it — next to the rootfs the terminal
 * already lives in.
 *
 * Every write appends one complete line. A line that is half there is worse
 * than no line: a reader parsing the file must be able to take any line at
 * face value.
 */
object BenchmarkLog {

    private const val NAME = "benchmark.log"

    private val lock = Any()

    @Volatile
    private var current: File? = null

    @Volatile
    private var active = false

    /** Whether a recording is in progress. */
    fun isRecording(): Boolean = active

    /**
     * Where the file is, whether or not a recording is running.
     *
     * The same `.drosh` directory the settings file uses inside the guest, so
     * a shell can read the log where it actually lives rather than in the
     * app's private root. `UbuntuBootstrap` puts the rootfs under `ubuntu/`,
     * which is the part a second copy of the path would get wrong.
     */
    fun path(context: Context): String = File(droshDir(context), NAME).absolutePath

    private fun droshDir(context: Context): File =
        File(File(File(File(context.filesDir, "ubuntu"), "rootfs"), "home"), ".drosh")

    /**
     * Start a new run: the old file is replaced, so a report never mixes two
     * sessions. An empty label is allowed and means "unlabelled".
     */
    @Throws(IOException::class)
    fun start(context: Context, label: String = "") {
        val dir = droshDir(context)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("cannot create ${dir.absolutePath}")
        }
        val file = File(dir, NAME)
        synchronized(lock) {
            file.writeText("")
            file.appendText(
                "# drosh benchmark · ${System.currentTimeMillis()} · $label\n",
            )
            current = file
            active = true
        }
    }

    /** Append one sample line. Safe to call from any thread. */
    fun sample(text: String) {
        if (!active) return
        val line = text.replace('\n', ' ').replace('\r', ' ')
        synchronized(lock) {
            val file = current ?: return
            try {
                file.appendText(line + "\n")
            } catch (error: IOException) {
                // A full disk must not take the render loop down. The panel
                // keeps showing the numbers; only the file stops growing.
                current = null
                active = false
            }
        }
    }

    /** Stop recording but keep the file and the numbers. */
    fun stop() {
        active = false
    }

    /**
     * Write the closing line and stop. Returns a short description of where
     * the run landed, for the panel to show.
     */
    fun finish(): String {
        synchronized(lock) {
            active = false
            val file = current
                ?: return "not recording"
            return try {
                file.appendText("# finished ${System.currentTimeMillis()}\n")
                "${file.name} · ${file.length()} bytes · ${pathFor(file)}"
            } catch (error: IOException) {
                "could not write: ${error.message}"
            }
        }
    }

    private fun pathFor(file: File): String = file.parent ?: file.absolutePath
}
