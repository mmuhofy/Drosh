package dev.drosh.terminal.engine.font

import android.content.Context
import android.graphics.Typeface
import dev.drosh.terminal.R

/**
 * The terminal's own typeface.
 *
 * The app ships a monospace font with the glyphs a terminal actually needs —
 * the box drawing, the block elements, the Powerline separators and the rest
 * of the private-use set — so a shell prompt that draws a segment, or a tool
 * that prints a tree, draws the character instead of a missing-glyph box.
 *
 * ## Why one place
 *
 * Two things measure this font and they have to agree: the cell the grid is
 * laid out with (the host's `Paint`) and the mask each glyph is rasterised
 * into (the atlas's `Paint`). A cell measured with one face and a glyph drawn
 * with another is text that does not fit its own advance — which looks like
 * the spacing between the characters growing or shrinking. So both ask here,
 * and the answer is the same object.
 */
object TerminalFont {

    @Volatile
    private var cached: Typeface? = null

    /**
     * The terminal's typeface.
     *
     * Falls back to the platform's monospace when the bundled font cannot be
     * read: a terminal drawn in the wrong face is legible, and a terminal that
     * refuses to start is not.
     */
    fun of(context: Context): Typeface {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            // `Resources.getFont` is API 26, which is this app's floor: the
            // font is read without a support library the terminal module does
            // not otherwise need.
            val bundled = runCatching {
                context.applicationContext.resources.getFont(R.font.jetbrains_mono_nerd)
            }.getOrNull()
            val chosen = bundled ?: Typeface.MONOSPACE
            cached = chosen
            return chosen
        }
    }

    /** The bold face of the same family. */
    fun bold(context: Context): Typeface = Typeface.create(of(context), Typeface.BOLD)

    /**
     * The font's bytes, for the engine's own shaper.
     *
     * The terminal's shaper lives in Rust and never sees the `Typeface`
     * Android gave us; it needs the file the APK ships. Reading it here keeps
     * the two on the same font, which is the only thing that matters: a
     * shaper that shaped with one face and a rasteriser that drew with another
     * is text that does not fit its own advance.
     */
    fun bytes(context: Context): ByteArray {
        val res = context.applicationContext.resources
        return res.openRawResource(R.font.jetbrains_mono_nerd).use { it.readBytes() }
    }
}