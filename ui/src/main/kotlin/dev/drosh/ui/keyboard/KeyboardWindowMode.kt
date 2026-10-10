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

package dev.drosh.ui.keyboard

import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where Drosh Keyboard is docked on screen. */
enum class KeyboardWindowMode {
    /** Along the bottom edge, full width. The default. */
    FIXED,

    /** Free-floating over the content, like a desktop window. */
    FLOATING,

    /** No keyboard running, or its mode is unknown. Treated as FIXED. */
    UNKNOWN,
}

/**
 * Placement of Drosh Keyboard, as broadcast by it.
 *
 * Only relevant when Drosh Keyboard is the active IME. Anything else leaves
 * this at [KeyboardWindowMode.UNKNOWN], which every consumer treats as FIXED —
 * the safe default, since it is what every keyboard does.
 */
object KeyboardWindowModeState {

    /** Broadcast by Drosh Keyboard. Signature-permission protected. */
    const val ACTION_WINDOW_MODE = "dev.drosh.action.KEYBOARD_WINDOW_MODE"
    const val EXTRA_MODE = "mode"

    /**
     * Broadcasters must hold this. Declared at signature level in both apps,
     * so only builds signed with the shared Drosh key can tell Drosh where
     * the keyboard sits.
     */
    const val PERMISSION_SEND_KEYBOARD_MODE = "dev.drosh.permission.SEND_KEYBOARD_MODE"

    private val _mode = MutableStateFlow(KeyboardWindowMode.UNKNOWN)
    val mode: StateFlow<KeyboardWindowMode> = _mode.asStateFlow()

    /** True when content should stay clear of the keyboard. */
    val docked: Boolean get() = _mode.value != KeyboardWindowMode.FLOATING

    fun onBroadcast(raw: String?) {
        _mode.value = when (raw?.trim()?.lowercase()) {
            "floating" -> KeyboardWindowMode.FLOATING
            "fixed" -> KeyboardWindowMode.FIXED
            else -> KeyboardWindowMode.UNKNOWN
        }
    }

    fun reset() {
        _mode.value = KeyboardWindowMode.UNKNOWN
    }
}

/**
 * Reserves space for the keyboard only while it is docked.
 *
 * A floating keyboard sits *over* the content by design, so padding would push
 * the layout around for no reason — and would leave a dead band where the
 * keyboard happens to sit. Docked, the keyboard occupies the bottom and content
 * must move up.
 *
 * Falls back to padding whenever the mode is unknown, which includes every
 * other IME and any moment before the first broadcast.
 */
fun Modifier.droshImePadding(): Modifier =
    if (KeyboardWindowModeState.docked) imePadding() else this
