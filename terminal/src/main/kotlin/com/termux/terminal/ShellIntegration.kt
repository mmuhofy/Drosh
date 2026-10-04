/*
 * Copyright (C) 2026 Drosh contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package com.termux.terminal

import android.os.SystemClock

/**
 * Command lifecycle, reconstructed from OSC 133 shell integration marks.
 *
 * OSC 133 is the FinalTerm protocol, adopted by iTerm2, WezTerm, Kitty,
 * Ghostty, VS Code and Windows Terminal. The shell writes a handful of
 * escape sequences into its own output at the prompt and command
 * boundaries, and the terminal emulator interprets them. Nothing is
 * written to disk, nothing is polled, and no clock is read in the shell.
 *
 * The old mechanism wrote `command|elapsed|exit_code` lines to a file that
 * a foreground service re-read every 500 ms. It could not represent a
 * command containing a pipe, forked twice per command for `date +%s`, grew
 * without bound, and silently dropped records when a read landed mid-line.
 *
 * Shells differ in what they can report, so capability is tracked
 * explicitly and consumers must degrade rather than assume.
 */
sealed class CommandStatus {
    /** No command has been observed since the session started. */
    data object Idle : CommandStatus()

    /** A command is executing. */
    data object Running : CommandStatus()

    /** Finished with exit code 0. */
    data object Success : CommandStatus()

    /** Finished with a non-zero exit code. */
    data class Failure(val exitCode: Int) : CommandStatus()

    /**
     * The command finished but the shell could not report an exit code, or
     * the command was aborted (Ctrl-C). Indistinguishable from failure-free
     * completion with no shell integration, so callers must not paint this as
     * an error.
     */
    data object Indeterminate : CommandStatus()
}

/**
 * How much a shell is able to tell us.
 *
 * `dash`/`sh` have no pre-exec hook of any kind, and exit status is not
 * reachable without wrapping every command — the approach Termux abandoned
 * when its exec instrumentation became unmaintainable. Rather than pretend,
 * the capability is reported and the emulator falls back to inferring
 * command boundaries from the screen itself.
 */
enum class ShellIntegrationLevel {
    /** A and B marks seen, so prompt boundaries are known but no exit code. */
    PROMPT_ONLY,

    /** Full A/B/C/D marks including exit code. */
    FULL,

    /** Nothing emitted; boundaries inferred heuristically. */
    NONE,
}

/**
 * Immutable snapshot of what the shell last told us.
 *
 * Timestamps come from [SystemClock.elapsedRealtime] captured in-process the
 * moment the mark arrives, so elapsed time cannot drift from what the user
 * sees and needs no cooperation from the shell.
 */
data class CommandSnapshot(
    val status: CommandStatus = CommandStatus.Idle,
    val command: String = "",
    /** Monotonic start, or 0 if the start was never marked. */
    val startedAtElapsedMs: Long = 0L,
    /** Monotonic end of the last completed command, or 0 if still running. */
    val endedAtElapsedMs: Long = 0L,
    val exitCode: Int? = null,
    val level: ShellIntegrationLevel = ShellIntegrationLevel.NONE,
    /** Last reported working directory, from OSC 7. */
    val cwd: String = "",
) {
    /** Wall time of the last completed command, or of the running one. */
    fun elapsedMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
        val start = startedAtElapsedMs
        if (start <= 0L) return 0L
        val end = if (status is CommandStatus.Running) nowElapsedMs else endedAtElapsedMs
        return (end - start).coerceAtLeast(0L)
    }

    val isRunning: Boolean get() = status is CommandStatus.Running
}

/**
 * Per-session holder for shell integration marks.
 *
 * Lives on the session rather than globally so that several terminals can
 * be tracked independently; Drosh supports more than one.
 */
class ShellIntegrationState {

    var snapshot: CommandSnapshot = CommandSnapshot()
        private set

    /** True once either A/B or C/D has been seen, i.e. the shell speaks OSC 133. */
    private var sawPromptMark = false
    private var sawCommandMark = false

    /** Cursor position recorded at the last A/B mark, used to recover the command. */
    private var promptRow = -1
    private var promptCol = -1

    fun onPromptStart(row: Int, col: Int) {
        sawPromptMark = true
        promptRow = row
        promptCol = col
        publish(
            snapshot.copy(
                status = CommandStatus.Idle,
                level = level(),
                cwd = snapshot.cwd,
            ),
        )
    }

    /**
     * B: the prompt has been drawn, the user is typing. No command is running.
     *
     * @param command recovered from the screen between the previous B and the
     *   upcoming C, when the shell did not send `cmdline=`. Empty when unknown.
     */
    fun onCommandStart(command: String?) {
        sawCommandMark = true
        publish(
            snapshot.copy(
                status = CommandStatus.Running,
                command = command ?: snapshot.command,
                startedAtElapsedMs = SystemClock.elapsedRealtime(),
                endedAtElapsedMs = 0L,
                exitCode = null,
                level = level(),
            ),
        )
    }

    /** D with an exit code. */
    fun onCommandFinished(exitCode: Int) {
        publish(
            snapshot.copy(
                status = if (exitCode == 0) CommandStatus.Success else CommandStatus.Failure(exitCode),
                endedAtElapsedMs = SystemClock.elapsedRealtime(),
                exitCode = exitCode,
                level = level(),
            ),
        )
    }

    /** D without an exit code: aborted, or a shell that cannot report status. */
    fun onCommandFinishedWithoutStatus() {
        publish(
            snapshot.copy(
                status = CommandStatus.Indeterminate,
                endedAtElapsedMs = SystemClock.elapsedRealtime(),
                exitCode = null,
                level = level(),
            ),
        )
    }

    fun onCwd(cwd: String) {
        if (cwd == snapshot.cwd) return
        publish(snapshot.copy(cwd = cwd))
    }

    /**
     * A program running in the guest asked Drosh to open a path in the native
     * editor.
     *
     * This rides the same shell-to-app channel as the OSC 133 marks because
     * that is what it is: the guest talking to its own terminal, not the guest
     * reaching into the app. Nothing is polled and no file is watched — the
     * `editor` command Drosh installs writes one escape sequence and exits.
     *
     * Deliberately kept out of [snapshot]. An editor request is an event with
     * no lasting state, and putting it in the snapshot would make
     * [CommandSnapshot.equals] report a command-lifecycle change every time
     * the user opens a file, which the block engine reads as activity.
     */
    fun onOpenEditor(guestPath: String) {
        if (guestPath.isEmpty()) return
        editorListeners.forEach { it(guestPath) }
    }

    private val editorListeners = mutableListOf<(String) -> Unit>()

    /**
     * Registers an editor-request listener.
     *
     * Unlike [addListener] this does not replay anything. A request that
     * arrived while nobody was listening has already been acted on or lost,
     * and re-delivering it on the next tab switch would open the editor again
     * with no command to explain it.
     */
    fun addEditorListener(listener: (String) -> Unit) {
        editorListeners += listener
    }

    fun removeEditorListener(listener: (String) -> Unit) {
        editorListeners -= listener
    }

    /** Called when a new session replaces the old one. */
    fun reset() {
        sawPromptMark = false
        sawCommandMark = false
        promptRow = -1
        promptCol = -1
        publish(CommandSnapshot())
    }

    val promptStartRow: Int get() = promptRow
    val promptStartCol: Int get() = promptCol

    private fun level(): ShellIntegrationLevel = when {
        sawPromptMark && sawCommandMark -> ShellIntegrationLevel.FULL
        sawPromptMark -> ShellIntegrationLevel.PROMPT_ONLY
        else -> ShellIntegrationLevel.NONE
    }

    private fun publish(next: CommandSnapshot) {
        if (next == snapshot) return
        snapshot = next
        listeners.forEach { it(next) }
    }

    private val listeners = mutableListOf<(CommandSnapshot) -> Unit>()

    fun addListener(listener: (CommandSnapshot) -> Unit) {
        listeners += listener
        listener(snapshot)
    }

    fun removeListener(listener: (CommandSnapshot) -> Unit) {
        listeners -= listener
    }
}
