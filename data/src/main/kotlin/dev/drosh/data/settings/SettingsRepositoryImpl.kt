package dev.drosh.data.settings

import android.content.Context
import dev.drosh.domain.settings.AboutInfo
import dev.drosh.domain.settings.AutoLockTimeout
import dev.drosh.domain.settings.CursorStyle
import dev.drosh.domain.settings.DroshSettings
import dev.drosh.domain.settings.FontPack
import dev.drosh.domain.settings.MotdMode
import dev.drosh.domain.settings.SettingsRepository
import dev.drosh.domain.settings.SettingsStore
import dev.drosh.domain.settings.TerminalMode
import dev.drosh.domain.settings.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SettingsRepository] as a view over the TOML-backed [SettingsStore].
 *
 * The per-key interface is kept while its consumers are moved to the
 * snapshot: each flow maps the section it names out of the one store value,
 * and each setter is a transform over it. Nothing here holds state of its
 * own, so a key cannot disagree with the file it was read from.
 *
 * When the last consumer has moved to [SettingsStore] this facade goes with
 * it; until then it is the seam that keeps the app working on the new
 * storage without a flag day.
 */
@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: SettingsStore,
) : SettingsRepository {

    private val settings: Flow<DroshSettings> get() = store.settings

    override val themeMode: Flow<ThemeMode> = settings.map { it.appearance.themeMode }

    override suspend fun setThemeMode(mode: ThemeMode) {
        store.update { it.copy(appearance = it.appearance.copy(themeMode = mode)) }
    }

    override val fontPack: Flow<FontPack> = settings.map { it.appearance.fontPack }

    override suspend fun setFontPack(pack: FontPack) {
        store.update { it.copy(appearance = it.appearance.copy(fontPack = pack)) }
    }

    override val useBlockEngine: Flow<Boolean> =
        settings.map { it.terminal.mode == TerminalMode.Blocks }

    override suspend fun setUseBlockEngine(enabled: Boolean) {
        store.update {
            it.copy(
                terminal = it.terminal.copy(
                    mode = if (enabled) TerminalMode.Blocks else TerminalMode.Stream,
                ),
            )
        }
    }

    override val extraKeysBarVisible: Flow<Boolean> = settings.map { it.input.extraKeysBar }

    override suspend fun setExtraKeysBarVisible(visible: Boolean) {
        store.update { it.copy(input = it.input.copy(extraKeysBar = visible)) }
    }

    override val fontSizeSp: Flow<Float> = settings.map { it.terminal.defaultFontSizeSp }

    override suspend fun setFontSize(size: Float) {
        store.update { it.copy(terminal = it.terminal.copy(defaultFontSizeSp = size)) }
    }

    override val terminalBgColor: Flow<String> = settings.map { it.terminal.background }

    override suspend fun setTerminalBgColor(hex: String) {
        store.update { it.copy(terminal = it.terminal.copy(background = hex)) }
    }

    override val accentColor: Flow<String> = settings.map { it.terminal.accent }

    override suspend fun setAccentColor(hex: String) {
        store.update { it.copy(terminal = it.terminal.copy(accent = hex)) }
    }

    override val terminalTextColor: Flow<String> = settings.map { it.terminal.foreground }

    override suspend fun setTerminalTextColor(hex: String) {
        store.update { it.copy(terminal = it.terminal.copy(foreground = hex)) }
    }

    override val prootStartCommand: Flow<String> = settings.map { it.shell.startupCommand }

    override suspend fun setProotStartCommand(command: String) {
        store.update { it.copy(shell = it.shell.copy(startupCommand = command)) }
    }

    override val cursorStyle: Flow<String> = settings.map { it.terminal.cursorStyle.name }

    override suspend fun setCursorStyle(style: String) {
        store.update {
            it.copy(
                terminal = it.terminal.copy(
                    cursorStyle = CursorStyle.fromString(style),
                ),
            )
        }
    }

    override val cursorBlinkRateMs: Flow<Int> = settings.map { it.terminal.cursorBlinkMs }

    override suspend fun setCursorBlinkRateMs(rate: Int) {
        store.update { it.copy(terminal = it.terminal.copy(cursorBlinkMs = rate)) }
    }

    override val autoLockTimeout: Flow<String> = settings.map { it.security.autoLock.name }

    override suspend fun setAutoLockTimeout(timeout: String) {
        store.update { it.copy(security = it.security.copy(autoLock = AutoLockTimeout.fromString(timeout))) }
    }

    // ── App Info (about.json in assets) ──────────────────────────────────────

    private fun jsonProp(text: String, key: String): String =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: ""

    override val appInfo: Flow<AboutInfo> = flow {
        val text = context.assets.open("about.json")
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        emit(AboutInfo(jsonProp(text, "version"), jsonProp(text, "build"), jsonProp(text, "license")))
    }

    // ── Locale ───────────────────────────────────────────────────────────────

    override val locale: Flow<String> = settings.map { it.appearance.language }

    override suspend fun setLocale(tag: String) {
        store.update { it.copy(appearance = it.appearance.copy(language = tag)) }
    }

    // ── MOTD ─────────────────────────────────────────────────────────────────

    override val motdMode: Flow<MotdMode> = settings.map { it.shell.motdMode }

    override suspend fun setMotdMode(mode: MotdMode) {
        store.update { it.copy(shell = it.shell.copy(motdMode = mode)) }
    }

    override val motdText: Flow<String> = settings.map { it.shell.motdText }

    override suspend fun setMotdText(text: String) {
        store.update { it.copy(shell = it.shell.copy(motdText = text)) }
    }
}
