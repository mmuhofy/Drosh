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

package dev.drosh.terminal

import com.termux.terminal.CommandSnapshot
import com.termux.terminal.ShellIntegrationState
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Exposes the active terminal's command lifecycle as a [StateFlow].
 *
 * The marks themselves are parsed inside the emulator, so by the time a
 * snapshot reaches here there is no latency left to recover: the state is
 * already current when the observer is notified. Elapsed time is derived by
 * the consumer from [CommandSnapshot.startedAtElapsedMs], so a UI can tick
 * every frame without re-reading anything.
 *
 * Bound to the active session and re-bound whenever the tab changes, so
 * multiple terminals do not fight over one flow.
 */
class CommandStatePublisher {

    private val _state = MutableStateFlow(CommandSnapshot())
    val state: StateFlow<CommandSnapshot> = _state.asStateFlow()

    private var bound: ShellIntegrationState? = null

    private val listener: (CommandSnapshot) -> Unit = { _state.value = it }

    fun bind(session: TerminalSession?) {
        val target = session?.shellIntegration
        if (bound === target) return
        bound?.removeListener(listener)
        bound = target
        // addListener replays the current value, so a switch lands on the new
        // terminal's real state instead of keeping the previous one's.
        target?.addListener(listener) ?: run { _state.value = CommandSnapshot() }
    }

    fun unbind() = bind(null)
}
