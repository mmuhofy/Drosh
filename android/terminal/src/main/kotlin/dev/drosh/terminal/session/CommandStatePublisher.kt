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

package dev.drosh.terminal.session

import android.os.SystemClock
import dev.drosh.domain.terminal.CommandSnapshot
import dev.drosh.domain.terminal.CommandStatus
import dev.drosh.domain.terminal.ShellIntegrationLevel
import dev.drosh.terminal.engine.core.BlockState
import dev.drosh.terminal.engine.core.EngineSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Exposes the active terminal's command lifecycle as a [StateFlow].
 *
 * ## Where the state comes from
 *
 * The Rust engine taps the shell's `OSC 133` marks where the bytes enter and
 * reports the phase, the exit status, the working directory and the editor
 * target through [EngineSession.blockState]. This class turns that into the
 * app's own [CommandSnapshot], so a consumer never learns which terminal
 * produced the answer.
 *
 * Elapsed time is derived by the consumer from
 * [CommandSnapshot.startedAtElapsedMs], so a UI can tick every frame without
 * re-reading anything.
 *
 * Bound to the active pane and re-bound whenever focus changes, so several
 * panes do not fight over one flow.
 */
class CommandStatePublisher(
    /**
     * Output-rate tracker, so the combined state carries activity alongside the
     * command lifecycle. A consumer therefore gets both from one snapshot.
     */
    private val activity: CommandActivityTracker,
) {

    private val _state = MutableStateFlow(CommandSnapshot())
    val state: StateFlow<CommandSnapshot> = _state.asStateFlow()

    private val _activityLevel = MutableStateFlow(0f)

    /** 0f idle, 1f saturating output rate. */
    val activityLevel: StateFlow<Float> = _activityLevel.asStateFlow()

    /** The engine session currently being followed, or null. */
    private var bound: EngineSession? = null

    /**
     * The snapshot of the previous poll, so a phase that has not moved does not
     * publish a new value — and so a running command keeps its start time.
     */
    private var last: CommandSnapshot = CommandSnapshot()

    /**
     * Follows [session]'s marks from now on.
     *
     * The engine reports its state on demand rather than pushing it, so the
     * caller polls [poll] from the same watch that already reads the viewport.
     */
    fun bind(session: EngineSession?) {
        if (bound === session) return
        bound = session
        activity.reset()
        last = CommandSnapshot()
        _state.value = CommandSnapshot()
        _activityLevel.value = 0f
    }

    fun unbind() = bind(null)

    /**
     * Reads the engine's current block state and publishes it when it moved.
     *
     * Called from the engine watch (a background dispatcher), so this does not
     * touch the UI thread.
     */
    fun poll() {
        val session = bound ?: return
        if (!session.isAlive) return

        val state = session.blockState()
        val next = snapshotOf(state)
        if (next == last) return
        last = next

        when (next.status) {
            is CommandStatus.Running -> activity.onCommandStarted()
            is CommandStatus.Success,
            is CommandStatus.Failure,
            is CommandStatus.Indeterminate,
            is CommandStatus.Idle -> activity.onSettled()
        }
        _state.value = next
        _activityLevel.value = activity.level.value
    }

    /**
     * The app's snapshot of one engine block state.
     *
     * The engine's phase is a string (`unknown`, `prompt`, `typing`, `running`,
     * `finished`) and its status is the last exit code, when it reported one.
     */
    private fun snapshotOf(state: BlockState): CommandSnapshot {
        val running = state.phase == BlockState.PHASE_RUNNING
        val finished = state.phase == BlockState.PHASE_FINISHED
        val status = when {
            running -> CommandStatus.Running
            finished && state.status == 0 -> CommandStatus.Success
            finished && state.status != null -> CommandStatus.Failure(state.status)
            finished -> CommandStatus.Indeterminate
            else -> CommandStatus.Idle
        }

        val now = SystemClock.elapsedRealtime()
        // A running command keeps the start it already had; anything else is
        // stamped on the transition, so elapsed time is measured from the
        // moment the phase actually moved.
        val started = when {
            running && last.status !is CommandStatus.Running -> now
            running -> last.startedAtElapsedMs
            else -> last.startedAtElapsedMs
        }
        val ended = when {
            !running && last.status is CommandStatus.Running -> now
            else -> last.endedAtElapsedMs
        }

        return CommandSnapshot(
            status = status,
            command = last.command,
            startedAtElapsedMs = started,
            endedAtElapsedMs = ended,
            exitCode = state.status,
            level = ShellIntegrationLevel.FULL,
            cwd = state.workingDirectory.orEmpty(),
        )
    }
}
