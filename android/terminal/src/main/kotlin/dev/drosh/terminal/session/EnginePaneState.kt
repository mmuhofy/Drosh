package dev.drosh.terminal.session

/**
 * The lifecycle of an [EnginePane].
 *
 * The pane owns its session, so the states are about that session: whether it
 * runs, and whether the pane may be used at all. The transitions are pure —
 * they are the rules, and [EnginePane] applies them — so they can be checked
 * without a device and without the native library.
 */
enum class EnginePaneState {
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
    fun started(): EnginePaneState = if (canStart) RUNNING else this

    /** The state after the session was stopped. */
    fun stopped(): EnginePaneState = if (canStop) IDLE else this

    /** The state after the pane was released. Releasing twice is harmless. */
    fun released(): EnginePaneState = RELEASED
}
