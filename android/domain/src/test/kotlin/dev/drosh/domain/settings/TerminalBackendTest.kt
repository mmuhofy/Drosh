package dev.drosh.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The rules the terminal backend setting depends on.
 *
 * Two of them, and both are about the file rather than about the app: the
 * name in the file is lower-case, so a hand-edit reads like prose, and an
 * unknown name is *refused* rather than guessed at — a file that says
 * `backend = "engin"` must leave the app on the path it knows works, not on
 * a path nobody chose.
 */
class TerminalBackendTest {

    @Test
    fun `the default is the terminal the app is known to work on`() {
        assertSame("Engine is now the default backend", TerminalBackend.ENGINE, TerminalBackend.DEFAULT)
        assertSame("and the setting itself defaults to it", TerminalBackend.TERMUX, TerminalSettings.DEFAULT.backend)
    }

    @Test
    fun `the name in the file is lower-case`() {
        assertEquals("termux", TerminalBackend.TERMUX.tomlName())
        assertEquals("engine", TerminalBackend.ENGINE.tomlName())
    }

    @Test
    fun `a name is read whatever its case, and a wrong one is refused`() {
        assertSame(TerminalBackend.ENGINE, TerminalBackend.fromName("engine"))
        assertSame(TerminalBackend.ENGINE, TerminalBackend.fromName("ENGINE"))
        assertSame(TerminalBackend.TERMUX, TerminalBackend.fromName(" termux "))

        assertNull("a name that is not one of ours is not guessed at", TerminalBackend.fromName("engin"))
        assertNull("and neither is a missing one", TerminalBackend.fromName(null))
    }
}
