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
class EnginePaneStateTest {

    @Test
    fun a_fresh_pane_is_idle_and_may_start() {
        assertTrue(EnginePaneState.IDLE.canStart)
        assertFalse(EnginePaneState.IDLE.canStop)
    }

    @Test
    fun starting_moves_the_pane_to_running() {
        assertEquals(EnginePaneState.RUNNING, EnginePaneState.IDLE.started())
    }

    @Test
    fun a_running_pane_does_not_start_again() {
        assertFalse(EnginePaneState.RUNNING.canStart)
        assertEquals(EnginePaneState.RUNNING, EnginePaneState.RUNNING.started())
    }

    @Test
    fun stopping_returns_the_pane_to_idle() {
        assertTrue(EnginePaneState.RUNNING.canStop)
        assertEquals(EnginePaneState.IDLE, EnginePaneState.RUNNING.stopped())
    }

    @Test
    fun stopping_a_pane_that_runs_nothing_changes_nothing() {
        assertFalse(EnginePaneState.IDLE.canStop)
        assertEquals(EnginePaneState.IDLE, EnginePaneState.IDLE.stopped())
    }

    @Test
    fun a_released_pane_never_runs_again() {
        assertFalse(EnginePaneState.RELEASED.canStart)
        assertFalse(EnginePaneState.RELEASED.canStop)
        assertEquals(EnginePaneState.RELEASED, EnginePaneState.RELEASED.started())
        assertEquals(EnginePaneState.RELEASED, EnginePaneState.RELEASED.stopped())
    }

    @Test
    fun releasing_ends_the_pane_whatever_it_was_doing() {
        assertEquals(EnginePaneState.RELEASED, EnginePaneState.IDLE.released())
        assertEquals(EnginePaneState.RELEASED, EnginePaneState.RUNNING.released())
        assertEquals(EnginePaneState.RELEASED, EnginePaneState.RELEASED.released())
    }
}
