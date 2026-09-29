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
}
