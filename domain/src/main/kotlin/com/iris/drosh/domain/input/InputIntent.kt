package com.iris.irisshell.domain.input

import com.iris.irisshell.domain.input.ExtraKey

/**
 * A single intent produced by an extra-key tap / long-press, consumed by
 * `terminal.input.InputDispatcher`.
 *
 * Kept as a flat sealed class so the dispatcher can exhaustively `when`
 * over it. All variants carry enough information for the dispatcher to
 * decide whether the bytes go to PTY (classic mode) or to the
 * BasicTextField + PTY byte flush (block mode) without needing to peek
 * at the key state again.
 */
sealed interface InputIntent {

    /** A literal codepoint (glyph). Block mode: insert into text field. Classic: `inputCodePoint`. */
    data class TypeChar(val char: Char) : InputIntent

    /** Press a navigation key. Block mode: BasicTextField cursor or block list scroll. Classic: `handleKeyCode`. */
    data class Navigate(val key: ExtraKey.Navigation) : InputIntent

    /** Arm a sticky modifier. Consumed by the next non-modifier intent (or expires on next read). */
    data class ArmModifier(val modifier: ExtraKey.Special) : InputIntent

    /** Flush a raw control byte directly to PTY — used for SIGINT (`\u0003`), EOF (`\u0004`), etc. */
    data class FlushBytes(val bytes: ByteArray) : InputIntent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FlushBytes) return false
            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }
}
