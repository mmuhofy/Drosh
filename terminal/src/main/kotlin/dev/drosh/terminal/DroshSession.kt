package dev.drosh.terminal

import com.termux.terminal.TerminalSession

/**
 * A wrapper that bundles a live [TerminalSession] with its persistent
 * metadata (id, name, shell pid). Inspired by Termux's `TermuxSession`
 * (github.com/termux/termux-app, termux-shared/.../terminal/TermuxSession.kt),
 * which pairs each `TerminalSession` with an `ExecutionCommand`.
 *
 * Drosh does not yet have an equivalent of `ExecutionCommand`, so this
 * wrapper stores the minimal metadata the session system needs:
 * the persistent Room id (if any), the user-visible name, and the shell pid.
 *
 * Ported from: mmuhofy/IrisCode — terminal/TerminalManager.kt
 * Adapted for Drosh — dev.drosh
 */
class DroshSession(
    var terminalSession: TerminalSession,
    val persistentId: String?,
    var name: String,
    var pid: Int = 0,
    var isFallbackSession: Boolean = false,
)
