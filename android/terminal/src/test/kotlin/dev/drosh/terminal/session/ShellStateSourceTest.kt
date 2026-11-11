package dev.drosh.terminal.session

import dev.drosh.domain.block.ShellPhase
import dev.drosh.terminal.engine.shell.Phase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The engine's shell phase in the shape the domain speaks.
 *
 * The engine reads the phase from the shell's marks and reports it as a
 * number; the domain speaks in names. The translation is where the engine's
 * vocabulary becomes the domain's, and it is where a phase that means nothing
 * would otherwise become a phase that means something.
 */
class ShellStateSourceTest {

    @Test
    fun `the phase comes from the engine's own code`() {
        assertEquals(ShellPhase.RUNNING, Phase.RUNNING.toShellPhase())
        assertEquals(ShellPhase.PROMPT, Phase.PROMPT.toShellPhase())
        assertEquals(ShellPhase.TYPING, Phase.TYPING.toShellPhase())
        assertEquals(ShellPhase.FINISHED, Phase.FINISHED.toShellPhase())
    }

    @Test
    fun `a phase the engine does not know is unknown here`() {
        assertEquals(ShellPhase.UNKNOWN, Phase.UNKNOWN.toShellPhase())
    }
}
