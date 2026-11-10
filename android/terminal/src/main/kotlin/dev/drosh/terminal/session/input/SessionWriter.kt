package dev.drosh.terminal.session.input

import android.view.KeyEvent
import dev.drosh.terminal.engine.native.NativeInput

/**
 * The writer for a session on the current engine.
 *
 * ## Why the engine makes the bytes
 *
 * The engine owns the encoding: the bytes for a key are produced by the
 * engine, never by the app. So the ctrl and alt encodings here are the
 * engine's and not a second opinion of them — the app says *what* was pressed
 * and *which* modifiers were held, and the engine says what that means in
 * bytes.
 *
 * The cursor keys follow the terminal's own DECCKM state: an application that
 * asked for the `ESC O` forms — vim does — gets them, and one that did not
 * gets the `ESC [` forms. That state lives in the engine, which is another
 * reason the encoding happens there.
 */
class SessionWriter(
    /** The pane the bytes go to. */
    private val pane: dev.drosh.terminal.session.TerminalEngine,
    /** The session's engine handle: the encoder reads the terminal's state. */
    private val handle: Long,
) : TerminalWriter {

    override fun writeChar(char: Char, mods: InputModifiers) {
        // The character as the keyboard produced it: the engine turns it into
        // bytes, with the modifiers it was held with.
        val out = ByteArray(MAX_KEY_BYTES)
        val written = NativeInput.encode(
            handle,
            KeyEvent.KEYCODE_UNKNOWN,
            metaStateOf(mods),
            NativeInput.ACTION_PRESS,
            char.toString(),
            out,
        )
        if (written > 0) pane.write(out.copyOf(written))
    }

    override fun writeKey(keyCode: Int, mods: InputModifiers) {
        val out = ByteArray(MAX_KEY_BYTES)
        val written = NativeInput.encode(
            handle,
            keyCode,
            metaStateOf(mods),
            NativeInput.ACTION_PRESS,
            "",
            out,
        )
        if (written > 0) pane.write(out.copyOf(written))
    }

    override fun writeBytes(bytes: ByteArray) {
        pane.write(bytes)
    }

    /**
     * The modifiers as Android's meta state.
     *
     * The sticky ctrl and alt of the extra-keys row are the same modifiers
     * Android's own meta state carries; there is one vocabulary, and the
     * engine reads it.
     */
    private fun metaStateOf(mods: InputModifiers): Int {
        var state = 0
        if (mods.ctrl) state = state or KeyEvent.META_CTRL_ON
        if (mods.alt) state = state or KeyEvent.META_ALT_ON
        return state
    }

    private companion object {
        /** The longest byte sequence a key can produce. */
        const val MAX_KEY_BYTES = 32
    }
}
