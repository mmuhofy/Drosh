package dev.drosh.terminal.session

import dev.drosh.domain.block.ShellPhase
import dev.drosh.terminal.engine.core.BlockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The engine's block state in the shape the domain speaks.
 *
 * The engine reports the phase, the working directory and the editor target
 * as they are; this is the translation, and the translation is where the
 * engine's own vocabulary (a phase code, a `file://` URI) becomes the
 * domain's (an enum, a path).
 */
class EngineShellStateSourceTest {

    @Test
    fun `the phase comes from the engine's own code`() {
        val state = BlockState(phase = BlockState.PHASE_RUNNING).toShellState()
        assertEquals(ShellPhase.RUNNING, state?.phase)
    }

    @Test
    fun `every phase the engine knows has a name here`() {
        assertEquals(ShellPhase.PROMPT, BlockState(phase = BlockState.PHASE_PROMPT).toShellState()?.phase)
        assertEquals(ShellPhase.TYPING, BlockState(phase = BlockState.PHASE_TYPING).toShellState()?.phase)
        assertEquals(ShellPhase.FINISHED, BlockState(phase = BlockState.PHASE_FINISHED).toShellState()?.phase)
    }

    @Test
    fun `a phase this build does not know is the unknown phase`() {
        val state = BlockState(
            phase = "something-else",
            workingDirectory = "file:///home",
        ).toShellState()
        assertEquals(ShellPhase.UNKNOWN, state?.phase)
    }

    @Test
    fun `a working directory arrives as a path`() {
        val state = BlockState(
            phase = BlockState.PHASE_PROMPT,
            workingDirectory = "file:///home/muhofy",
        ).toShellState()
        assertEquals("/home/muhofy", state?.workingDirectory)
    }

    @Test
    fun `a plus in a path is not a space`() {
        val state = BlockState(
            phase = BlockState.PHASE_PROMPT,
            workingDirectory = "file:///home/a+b",
        ).toShellState()
        assertEquals("/home/a+b", state?.workingDirectory)
    }

    @Test
    fun `a directory that is not a file uri is no directory`() {
        val state = BlockState(
            phase = BlockState.PHASE_PROMPT,
            workingDirectory = "/home/muhofy",
        ).toShellState()
        assertNull(state?.workingDirectory)
    }

    @Test
    fun `the editor target is the path the shell asked for`() {
        val state = BlockState(
            phase = BlockState.PHASE_PROMPT,
            editorTarget = "/tmp/notes.txt",
        ).toShellState()
        assertEquals("/tmp/notes.txt", state?.editorTarget)
    }

    @Test
    fun `the exit status rides along`() {
        val state = BlockState(phase = BlockState.PHASE_FINISHED, status = 3).toShellState()
        assertEquals(3, state?.exitStatus)
    }

    @Test
    fun `an engine that knows nothing says nothing`() {
        assertNull(BlockState().toShellState())
    }
}
