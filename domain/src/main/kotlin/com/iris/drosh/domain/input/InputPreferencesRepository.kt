package com.iris.irisshell.domain.input

import kotlinx.coroutines.flow.Flow

/**
 * User preferences for the on-screen input system.
 *
 * Currently surfaces only the bar-visibility toggle — when enabled, the
 * bar sits above the system keyboard in BOTH classic and block mode.
 * Default: false (hidden by default — matches `docs/MEMORYBANK.md` §8).
 */
interface InputPreferencesRepository {

    /** Hot stream of the bar-visibility flag. Emits false on first launch. */
    val extraKeysBarVisible: Flow<Boolean>

    /** Persists the bar-visibility flag. */
    suspend fun setExtraKeysBarVisible(visible: Boolean)
}
