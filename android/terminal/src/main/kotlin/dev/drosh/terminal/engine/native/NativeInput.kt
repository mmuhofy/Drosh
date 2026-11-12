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
 * produced nothing. [encodeMouse] does the same for a mouse event, when the
 * running program has asked to have touch reported to it. The caller writes
 * them to the PTY. Nothing about a key is remembered here: the encoder is the
 * session's, and the session knows its own modes.
 */
object NativeInput {

    /** Android's key actions, matching `KeyEvent.ACTION_*`. */
    const val ACTION_PRESS = 0
    const val ACTION_RELEASE = 1
    const val ACTION_REPEAT = 2

    /** Mouse actions, matching the engine's `GhosttyMouseAction`. */
    const val MOUSE_ACTION_PRESS = 0
    const val MOUSE_ACTION_RELEASE = 1
    const val MOUSE_ACTION_MOTION = 2

    /** Mouse buttons, matching the engine's `GhosttyMouseButton`. */
    const val MOUSE_BUTTON_LEFT = 1
    const val MOUSE_BUTTON_RIGHT = 2
    const val MOUSE_BUTTON_MIDDLE = 3

    /**
     * The wheel is buttons four to seven on the wire — the xterm convention
     * the tracking protocols are written around: up, down, left, right.
     */
    const val MOUSE_BUTTON_WHEEL_UP = 4
    const val MOUSE_BUTTON_WHEEL_DOWN = 5
    const val MOUSE_BUTTON_WHEEL_LEFT = 6
    const val MOUSE_BUTTON_WHEEL_RIGHT = 7

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

    /**
     * Encode one mouse event.
     *
     * The engine owns the protocol: which tracking mode the program asked for
     * (X10, normal, button-event, any-event) and which wire format it accepted
     * (SGR 1006, UTF-8, URxvt, ...) are its own state, read out of the VT
     * stream it parsed. Nothing about the protocol is decided here.
     *
     * @param action one of the `MOUSE_ACTION_*` constants
     * @param button one of the `MOUSE_BUTTON_*` constants
     * @param x, y the touch position, in surface pixels
     * @param viewWidth, viewHeight the surface's size, in pixels
     * @param cellWidth, cellHeight the cell's size, in pixels — the geometry
     *   the engine converts the touch position through
     * @param buttonPressed whether any button is held right now; motion
     *   reports in the normal tracking mode need one, and the host is the one
     *   tracking the finger
     * @param out a buffer the bytes are written into
     * @return the number of bytes written, 0 when the event produces nothing
     *         (an action the tracking mode does not report), or a negative code
     */
    private external fun nativeEncodeMouse(
        handle: Long,
        action: Int,
        button: Int,
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
        cellWidth: Int,
        cellHeight: Int,
        buttonPressed: Boolean,
        out: ByteArray,
    ): Int

    /**
     * Encode one mouse event.
     *
     * See [nativeEncodeMouse] for the parameters. The public form mirrors
     * [encode]: a missing library or a dead handle is a normal state here, not
     * a crash.
     */
    fun encodeMouse(
        handle: Long,
        action: Int,
        button: Int,
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
        cellWidth: Int,
        cellHeight: Int,
        buttonPressed: Boolean,
        out: ByteArray,
    ): Int {
        if (handle == 0L || !NativeLibraries.ensureLoaded()) return -1
        return nativeEncodeMouse(
            handle,
            action,
            button,
            x,
            y,
            viewWidth,
            viewHeight,
            cellWidth,
            cellHeight,
            buttonPressed,
            out,
        )
    }
}
