package dev.drosh.terminal

/**
 * Callback interface allowing [TerminalManager] to notify the data/session
 * layer about terminal session lifecycle events.
 *
 * Inspired by Termux's `TerminalSessionClient` callback pattern
 * (github.com/termux/termux-app, terminal-emulator/.../TerminalSessionClient.kt),
 * where the service-bound client receives `onSessionFinished` and
 * `setTerminalShellPid` events. Drosh splits this: the PTY-level
 * events flow through [TerminalSessionClientImpl], and the session-level
 * events (keyed by persistent id) flow through this interface.
 *
 * Ported from: mmuhofy/IrisCode — terminal/TerminalManager.kt
 * Adapted for Drosh — dev.drosh
 */
interface SessionLifecycleCallbacks {

    /**
     * A session's process has exited, either naturally or by being killed.
     * [persistentId] is null for sessions that were never persisted.
     */
    fun onSessionFinished(persistentId: String?, exitCode: Int)

    /**
     * The shell pid has been assigned for a session. Room does not store pids
     * yet, so this is currently a no-op; reserved for process monitoring.
     */
    fun onSessionPidChanged(persistentId: String?, pid: Int)
}
