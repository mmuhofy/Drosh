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

import java.io.File
import kotlinx.serialization.json.Json

/**
 * Command snippets shared with Drosh Keyboard.
 *
 * Lives in `~/.drosh/snippets.json`, as a flat alias-to-command object:
 *
 *     { "gp": "git push", "gl": "git log --oneline" }
 *
 * A plain object on purpose: the keyboard lists entries, the user picks one,
 * and there is no ordering or nesting to argue about. Snippets are Drosh's
 * own configuration, so they live outside `.zshrc` where the user cannot
 * break Drosh's setup by editing their shell config.
 *
 * Everything here is a pure function over files. Freshness is the caller's
 * job: the foreground service re-reads on every command-state publish and
 * only notifies when the content changed, so there is no watcher, no mtime
 * race, and no polling loop.
 */
object SnippetsStore {

    /** One alias. Sorted by alias at load so both sides see a stable order. */
    data class Snippet(val alias: String, val command: String)

    private const val DIR_NAME = ".drosh"
    private const val FILE_NAME = "snippets.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Reads all snippets. Returns empty when the file is missing, unreadable,
     * or malformed — a broken snippet file must never break the terminal,
     * and the keyboard treats empty as "no snippets".
     *
     * Blank aliases and blank commands are dropped: they cannot be triggered
     * or displayed, so keeping them only moves a broken entry to the UI.
     */
    fun load(homeDir: File): List<Snippet> {
        val file = File(File(homeDir, DIR_NAME), FILE_NAME)
        if (!file.isFile || !file.canRead()) return emptyList()
        val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        val map = runCatching {
            json.decodeFromString<Map<String, String>>(raw)
        }.getOrNull() ?: return emptyList()
        return map.entries
            .filter { (alias, command) -> alias.isNotBlank() && command.isNotBlank() }
            .map { (alias, command) -> Snippet(alias.trim(), command.trim()) }
            .sortedBy { it.alias }
    }

    fun location(homeDir: File): File = File(File(homeDir, DIR_NAME), FILE_NAME)
}
