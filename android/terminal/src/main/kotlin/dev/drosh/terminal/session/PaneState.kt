package dev.drosh.terminal.session

/**
 * The lifecycle of a terminal pane.
 *
 * The pane owns its session, so the states are about that session: whether it
 * runs, and whether the pane may be used at all. The transitions are pure —
 * they are the rules, and the pane applies them — so they can be checked
 * without a device and without the native library.
 */
enum class PaneState {
    /** No session yet; the pane may be started. */
    IDLE,

    /** A session is running; the pane may be stopped. */
    RUNNING,

    /** The pane was released: its session is gone and it can never run again. */
    RELEASED;

    /** May a session be started in this state? */
    val canStart: Boolean get() = this == IDLE

    /** May the session be stopped in this state? */
    val canStop: Boolean get() = this == RUNNING

    /** The state after a session was started successfully. */
    fun started(): PaneState = if (canStart) RUNNING else this

    /** The state after the session was stopped. */
    fun stopped(): PaneState = if (canStop) IDLE else this

    /** The state after the pane was released. Releasing twice is harmless. */
    fun released(): PaneState = RELEASED

    /**
     * The state after the child exited on its own.
     *
     * A session that ended is not a session that was stopped: the pane keeps
     * what the child last drew on screen, and starting again is a new session
     * in the same pane.
     */
    fun exited(): PaneState = if (this == RUNNING) IDLE else this
}
