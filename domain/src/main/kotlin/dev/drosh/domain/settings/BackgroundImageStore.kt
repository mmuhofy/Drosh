package dev.drosh.domain.settings

import android.net.Uri
import java.io.File

/**
 * The background image, by convention rather than by path.
 *
 * A file called `background.<ext>` in `.drosh/` *is* the background — no
 * setting to point at, no path to type, and nothing for a user to get
 * wrong. The picker exists to put a file there, not to configure one: it
 * copies the chosen document into the convention and the convention takes
 * over from there. The same file is what a shell or the agent sees, so
 * `cp ~/Pictures/x.png ~/.drosh/background.png` works exactly like the
 * picker.
 *
 * Blur is applied once, to a cached file, rather than per frame: a blur is
 * an expensive thing to ask a phone to redo sixty times a second behind a
 * terminal.
 */
interface BackgroundImageStore {

    /** The convention file's name, or null when there is none. */
    fun currentFileName(): String?

    /**
     * Copies a picked document into the convention, keeping its extension.
     * Returns the stored file name.
     */
    suspend fun import(uri: Uri): Result<String>

    /** Removes the convention file, if there is one. */
    suspend fun remove(): Result<Unit>

    /**
     * The file the terminal should draw — the blurred cache when
     * [blurRadius] asks for one, the source otherwise. Generates the cache
     * when it is missing or stale.
     */
    suspend fun displayFile(blurRadius: Int): File?
}
