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
import dev.drosh.terminal.SnippetsStore.Snippet

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

    data class Entry(
        val sessionId: String,
        val snapshot: CommandSnapshot,
        /** 0f idle, 1f saturating output rate of the running command. */
        val activityLevel: Float = 0f,
        /** ARGB tint from the screen, or null when it reads as neutral. */
        val ambientTint: Int? = null,
    )

    @Volatile
    private var latest: Entry? = null

    /** Cleared when the terminal goes away so readers do not see stale state. */
    fun publish(
        sessionId: String,
        snapshot: CommandSnapshot,
        activityLevel: Float = 0f,
        ambientTint: Int? = null,
    ) {
        latest = Entry(sessionId, snapshot, activityLevel, ambientTint)
    }

    fun clear() {
        latest = null
        latestSnippets = emptyList()
    }

    fun read(): Entry? = latest

    @Volatile
    private var latestSnippets: List<Snippet> = emptyList()

    /**
     * Snippets for the keyboard tab. A plain list, not versioned: the
     * service only calls this when the content changed, and the provider
     * serves whatever is here on every query.
     */
    fun publishSnippets(snippets: List<Snippet>) {
        latestSnippets = snippets
    }

    fun readSnippets(): List<Snippet> = latestSnippets

    // ── Encoding ────────────────────────────────────────────────────────────
    // Single letters so a reader can never mis-parse a value added later.

    // Singletons are matched by equality — `is` over an object entry is
    // prohibited. Failure carries an exit code, so it is matched as a type.
    fun statusCode(status: CommandStatus): String = when (status) {
        CommandStatus.Idle -> "i"
        CommandStatus.Running -> "r"
        CommandStatus.Success -> "s"
        is CommandStatus.Failure -> "f"
        CommandStatus.Indeterminate -> "n"
    }

    fun levelCode(level: ShellIntegrationLevel): String = when (level) {
        ShellIntegrationLevel.FULL -> "f"
        ShellIntegrationLevel.PROMPT_ONLY -> "p"
        ShellIntegrationLevel.NONE -> "n"
    }
}
