package dev.drosh.terminal.engine.native

/**
 * Keyboard input: Android's key codes in, escape sequences out.
 *
 * ## Why the engine encodes
 *
 * A terminal's escape sequences depend on state the terminal owns: whether the
 * application asked for cursor keys in `DECCKM` form (`ESC O A` rather than
 * `ESC [ A`), whether the kitty keyboard protocol is on, whether a modifier is
 * a parameter or a prefix. The previous engine reimplemented those rules in
 * Kotlin — read out of alacritty's source, correctly, but still a copy — and a
 * copy is a place to be wrong.
 *
 * `libghostty-vt` owns the rules. This asks it.
 *
 * ## What goes where
 *
 * [encode] returns the bytes for one key event, or an empty array when the key
 * produced nothing. The caller writes them to the PTY. Nothing about a key is
 * remembered here: the encoder is the session's, and the session knows its own
 * modes.
 */
object NativeInput {

    /** Android's key actions, matching `KeyEvent.ACTION_*`. */
    const val ACTION_PRESS = 0
    const val ACTION_RELEASE = 1
    const val ACTION_REPEAT = 2

    /**
     * Encode one key event.
     *
     * @param keyCode Android's `KeyEvent.keyCode`
     * @param metaState Android's `KeyEvent.metaState`
     * @param action one of the `ACTION_*` constants
     * @param utf8 the character the key produced, when it produced one
     * @param out a buffer the bytes are written into
     * @return the number of bytes written, 0 when the key produced nothing,
     *         or a negative code
     */
    fun encode(
        handle: Long,
        keyCode: Int,
        metaState: Int,
        action: Int,
        utf8: String,
        out: ByteArray,
    ): Int {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return -1
        return nativeEncodeKey(handle, keyCode, metaState, action, utf8, out)
    }

    private external fun nativeEncodeKey(
        handle: Long,
        keyCode: Int,
        metaState: Int,
        action: Int,
        utf8: String,
        out: ByteArray,
    ): Int
}
