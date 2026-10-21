package dev.drosh.terminal.session.input

import dev.drosh.terminal.engine.core.NativeCore
import dev.drosh.terminal.engine.input.AndroidKeys
import dev.drosh.terminal.engine.input.Encoder
import dev.drosh.terminal.session.EnginePane

/**
 * The Rust engine's writer.
 *
 * The engine owns its own encoder: the bytes for a key are produced by
 * [Encoder], never by the app. So the ctrl and alt encodings here are the
 * engine's and not a second opinion of them — the app says *what* was
 * pressed and *which* modifiers were held, and the encoder says what that
 * means in bytes.
 *
 * The cursor keys follow the terminal's own DECCKM state: an application
 * that asked for the `ESC O` forms — vim does — gets them, and one that did
 * not gets the `ESC [` forms.
 */
class EngineTerminalWriter(private val pane: EnginePane) : TerminalWriter {

    override fun writeChar(char: Char, mods: InputModifiers) {
        val bytes = Encoder.text(char.toString(), encoderMods(mods))
        pane.session.write(bytes)
    }

    override fun writeKey(keyCode: Int, mods: InputModifiers) {
        val named = AndroidKeys.named(keyCode) ?: return
        val bytes = Encoder.sequence(named, encoderMods(mods), appCursor()) ?: return
        pane.session.write(bytes)
    }

    override fun writeBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) {
            return
        }
        pane.session.write(bytes)
    }

    /** Did the application ask for the `ESC O` cursor keys? */
    private fun appCursor(): Boolean =
        pane.modes() and NativeCore.MODE_APP_CURSOR != 0

    /** The engine's modifier set. Shift is not one of them: the row has no shift key. */
    private fun encoderMods(mods: InputModifiers): Encoder.Mods = Encoder.Mods(
        ctrl = mods.ctrl,
        alt = mods.alt,
    )
}
