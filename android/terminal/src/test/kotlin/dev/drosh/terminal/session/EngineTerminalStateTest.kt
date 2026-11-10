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
        assertTrue(PaneState.IDLE.canStart)
        assertFalse(PaneState.IDLE.canStop)
    }

    @Test
    fun starting_moves_the_pane_to_running() {
        assertEquals(PaneState.RUNNING, PaneState.IDLE.started())
    }

    @Test
    fun a_running_pane_does_not_start_again() {
        assertFalse(PaneState.RUNNING.canStart)
        assertEquals(PaneState.RUNNING, PaneState.RUNNING.started())
    }

    @Test
    fun stopping_returns_the_pane_to_idle() {
        assertTrue(PaneState.RUNNING.canStop)
        assertEquals(PaneState.IDLE, PaneState.RUNNING.stopped())
    }

    @Test
    fun stopping_a_pane_that_runs_nothing_changes_nothing() {
        assertFalse(PaneState.IDLE.canStop)
        assertEquals(PaneState.IDLE, PaneState.IDLE.stopped())
    }

    @Test
    fun a_released_pane_never_runs_again() {
        assertFalse(PaneState.RELEASED.canStart)
        assertFalse(PaneState.RELEASED.canStop)
        assertEquals(PaneState.RELEASED, PaneState.RELEASED.started())
        assertEquals(PaneState.RELEASED, PaneState.RELEASED.stopped())
    }

    @Test
    fun releasing_ends_the_pane_whatever_it_was_doing() {
        assertEquals(PaneState.RELEASED, PaneState.IDLE.released())
        assertEquals(PaneState.RELEASED, PaneState.RUNNING.released())
        assertEquals(PaneState.RELEASED, PaneState.RELEASED.released())
    }
}
