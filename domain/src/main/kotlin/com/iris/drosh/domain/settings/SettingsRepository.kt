package com.iris.irisshell.domain.settings

import kotlinx.coroutines.flow.Flow

/**
 * User preferences that live in DataStore.
 *
 * Surfaces:
 *  - Block Mode toggle
 *  - Extra-keys bar visibility toggle
 *  - Terminal font size (sp)
 *  - Terminal background color (hex string)
 *  - Accent color (hex string)
 *  - Terminal text color (hex string)
 *
 * See MEMORYBANK.md §5 and §8.
 */
interface SettingsRepository {

    /** Hot stream of the Block Mode flag. Emits false on first launch. */
    val useBlockEngine: Flow<Boolean>

    /** Persists the Block Mode flag. */
    suspend fun setUseBlockEngine(enabled: Boolean)

    /** Hot stream of the extra-keys bar visibility flag. Emits false on first launch. */
    val extraKeysBarVisible: Flow<Boolean>

    /** Persists the extra-keys bar visibility flag. */
    suspend fun setExtraKeysBarVisible(visible: Boolean)

    /** Hot stream of the font size in sp. Emits 14 on first launch. */
    val fontSizeSp: Flow<Int>

    /** Persists the font size in sp. */
    suspend fun setFontSize(size: Int)

    /** Hot stream of the terminal background color as a hex string. Default: "#000000". */
    val terminalBgColor: Flow<String>

    /** Persists the terminal background color. */
    suspend fun setTerminalBgColor(hex: String)

    /** Hot stream of the accent color as a hex string. Default: "#3B82F6". */
    val accentColor: Flow<String>

    /** Persists the accent color. */
    suspend fun setAccentColor(hex: String)

    /** Hot stream of the terminal text color as a hex string. Default: "#E8E8E8". */
    val terminalTextColor: Flow<String>

    /** Persists the terminal text color. */
    suspend fun setTerminalTextColor(hex: String)

    /**
     * Custom PRoot start command (e.g. `/bin/bash --login --norc`).
     * Empty string = use default (shell + `--login`).
     *
     * WARNING: Experimental. Changing this can break terminal sessions.
     */
    val prootStartCommand: Flow<String>

    /** Persists the custom PRoot start command. */
    suspend fun setProotStartCommand(command: String)

    /** Hot stream of the cursor style preference. Emits Block on first launch. */
    val cursorStyle: Flow<String>

    /** Persists the cursor style. */
    suspend fun setCursorStyle(style: String)

    /** Hot stream of the cursor blink rate in ms. Emits 500 on first launch. */
    val cursorBlinkRateMs: Flow<Int>

    /** Persists the cursor blink rate in ms. */
    suspend fun setCursorBlinkRateMs(rate: Int)

    /** Hot stream of the auto-lock timeout preference. Emits Immediately on first launch. */
    val autoLockTimeout: Flow<String>

    /** Persists the auto-lock timeout. */
    suspend fun setAutoLockTimeout(timeout: String)

    /** Static app info (version / build tag / license) sourced from about.json. */
    val appInfo: Flow<AboutInfo>
}
