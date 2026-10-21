package dev.drosh.terminal.session.input

import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession

/**
 * The vendored Termux terminal's writer.
 *
 * The bytes are the ones the vendored path has always sent, through the
 * vendored session's own doors:
 *
 *  - a character goes through `TerminalSession.writeCodePoint()`, which puts
 *    the escape in front of it when alt was held and encodes it as UTF-8;
 *  - a key goes through the vendored `KeyHandler`, which is what turns a key
 *    code and a modifier set into a VT sequence;
 *  - raw bytes go through the session's byte write.
 *
 * Nothing here decides *what* a key means — the dispatcher does that — and
 * nothing here invents an encoding: the vendored terminal keeps the one it
 * has always had, table and all.
 */
class TermuxTerminalWriter(private val session: TerminalSession) : TerminalWriter {

    override fun writeChar(char: Char, mods: InputModifiers) {
        val codePoint = if (mods.ctrl) termuxControlCode(char.code) else char.code
        session.writeCodePoint(mods.alt, codePoint)
    }

    override fun writeKey(keyCode: Int, mods: InputModifiers) {
        val code = KeyHandler.getCode(
            keyCode = keyCode,
            keyMode = keyHandlerMods(mods),
            // The vendored path has always asked for the `ESC [` form of the
            // cursor keys, whatever the application asked for. Kept as it is:
            // this is the vendored terminal's behaviour, not a decision to be
            // made here.
            cursorApp = false,
            keypadApplication = false,
        )
        code?.let { session.write(it) }
    }

    override fun writeBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) {
            return
        }
        session.write(bytes, 0, bytes.size)
    }

    /** The vendored key handler's modifier bits. */
    private fun keyHandlerMods(mods: InputModifiers): Int {
        var mod = 0
        if (mods.ctrl) mod = mod or KeyHandler.KEYMOD_CTRL
        if (mods.alt) mod = mod or KeyHandler.KEYMOD_ALT
        return mod
    }
}

/**
 * The C0 control code for a character, or the character itself.
 *
 * Mirrors the C0-control translation in `TerminalView.inputCodePoint` (see
 * docs/MEMORYBANK.md §8) — the vendored terminal's own table, kept exactly
 * as it is, because the vendored path has always sent these bytes.
 */
internal fun termuxControlCode(codePoint: Int): Int = when (codePoint) {
    in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
    in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
    ' '.code, '2'.code -> 0
    '['.code, '3'.code -> 27
    '\\'.code, '4'.code -> 28
    ']'.code, '5'.code -> 29
    '^'.code, '6'.code -> 30
    '_'.code, '7'.code, '/'.code -> 31
    '8'.code -> 127
    else -> codePoint
}
