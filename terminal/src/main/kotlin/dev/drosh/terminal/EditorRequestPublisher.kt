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

import com.termux.terminal.ShellIntegrationState
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Turns `editor <path>` typed in a terminal into a one-shot event the UI can
 * navigate on.
 *
 * A [Channel] rather than a `StateFlow`, and that is the whole design. The
 * request is an event: "open this file, now". Modelling it as state would mean
 * either re-delivering the same path to every new collector — so rotating the
 * device or backgrounding the app reopens the editor — or having to invent a
 * "consumed" flag to get back to event semantics with extra steps. A buffered
 * channel hands the path to exactly one collector, survives a brief gap
 * between the escape sequence arriving and the screen subscribing, and drops
 * the event if nobody ever comes back for it.
 *
 * Mirrors [CommandStatePublisher] in binding: it follows the active tab, so
 * two terminals cannot each try to open the editor for the other's command.
 */
class EditorRequestPublisher {

    // Buffered rather than CONFLATED: two `editor` commands in quick
    // succession are two files the user asked to see, and conflating them
    // would silently open only the second.
    private val channel = Channel<String>(Channel.BUFFERED)

    /** Guest paths, in the order the guest asked for them. */
    val requests: Flow<String> = channel.receiveAsFlow()

    private var bound: ShellIntegrationState? = null

    private val listener: (String) -> Unit = { guestPath ->
        // trySend, not send: this runs on the emulator's thread inside the PTY
        // read loop, and suspending there would stall terminal output.
        // The buffer exists precisely so a full channel cannot happen.
        channel.trySend(guestPath)
    }

    fun bind(session: TerminalSession?) {
        val target = session?.shellIntegration
        if (bound === target) return
        bound?.removeEditorListener(listener)
        bound = target
        target?.addEditorListener(listener)
    }

    fun unbind() = bind(null)
}