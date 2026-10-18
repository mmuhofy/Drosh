package dev.drosh.engine.input

/**
 * Key → bytes, xterm style (decision D6).
 *
 * Every sequence in here was read out of alacritty's own sources instead of
 * being remembered:
 *
 * - the APP_CURSOR forms (`ESC O H`, `ESC O A`…), Shift+Tab (`ESC [ Z`), the
 *   Alt-prefixed variants (`ESC ESC [ Z`, `ESC DEL`) and Enter as CR come from
 *   `alacritty/src/config/bindings.rs` (`default_key_bindings`);
 * - the `~`-terminated keys (PgUp/PgDn/Insert/Delete, F5–F12), F1–F4 as SS3 and
 *   the named control characters (Tab 9, Enter 13, Escape 27, Backspace 127)
 *   come from `alacritty/src/input/keyboard.rs`;
 * - the modifier parameter — `CSI 1;5D` for Ctrl+Left, i.e. 1 + shift + 2·alt +
 *   4·ctrl — comes from alacritty's own test bindings.
 *
 * The one thing desktop terminals get from the OS keyboard layer is the C0
 * mapping of Ctrl+letter (`Ctrl+A` = SOH = 0x01). It is the ASCII definition
 * (`code & 0x1f`) and the device test exercises it through Ctrl+C.
 */
internal object Encoder {

    /** Modifiers held for one key press. */
    data class Mods(
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val shift: Boolean = false,
    ) {
        /** The xterm modifier parameter: 1 + shift + 2·alt + 4·ctrl. */
        val parameter: Int get() = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)

        val any: Boolean get() = ctrl || alt || shift
    }

    /** Keys that carry a sequence rather than a character. */
    enum class Named {
        Tab,
        Escape,
        Enter,
        Backspace,
        Up,
        Down,
        Right,
        Left,
        Home,
        End,
        PageUp,
        PageDown,
        Insert,
        Delete,
        F1,
        F2,
        F3,
        F4,
        F5,
        F6,
        F7,
        F8,
        F9,
        F10,
        F11,
        F12,
    }

    private const val ESC = 0x1b
    private const val CSI = "\u001b["
    private const val SS3 = "\u001bO"

    /**
     * The bytes for a named key, or `null` when the combination has no sequence.
     *
     * [appCursor] is the terminal's DECCKM state (`NativeCore.MODE_APP_CURSOR`):
     * full-screen programs such as vim ask for `ESC O` cursors, and sending the
     * wrong form makes their arrow keys print letters.
     */
    fun sequence(named: Named, mods: Mods = Mods(), appCursor: Boolean = false): ByteArray? =
        body(named, mods, appCursor)

    private fun body(named: Named, mods: Mods, appCursor: Boolean): ByteArray? {
        cursorFinal(named)?.let { final ->
            // Without modifiers the cursor keys are `CSI` — or `SS3` when the
            // application asked for DECCKM; with modifiers they are
            // `CSI 1;<mod><final>`, and Alt is part of that parameter
            // (`CSI 1;3D` is Alt+Left, not an ESC prefix).
            val head = if (appCursor && !mods.any) SS3 else CSI
            val parameter = if (mods.any) "1;${mods.parameter}" else ""
            return "$head$parameter$final".toByteArray(Charsets.US_ASCII)
        }

        tildeNumber(named)?.let { number ->
            val parameter = if (mods.any) ";${mods.parameter}" else ""
            return "$CSI$number$parameter~".toByteArray(Charsets.US_ASCII)
        }

        functionFinal(named)?.let { final ->
            // F1–F4 are SS3 sequences on their own (alacritty binds them as
            // `ESC O P`…`ESC O S`); with any modifier they take the CSI form,
            // which is what xterm sends.
            return if (mods.any) {
                "$CSI${mods.parameter}$final".toByteArray(Charsets.US_ASCII)
            } else {
                "$SS3$final".toByteArray(Charsets.US_ASCII)
            }
        }

        // Keys with no modifier parameter carry Alt as an ESC prefix instead —
        // bindings.rs sends `ESC DEL` for Alt+Backspace and `ESC ESC [ Z` for
        // Tab+Shift+Alt.
        val prefixed = if (mods.alt) byteArrayOf(ESC.toByte()) else ByteArray(0)
        val simple = when (named) {
            // Shift+Tab (CBT) is the only Tab variant with a sequence. Note the
            // `${CSI}`: a bare `$CSI Z` would put a space in the sequence, which
            // is exactly the bug the unit test caught before it reached a device.
            Named.Tab -> if (mods.shift) "${CSI}Z".toByteArray(Charsets.US_ASCII) else byteArrayOf(0x09)
            // Enter is CR, not LF: the tty's ICRNL turns it into a newline for
            // the shell, and a raw-mode program expects CR.
            Named.Enter -> byteArrayOf(0x0d)
            Named.Escape -> byteArrayOf(ESC.toByte())
            Named.Backspace -> byteArrayOf(0x7f)
            else -> return null
        }
        return if (prefixed.isEmpty()) simple else prefixed + simple
    }

    private fun cursorFinal(named: Named): Char? = when (named) {
        Named.Up -> 'A'
        Named.Down -> 'B'
        Named.Right -> 'C'
        Named.Left -> 'D'
        Named.Home -> 'H'
        Named.End -> 'F'
        else -> null
    }

    private fun tildeNumber(named: Named): Int? = when (named) {
        Named.Insert -> 2
        Named.Delete -> 3
        Named.PageUp -> 5
        Named.PageDown -> 6
        Named.F5 -> 15
        Named.F6 -> 17
        Named.F7 -> 18
        Named.F8 -> 19
        Named.F9 -> 20
        Named.F10 -> 21
        Named.F11 -> 23
        Named.F12 -> 24
        else -> null
    }

    private fun functionFinal(named: Named): Char? = when (named) {
        Named.F1 -> 'P'
        Named.F2 -> 'Q'
        Named.F3 -> 'R'
        Named.F4 -> 'S'
        else -> null
    }

    /**
     * Bytes for typed text — what the soft keyboard commits.
     *
     * The sticky Ctrl from the extra-keys row applies to the *first* character,
     * so Gboard's `c` with Ctrl armed sends 0x03 (Ctrl+C); the rest of the
     * commit goes through unchanged.
     */
    fun text(text: String, mods: Mods = Mods()): ByteArray {
        if (text.isEmpty()) {
            return ByteArray(0)
        }

        // Built through a stream rather than array concatenation: this runs once
        // per committed character, and the intent stays readable.
        val out = java.io.ByteArrayOutputStream(text.length + 2)

        if (mods.alt) {
            out.write(ESC)
        }

        var rest = text
        if (mods.ctrl) {
            controlCode(text[0])?.let { code ->
                out.write(code)
                rest = text.substring(1)
            }
        }

        // Only the first character can be turned into a control code, so the
        // remainder is a byte-for-byte copy of what the keyboard sent (UTF-8).
        if (rest.isNotEmpty()) {
            out.write(rest.toByteArray(Charsets.UTF_8))
        }

        return out.toByteArray()
    }

    /**
     * The C0 control code for a character, or `null` when it has none.
     *
     * C0 is defined by masking with 0x1f: `Ctrl+A` and `Ctrl+a` are both 0x01,
     * `Ctrl+[` is 0x1b, `Ctrl+_` is 0x1f, `Ctrl+@` and `Ctrl+Space` are 0x00.
     */
    fun controlCode(character: Char): Int? {
        val code = character.code
        return when {
            code in 0x40..0x5f -> code and 0x1f
            code in 0x61..0x7a -> code and 0x1f
            code == 0x20 -> 0x00
            else -> null
        }
    }
}
