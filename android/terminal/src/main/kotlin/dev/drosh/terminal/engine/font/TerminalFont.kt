package dev.drosh.terminal.engine.font

import android.content.Context
import android.graphics.Typeface
import dev.drosh.domain.font.FontStore
import dev.drosh.terminal.R
import java.io.File

/**
 * The terminal's own typeface.
 *
 * The app ships a monospace font with the glyphs a terminal actually needs — the
 * box drawing, the block elements, the Powerline separators and the rest of the
 * private-use set — so a shell prompt that draws a segment, or a tool that prints
 * a tree, draws the character instead of a missing-glyph box. A font the user
 * downloaded from the store replaces it.
 *
 * ## Why one place
 *
 * Two things measure this font and they have to agree: the cell the grid is laid
 * out with (the host's `Paint`) and the mask each glyph is rasterised into (the
 * atlas's `Paint`). A cell measured with one face and a glyph drawn with another
 * is text that does not fit its own advance — which looks like the spacing
 * between the characters growing or shrinking. So both ask here, and the answer
 * is the same object.
 *
 * ## How a downloaded font is found
 *
 * Through [FontStore.ACTIVE_MARKER]: one line, the id of the active font. The
 * marker is read here rather than the settings file because this runs on a draw
 * thread (and in a `Paint`'s initialiser) where parsing a TOML document is not an
 * option, and because a font is a file the process can stat in a microsecond.
 * The marker's modification time is part of the cache key, so switching fonts —
 * which rewrites the marker — is a new face on the next pane, without the
 * renderer having to be told.
 *
 * The bundled face is the fallback for every failure: an unreadable file, an
 * empty marker, a font that Android refuses. A terminal drawn in the wrong face
 * is legible; a terminal that refuses to start is not.
 */
object TerminalFont {

    /** The last face answered, with the marker that produced it. */
    private var cached: Cached? = null

    private data class Cached(val path: String, val stamp: Long, val face: Typeface)

    /**
     * The terminal's typeface.
     */
    fun of(context: Context): Typeface {
        val active = activeFile(context)
        val path = active?.absolutePath ?: ""
        val stamp = active?.lastModified() ?: 0L
        cached?.let { if (it.path == path && it.stamp == stamp) return it.face }
        synchronized(this) {
            cached?.let { if (it.path == path && it.stamp == stamp) return it.face }
            val face = read(active) ?: bundled(context)
            cached = Cached(path, stamp, face)
            return face
        }
    }

    /** The bold face of the same family. */
    fun bold(context: Context): Typeface = Typeface.create(of(context), Typeface.BOLD)

    /**
     * The font's bytes, for the engine's own shaper.
     *
     * The terminal's shaper lives in Rust and never sees the `Typeface` Android
     * gave us; it needs the file the APK ships — or the file the store
     * downloaded, which is the same question with a moved answer. Reading it
     * here keeps the two on the same font, which is the only thing that
     * matters: a shaper that shaped with one face and a rasteriser that drew
     * with another is text that does not fit its own advance.
     */
    fun bytes(context: Context): ByteArray {
        val active = activeFile(context)
        if (active != null) {
            runCatching { return active.readBytes() }
        }
        val res = context.applicationContext.resources
        return res.openRawResource(R.font.jetbrains_mono_nerd).use { it.readBytes() }
    }

    /** Forgets the face it answered, after a change that a stat cannot see. */
    fun invalidate() {
        cached = null
    }

    /**
     * The TTF the marker points at, when it points at one that exists.
     *
     * The marker holds an id, not a path: the app's files directory moves
     * between installs and a stored path would be one that sometimes exists.
     */
    private fun activeFile(context: Context): File? {
        val marker = File(context.applicationContext.filesDir, FontStore.ACTIVE_MARKER)
        val id = runCatching { marker.readText().trim() }.getOrNull().orEmpty()
        if (id.isEmpty()) return null
        val file = File(File(context.applicationContext.filesDir, FontStore.FONTS_DIR), "$id.ttf")
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    private fun read(file: File?): Typeface? {
        val candidate = file ?: return null
        return runCatching { Typeface.createFromFile(candidate) }.getOrNull()
    }

    private fun bundled(context: Context): Typeface =
        // `Resources.getFont` is API 26, which is this app's floor: the font is
        // read without a support library the terminal module does not otherwise
        // need.
        runCatching {
            context.applicationContext.resources.getFont(R.font.jetbrains_mono_nerd)
        }.getOrNull() ?: Typeface.MONOSPACE
}
