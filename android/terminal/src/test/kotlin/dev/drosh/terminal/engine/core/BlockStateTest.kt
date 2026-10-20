package dev.drosh.terminal.engine.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The engine's block-state record, as the Kotlin side reads it.
 *
 * The format is one `key:value` per line. A shell that has not reported in yet
 * knows nothing, and a missing key has to read as "not known" rather than as
 * an empty string — the difference matters for the working directory, where
 * "" is a path and null is an absence.
 */
class BlockStateTest {

    @Test
    fun an_empty_record_is_the_unknown_phase() {
        val state = BlockState.parse("")
        assertEquals(BlockState.PHASE_UNKNOWN, state.phase)
        assertNull(state.status)
        assertNull(state.workingDirectory)
        assertNull(state.editorTarget)
    }

    @Test
    fun every_key_is_read() {
        val state = BlockState.parse(
            "phase:running\nstatus:3\ncwd:/home/muhofy\neditor:/tmp/notes.txt",
        )
        assertEquals(BlockState.PHASE_RUNNING, state.phase)
        assertEquals(3, state.status)
        assertEquals("/home/muhofy", state.workingDirectory)
        assertEquals("/tmp/notes.txt", state.editorTarget)
    }

    @Test
    fun a_record_without_a_status_has_none() {
        val state = BlockState.parse("phase:prompt\ncwd:/home")
        assertEquals(BlockState.PHASE_PROMPT, state.phase)
        assertNull(state.status)
        assertEquals("/home", state.workingDirectory)
    }

    @Test
    fun a_value_may_contain_a_colon() {
        val state = BlockState.parse("phase:finished\ncwd:/home/a:b")
        assertEquals("/home/a:b", state.workingDirectory)
    }

    @Test
    fun a_line_without_a_separator_is_ignored() {
        val state = BlockState.parse("phase:prompt\ngarbage\ncwd:/home")
        assertEquals(BlockState.PHASE_PROMPT, state.phase)
        assertEquals("/home", state.workingDirectory)
    }
}
