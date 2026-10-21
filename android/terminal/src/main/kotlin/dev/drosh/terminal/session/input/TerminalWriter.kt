package dev.drosh.terminal.session.input

/**
 * Where the bytes of a key press go.
 *
 * Two terminals, two ways of writing: the vendored Termux session takes a
 * code point or a VT string, the engine's session takes bytes. The decisions
 * — which key was pressed, which modifiers were held, whether the sticky
 * modifier applies to this key — belong to the app and stay in one place
 * ([InputDispatcher]); only the bytes are the terminal's, and each terminal
 * makes its own.
 *
 * The seam exists so that a caller never has to know which terminal is
 * behind the pane it is typing into. It asks the manager which terminal has
 * the focus and writes to the writer it gets back.
 *
 * A writer is asked for the focused terminal, not for a session: the engine's
 * session is not one of the app's kind, and a writer that took one would
 * quietly do nothing on the engine's path.
 */
interface TerminalWriter {

    /**
     * Type one character.
     *
     * @param char the character as the user typed it.
     * @param mods the modifiers that were held while it was typed.
     */
    fun writeChar(char: Char, mods: InputModifiers)

    /**
     * Press a key that carries a sequence rather than a character.
     *
     * @param keyCode the Android key code of the key.
     * @param mods the modifiers that were held while it was pressed.
     */
    fun writeKey(keyCode: Int, mods: InputModifiers)

    /**
     * Write bytes as they are.
     *
     * For the bytes the caller has already decided on — a control byte for
     * an interrupt, a whole line for a command. The writer does not look at
     * them and does not change them.
     */
    fun writeBytes(bytes: ByteArray)
}
