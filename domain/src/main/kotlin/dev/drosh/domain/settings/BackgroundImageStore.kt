package dev.drosh.domain.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
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

    /**
     * The file the terminal should draw right now, or null for none.
     *
     * A flow rather than a function because an import happens somewhere else
     * entirely — the settings screen's picker — and the terminal has to hear
     * about it. Blur changes arrive through [applyBlur]; imports and removals
     * update it themselves.
     */
    val imageFile: StateFlow<File?>

    /** The convention file's name, or null when there is none. */
    fun currentFileName(): String?

    /**
     * Copies a picked document into the convention, keeping its extension.
     * [uri] is the document's string form — the domain layer is pure Kotlin,
     * and a string is all the implementation needs to parse it back.
     * Returns the stored file name.
     */
    suspend fun import(uri: String): Result<String>

    /** Removes the convention file, if there is one. */
    suspend fun remove(): Result<Unit>

    /**
     * Re-resolves the file for a new blur radius, generating the cache when
     * the radius asks for one and there is an image to blur.
     */
    suspend fun applyBlur(radius: Int)
}
