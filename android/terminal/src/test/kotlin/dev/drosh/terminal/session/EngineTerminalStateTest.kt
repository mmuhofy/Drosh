package dev.drosh.terminal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pane's lifecycle rules.
 *
 * Pure on purpose: the pane owns a native session, but its *states* do not
 * touch one, so the rules can be checked without a device and without the
 * native library.
 */
class EngineTerminalStateTest {

    @Test
    fun a_fresh_pane_is_idle_and_may_start() {
        assertTrue(EngineTerminalState.IDLE.canStart)
        assertFalse(EngineTerminalState.IDLE.canStop)
    }

    @Test
    fun starting_moves_the_pane_to_running() {
        assertEquals(EngineTerminalState.RUNNING, EngineTerminalState.IDLE.started())
    }

    @Test
    fun a_running_pane_does_not_start_again() {
        assertFalse(EngineTerminalState.RUNNING.canStart)
        assertEquals(EngineTerminalState.RUNNING, EngineTerminalState.RUNNING.started())
    }

    @Test
    fun stopping_returns_the_pane_to_idle() {
        assertTrue(EngineTerminalState.RUNNING.canStop)
        assertEquals(EngineTerminalState.IDLE, EngineTerminalState.RUNNING.stopped())
    }

    @Test
    fun stopping_a_pane_that_runs_nothing_changes_nothing() {
        assertFalse(EngineTerminalState.IDLE.canStop)
        assertEquals(EngineTerminalState.IDLE, EngineTerminalState.IDLE.stopped())
    }

    @Test
    fun a_released_pane_never_runs_again() {
        assertFalse(EngineTerminalState.RELEASED.canStart)
        assertFalse(EngineTerminalState.RELEASED.canStop)
        assertEquals(EngineTerminalState.RELEASED, EngineTerminalState.RELEASED.started())
        assertEquals(EngineTerminalState.RELEASED, EngineTerminalState.RELEASED.stopped())
    }

    @Test
    fun releasing_ends_the_pane_whatever_it_was_doing() {
        assertEquals(EngineTerminalState.RELEASED, EngineTerminalState.IDLE.released())
        assertEquals(EngineTerminalState.RELEASED, EngineTerminalState.RUNNING.released())
        assertEquals(EngineTerminalState.RELEASED, EngineTerminalState.RELEASED.released())
    }
}
