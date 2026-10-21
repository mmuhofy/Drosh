package dev.drosh.terminal.session.input

import android.view.KeyEvent
import dev.drosh.domain.input.ExtraKey
import dev.drosh.domain.input.InputIntent
import dev.drosh.terminal.session.ExtraKeyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dispatcher's half of the seam: which key and which modifiers reach the
 * writer.
 *
 * The bytes are the writer's business — the vendored session's or the
 * engine's — and are not checked here: what is pinned is that the decision
 * (the character, the key code, the sticky modifier) is made once, in the
 * dispatcher, and travels to the writer unchanged.
 */
class InputDispatcherWriterTest {

    @Test
    fun `TypeChar reaches the writer as the character`() {
        val writer = RecordingWriter()
        val dispatcher = InputDispatcher(ExtraKeyState(), writer)

        dispatcher.dispatch(InputIntent.TypeChar('a'))

        assertEquals('a', writer.char)
        assertEquals(InputModifiers(), writer.mods)
    }

    @Test
    fun `An armed CTRL travels with the character and is consumed`() {
        val state = ExtraKeyState()
        val writer = RecordingWriter()
        val dispatcher = InputDispatcher(state, writer)

        dispatcher.dispatch(InputIntent.ArmModifier(ExtraKey.Special.CTRL))
        dispatcher.dispatch(InputIntent.TypeChar('c'))

        assertEquals('c', writer.char)
        assertTrue("CTRL should travel with the character", writer.mods?.ctrl == true)

        // The sticky modifier is consumed by the key it armed.
        dispatcher.dispatch(InputIntent.TypeChar('d'))
        assertEquals("CTRL should not travel with the next key", false, writer.mods?.ctrl)
    }

    @Test
    fun `Navigate reaches the writer as an Android key code`() {
        val writer = RecordingWriter()
        val dispatcher = InputDispatcher(ExtraKeyState(), writer)

        dispatcher.dispatch(InputIntent.Navigate(ExtraKey.Navigation.ARROW_UP))

        assertEquals(KeyEvent.KEYCODE_DPAD_UP, writer.keyCode)
    }

    @Test
    fun `FlushBytes reaches the writer as bytes`() {
        val writer = RecordingWriter()
        val dispatcher = InputDispatcher(ExtraKeyState(), writer)

        dispatcher.dispatch(InputIntent.FlushBytes(byteArrayOf(3)))

        assertEquals(1, writer.bytes?.size)
        assertEquals(3, writer.bytes?.get(0)?.toInt())
    }

    /** A writer that remembers what it was asked to write. */
    private class RecordingWriter : TerminalWriter {

        var char: Char? = null
        var mods: InputModifiers? = null
        var keyCode: Int? = null
        var bytes: ByteArray? = null

        override fun writeChar(char: Char, mods: InputModifiers) {
            this.char = char
            this.mods = mods
        }

        override fun writeKey(keyCode: Int, mods: InputModifiers) {
            this.keyCode = keyCode
            this.mods = mods
        }

        override fun writeBytes(bytes: ByteArray) {
            this.bytes = bytes
        }
    }
}
