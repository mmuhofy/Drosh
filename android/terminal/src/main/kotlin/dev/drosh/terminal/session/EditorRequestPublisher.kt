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

import dev.drosh.terminal.engine.core.EngineSession
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
 * The engine reports the editor target as part of its block state (`OSC 1339`),
 * so [poll] is driven by the same watch that reads the viewport.
 */
class EditorRequestPublisher {

    // Buffered rather than CONFLATED: two `editor` commands in quick
    // succession are two files the user asked to see, and conflating them
    // would silently open only the second.
    private val channel = Channel<String>(Channel.BUFFERED)

    /** Guest paths, in the order the guest asked for them. */
    val requests: Flow<String> = channel.receiveAsFlow()

    private var bound: EngineSession? = null

    /** The last editor target seen, so the same request is not delivered twice. */
    private var lastTarget: String? = null

    fun bind(session: EngineSession?) {
        if (bound === session) return
        bound = session
        lastTarget = null
    }

    fun unbind() = bind(null)

    /**
     * Reads the engine's current editor target and delivers it when it is new.
     *
     * Called from the engine watch (a background dispatcher), so this does not
     * touch the UI thread. `trySend` rather than `send`: the buffer exists
     * precisely so this can never suspend.
     */
    fun poll() {
        val session = bound ?: return
        if (!session.isAlive) return

        val target = session.blockState().editorTarget
        if (target.isNullOrEmpty() || target == lastTarget) return
        lastTarget = target
        channel.trySend(target)
    }
}
