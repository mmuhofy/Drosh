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

package dev.drosh.data.state

import com.termux.terminal.CommandSnapshot
import com.termux.terminal.CommandStatus
import com.termux.terminal.ShellIntegrationLevel

/**
 * Holds the latest command state for cross-process readers.
 *
 * A provider cannot reach the Hilt graph — it is created by the framework
 * before anything is injected — so the foreground service, which already owns
 * the terminal, pushes state here and the provider serves it. That keeps the
 * provider free of DI and makes the write path obvious: one writer, many
 * readers.
 */
object CommandStateBus {

    data class Entry(val sessionId: String, val snapshot: CommandSnapshot)

    @Volatile
    private var latest: Entry? = null

    /** Cleared when the terminal goes away so readers do not see stale state. */
    fun publish(sessionId: String, snapshot: CommandSnapshot) {
        latest = Entry(sessionId, snapshot)
    }

    fun clear() {
        latest = null
    }

    fun read(): Entry? = latest

    // ── Encoding ────────────────────────────────────────────────────────────
    // Single letters so a reader can never mis-parse a value added later.

    fun statusCode(status: CommandStatus): String = when (status) {
        is CommandStatus.Idle -> "i"
        is CommandStatus.Running -> "r"
        is CommandStatus.Success -> "s"
        is CommandStatus.Failure -> "f"
        is CommandStatus.Indeterminate -> "n"
    }

    fun levelCode(level: ShellIntegrationLevel): String = when (level) {
        is ShellIntegrationLevel.FULL -> "f"
        is ShellIntegrationLevel.PROMPT_ONLY -> "p"
        is ShellIntegrationLevel.NONE -> "n"
    }
}
