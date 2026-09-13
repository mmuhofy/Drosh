package com.iris.irisshell.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.iris.irisshell.data.local.irisShellDataStore
import com.iris.irisshell.domain.settings.AboutInfo
import com.iris.irisshell.domain.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed implementation of [SettingsRepository].
 *
 * Persists all user preferences under named keys. Defaults match
 * MEMORYBANK.md §5 Visual Identity tokens.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private val dataStore: DataStore<Preferences> = context.irisShellDataStore

    // ── Block Mode ────────────────────────────────────────────────────────────

    override val useBlockEngine: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_USE_BLOCK_ENGINE] ?: DEFAULT_USE_BLOCK_ENGINE }

    override suspend fun setUseBlockEngine(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_USE_BLOCK_ENGINE] = enabled }
    }

    // ── Extra Keys Bar ────────────────────────────────────────────────────────

    override val extraKeysBarVisible: Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_EXTRA_KEYS_BAR_VISIBLE] ?: DEFAULT_EXTRA_KEYS_BAR_VISIBLE }

    override suspend fun setExtraKeysBarVisible(visible: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_EXTRA_KEYS_BAR_VISIBLE] = visible }
    }

    // ── Font Size ─────────────────────────────────────────────────────────────

    override val fontSizeSp: Flow<Int> =
        dataStore.data.map { prefs -> prefs[KEY_FONT_SIZE_SP] ?: DEFAULT_FONT_SIZE_SP }

    override suspend fun setFontSize(size: Int) {
        dataStore.edit { prefs -> prefs[KEY_FONT_SIZE_SP] = size }
    }

    // ── Terminal Background Color ─────────────────────────────────────────────

    override val terminalBgColor: Flow<String> =
        dataStore.data.map { prefs -> prefs[KEY_TERMINAL_BG_COLOR] ?: DEFAULT_TERMINAL_BG_COLOR }

    override suspend fun setTerminalBgColor(hex: String) {
        dataStore.edit { prefs -> prefs[KEY_TERMINAL_BG_COLOR] = hex }
    }

    // ── Accent Color ──────────────────────────────────────────────────────────

    override val accentColor: Flow<String> =
        dataStore.data.map { prefs -> prefs[KEY_ACCENT_COLOR] ?: DEFAULT_ACCENT_COLOR }

    override suspend fun setAccentColor(hex: String) {
        dataStore.edit { prefs -> prefs[KEY_ACCENT_COLOR] = hex }
    }

    // ── Terminal Text Color ───────────────────────────────────────────────────

    override val terminalTextColor: Flow<String> =
        dataStore.data.map { prefs -> prefs[KEY_TERMINAL_TEXT_COLOR] ?: DEFAULT_TERMINAL_TEXT_COLOR }

    override suspend fun setTerminalTextColor(hex: String) {
        dataStore.edit { prefs -> prefs[KEY_TERMINAL_TEXT_COLOR] = hex }
    }

    // ── PRoot Start Command ─────────────────────────────────────────────────────

    override val prootStartCommand: Flow<String> =
        dataStore.data.map { prefs -> prefs[KEY_PROOT_START_COMMAND] ?: DEFAULT_PROOT_START_COMMAND }

    override suspend fun setProotStartCommand(command: String) {
        dataStore.edit { prefs -> prefs[KEY_PROOT_START_COMMAND] = command }
    }

    override val cursorStyle: Flow<String> =
        dataStore.data.map { prefs -> prefs[KEY_CURSOR_STYLE] ?: DEFAULT_CURSOR_STYLE }

    override suspend fun setCursorStyle(style: String) {
        dataStore.edit { prefs -> prefs[KEY_CURSOR_STYLE] = style }
    }

    override val cursorBlinkRateMs: Flow<Int> =
        dataStore.data.map { prefs -> prefs[KEY_CURSOR_BLINK_RATE_MS] ?: DEFAULT_CURSOR_BLINK_RATE_MS }

    override suspend fun setCursorBlinkRateMs(rate: Int) {
        dataStore.edit { prefs -> prefs[KEY_CURSOR_BLINK_RATE_MS] = rate }
    }

    override val autoLockTimeout: Flow<String> =
        dataStore.data.map { prefs -> prefs[KEY_AUTO_LOCK_TIMEOUT] ?: DEFAULT_AUTO_LOCK_TIMEOUT }

    override suspend fun setAutoLockTimeout(timeout: String) {
        dataStore.edit { prefs -> prefs[KEY_AUTO_LOCK_TIMEOUT] = timeout }
    }

    // ── App Info (about.json in assets) ─

    private fun jsonProp(text: String, key: String): String =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: ""

    override val appInfo: Flow<AboutInfo> = flow {
        val text = context.assets.open("about.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        emit(AboutInfo(jsonProp(text, "version"), jsonProp(text, "build"), jsonProp(text, "license")))
    }

    // ── Keys & Defaults ───────────────────────────────────────────────────────

    private companion object {
        val KEY_USE_BLOCK_ENGINE        = booleanPreferencesKey("use_block_engine")
        val KEY_EXTRA_KEYS_BAR_VISIBLE  = booleanPreferencesKey("extra_keys_bar_visible")
        val KEY_FONT_SIZE_SP            = intPreferencesKey("font_size_sp")
        val KEY_TERMINAL_BG_COLOR       = stringPreferencesKey("terminal_bg_color")
        val KEY_ACCENT_COLOR            = stringPreferencesKey("accent_color")
        val KEY_TERMINAL_TEXT_COLOR     = stringPreferencesKey("terminal_text_color")
        val KEY_PROOT_START_COMMAND     = stringPreferencesKey("proot_start_command")
        val KEY_CURSOR_STYLE            = stringPreferencesKey("cursor_style")
        val KEY_CURSOR_BLINK_RATE_MS    = intPreferencesKey("cursor_blink_rate_ms")
        val KEY_AUTO_LOCK_TIMEOUT       = stringPreferencesKey("auto_lock_timeout")

        const val DEFAULT_USE_BLOCK_ENGINE       = false
        const val DEFAULT_EXTRA_KEYS_BAR_VISIBLE = true
        const val DEFAULT_FONT_SIZE_SP           = 14
        const val DEFAULT_TERMINAL_BG_COLOR      = "#000000"
        const val DEFAULT_ACCENT_COLOR           = "#3B82F6"
        const val DEFAULT_TERMINAL_TEXT_COLOR    = "#E8E8E8"
        const val DEFAULT_PROOT_START_COMMAND    = ""
        const val DEFAULT_CURSOR_STYLE           = "Block"
        const val DEFAULT_CURSOR_BLINK_RATE_MS   = 500
        const val DEFAULT_AUTO_LOCK_TIMEOUT      = "Immediately"
    }
}
