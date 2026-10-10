package dev.drosh.data.settings.toml

import android.content.Context
import android.os.FileObserver
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.drosh.core.toml.TomlDocument
import dev.drosh.data.local.irisShellDataStore
import dev.drosh.domain.settings.AutoLockTimeout
import dev.drosh.domain.settings.CursorStyle
import dev.drosh.domain.settings.DroshSettings
import dev.drosh.domain.settings.FontPack
import dev.drosh.domain.settings.MotdMode
import dev.drosh.domain.settings.TerminalMode
import dev.drosh.domain.settings.ThemeMode
import dev.drosh.domain.settings.SettingsStore
import dev.drosh.domain.terminal.PanePresentation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [SettingsStore] over `<rootfs home>/.drosh/settings.toml`, with an
 * app-private backup.
 *
 * **Resolution.** Reads prefer the rootfs copy; if it is missing — rootfs not
 * installed yet, or wiped by a reset — the backup is read and, once the rootfs
 * is back, the first write restores it there. Writes go to both, primary
 * first, atomically (tmp + rename), so a reader never sees a partial file and
 * a power loss costs at most the last write, which the backup then holds.
 *
 * **External edits.** The file is watched with a [FileObserver] on both
 * directories. A write from a shell, a script or the agent re-reads the file
 * and emits — that is the point of keeping the settings inside the Linux
 * environment: `theme_mode = "dark"` in an SSH session is a settings change.
 * The app's own writes fire the same event; [reload] compares before
 * emitting, so they settle instead of looping.
 *
 * **Migration.** With neither file present — a fresh install, or the first
 * launch after this system replaced DataStore — the legacy DataStore values
 * are read once and written out as the TOML file, then never read again.
 * The DataStore stays in the build solely as that one-time source.
 */
@Singleton
class TomlSettingsStore @Inject constructor(
    private val paths: SettingsPaths,
    @ApplicationContext private val context: Context,
) : SettingsStore {

    private val legacyDataStore: DataStore<Preferences> = context.irisShellDataStore

    private val _settings = MutableStateFlow(DroshSettings.DEFAULT)
    override val settings: StateFlow<DroshSettings> = _settings.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var observer: FileObserver? = null

    init {
        // Synchronous on purpose: MainActivity's attachBaseContext, the
        // terminal service and the overlay all read settings before any flow
        // could have emitted, and the first read is a 2 KB file.
        val loaded = runBlocking { withContext(Dispatchers.IO) { load() } }
        _settings.value = loaded.settings
        if (loaded.recoveredFromBackup) {
            Timber.i("settings: primary missing, running on the app-private backup")
        }
        if (loaded.migrated) {
            Timber.i("settings: migrated legacy DataStore values into settings.toml")
        }
        watch()
    }

    override suspend fun update(transform: (DroshSettings) -> DroshSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        val written = withContext(Dispatchers.IO) { write(next) }
        if (!written) {
            // The value stands: a toggle that snapped back because the disk
            // said no is worse than one that has not been saved yet. The next
            // successful write carries it.
            Timber.w("settings: could not persist the file; the value is held in memory")
        }
    }

    override suspend fun reload() {
        val read = withContext(Dispatchers.IO) { readFile() }
        val parsed = read?.let { runCatching { TomlDocument.parse(it) }.getOrNull() }
            ?: return
        val next = DroshSettingsToml.fromDocument(parsed)
        if (next != _settings.value) {
            _settings.value = next
            Timber.i("settings: file changed outside the app, reloaded")
        }
    }

    /**
     * The file to believe, primary first. A missing primary is the rootfs
     * being away; a present one wins even if the backup is newer, because
     * the rootfs copy is the one outside edits land in.
     */
    private fun readFile(): String? =
        paths.primaryFile.readTextOrNull() ?: paths.backupFile.readTextOrNull()

    // ── loading ───────────────────────────────────────────────────────────────

    private data class LoadResult(
        val settings: DroshSettings,
        val migrated: Boolean = false,
        val recoveredFromBackup: Boolean = false,
    )

    private suspend fun load(): LoadResult {
        val primaryText = paths.primaryFile.readTextOrNull()
        val primary = primaryText?.let { runCatching { TomlDocument.parse(it) }.getOrNull() }
        if (primary != null) {
            return LoadResult(DroshSettingsToml.fromDocument(primary))
        }
        if (primaryText != null) {
            // Present but unreadable: say so and fall through to the backup
            // rather than throwing the user's preferences away.
            Timber.w("settings: primary file did not parse, trying the backup")
        }

        val backupText = paths.backupFile.readTextOrNull()
        val backup = backupText?.let { runCatching { TomlDocument.parse(it) }.getOrNull() }
        if (backup != null) {
            val loaded = DroshSettingsToml.fromDocument(backup)
            // The rootfs copy is the one that should exist; restoring it here
            // is what makes the next external edit land in the right place.
            if (paths.primaryFile.parentFile?.exists() == true) {
                write(loaded)
            }
            return LoadResult(loaded, recoveredFromBackup = true)
        }

        val migrated = migrateFromLegacyDataStore()
        if (migrated != null) {
            write(migrated)
            return LoadResult(migrated, migrated = true)
        }
        write(DroshSettings.DEFAULT)
        return LoadResult(DroshSettings.DEFAULT)
    }

    private suspend fun migrateFromLegacyDataStore(): DroshSettings? {
        // Only a pre-existing DataStore is a source: an empty one is a fresh
        // install with nothing to carry over, and writing defaults is the
        // same either way.
        val prefs = runCatching { legacyDataStore.data.first() }.getOrNull() ?: return null
        if (prefs.asMap().isEmpty()) return null

        val base = DroshSettings.DEFAULT
        val migrated = base.copy(
            appearance = base.appearance.copy(
                themeMode = ThemeMode.fromName(prefs[KEY_THEME_MODE]),
                fontPack = FontPack.fromName(prefs[KEY_FONT_PACK]),
                language = prefs[KEY_LOCALE] ?: base.appearance.language,
            ),
            terminal = base.terminal.copy(
                mode = if (prefs[KEY_USE_BLOCK_ENGINE] ?: false) TerminalMode.Blocks else TerminalMode.Stream,
                // The slider's key and the pinch's key held the same setting
                // twice; the pinch's is the one the terminal actually read, so
                // it wins — and where it is absent the slider's is carried.
                defaultFontSizeSp = prefs[KEY_TERMINAL_FONT_SP] ?: prefs[KEY_FONT_SIZE_SP]
                    ?: base.terminal.defaultFontSizeSp,
                cursorStyle = CursorStyle.fromString(prefs[KEY_CURSOR_STYLE] ?: ""),
                cursorBlinkMs = prefs[KEY_CURSOR_BLINK_RATE_MS] ?: base.terminal.cursorBlinkMs,
                background = prefs[KEY_TERMINAL_BG_COLOR] ?: base.terminal.background,
                foreground = prefs[KEY_TERMINAL_TEXT_COLOR] ?: base.terminal.foreground,
                accent = prefs[KEY_ACCENT_COLOR] ?: base.terminal.accent,
            ),
            input = base.input.copy(
                extraKeysBar = prefs[KEY_EXTRA_KEYS_BAR_VISIBLE] ?: base.input.extraKeysBar,
            ),
            shell = base.shell.copy(
                startupCommand = prefs[KEY_PROOT_START_COMMAND] ?: base.shell.startupCommand,
                motdMode = MotdMode.fromString(prefs[KEY_MOTD_MODE] ?: ""),
                motdText = prefs[KEY_MOTD_TEXT] ?: base.shell.motdText,
            ),
            security = base.security.copy(
                autoLock = AutoLockTimeout.fromString(prefs[KEY_AUTO_LOCK_TIMEOUT] ?: ""),
            ),
            session = base.session.copy(
                secondarySessionId = prefs[KEY_PANE_SECONDARY_ID] ?: base.session.secondarySessionId,
                presentation = PanePresentation.entries.firstOrNull {
                    it.name.equals(prefs[KEY_PANE_PRESENTATION], ignoreCase = true)
                } ?: base.session.presentation,
                splitFraction = prefs[KEY_PANE_SPLIT_FRACTION] ?: base.session.splitFraction,
                floatLeft = prefs[KEY_PANE_FLOAT_LEFT] ?: base.session.floatLeft,
                floatTop = prefs[KEY_PANE_FLOAT_TOP] ?: base.session.floatTop,
                floatWidth = prefs[KEY_PANE_FLOAT_WIDTH] ?: base.session.floatWidth,
                floatHeight = prefs[KEY_PANE_FLOAT_HEIGHT] ?: base.session.floatHeight,
                maximized = prefs[KEY_PANE_MAXIMIZED] ?: base.session.maximized,
            ),
            meta = base.meta.copy(
                firstLaunchCompleted = prefs[KEY_FIRST_LAUNCH_COMPLETED] ?: base.meta.firstLaunchCompleted,
            ),
        )
        return migrated
    }

    // ── writing ───────────────────────────────────────────────────────────────

    /** Atomic: write a sibling temp file, then rename over the target. */
    private fun write(settings: DroshSettings): Boolean {
        val text = SETTINGS_FILE_HEADER + "\n\n" + DroshSettingsToml.toDocument(settings).render()
        val primaryOk = writeAtomically(paths.primaryFile, text)
        val backupOk = writeAtomically(paths.backupFile, text)
        return primaryOk && backupOk
    }

    private fun writeAtomically(target: File, text: String): Boolean = try {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            // renameTo fails when the target exists on some filesystems; the
            // delete-then-rename pair is the documented dance for that.
            target.delete()
            tmp.renameTo(target)
        }
        true
    } catch (e: IOException) {
        Timber.w(e, "settings: could not write ${target.path}")
        false
    }

    private fun File.readTextOrNull(): String? = try {
        if (exists()) readText() else null
    } catch (e: IOException) {
        Timber.w(e, "settings: could not read $path")
        null
    }

    // ── watching ──────────────────────────────────────────────────────────────

    private fun watch() {
        watchDirectory(paths.primaryFile.parentFile)
        watchDirectory(paths.backupFile.parentFile)
    }

    /**
     * Watches [dir] for writes to `settings.toml`.
     *
     * Two observers rather than one on a common ancestor: the rootfs copy and
     * the backup can live far enough apart that a single watch on a shared
     * parent would miss both, and the file name is the only event worth
     * waking for — the directory holds nothing else.
     */
    private fun watchDirectory(dir: File?) {
        val path = dir?.path ?: return
        if (!dir.exists()) return
        // The String overload, not the File one: the File constructor is
        // API 29 and minSdk is 26, and the path is all either one is given.
        val watcher = object : FileObserver(path, CLOSE_WRITE or MOVED_TO or CREATE) {
            override fun onEvent(event: Int, path: String?) {
                if (path != SettingsPaths.FILE_NAME) return
                scope.launch { reload() }
            }
        }
        watcher.startWatching()
        observer = watcher
    }

    /** Legacy DataStore keys — read once, by [migrateFromLegacyDataStore]. */
    private companion object {
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_FONT_PACK = stringPreferencesKey("font_pack")
        val KEY_USE_BLOCK_ENGINE = booleanPreferencesKey("use_block_engine")
        val KEY_EXTRA_KEYS_BAR_VISIBLE = booleanPreferencesKey("extra_keys_bar_visible")
        val KEY_FONT_SIZE_SP = floatPreferencesKey("font_size_sp")
        val KEY_TERMINAL_BG_COLOR = stringPreferencesKey("terminal_bg_color")
        val KEY_ACCENT_COLOR = stringPreferencesKey("accent_color")
        val KEY_TERMINAL_TEXT_COLOR = stringPreferencesKey("terminal_text_color")
        val KEY_PROOT_START_COMMAND = stringPreferencesKey("proot_start_command")
        val KEY_CURSOR_STYLE = stringPreferencesKey("cursor_style")
        val KEY_CURSOR_BLINK_RATE_MS = intPreferencesKey("cursor_blink_rate_ms")
        val KEY_AUTO_LOCK_TIMEOUT = stringPreferencesKey("auto_lock_timeout")
        val KEY_LOCALE = stringPreferencesKey("locale")
        val KEY_MOTD_MODE = stringPreferencesKey("motd_mode")
        val KEY_MOTD_TEXT = stringPreferencesKey("motd_text")
        val KEY_TERMINAL_FONT_SP = floatPreferencesKey("terminal_font_sp")
        val KEY_FIRST_LAUNCH_COMPLETED = booleanPreferencesKey("first_launch_completed")
        val KEY_PANE_SECONDARY_ID = stringPreferencesKey("pane_secondary_session_id")
        val KEY_PANE_SPLIT_FRACTION = floatPreferencesKey("pane_split_fraction")
        val KEY_PANE_PRESENTATION = stringPreferencesKey("pane_presentation")
        val KEY_PANE_FLOAT_LEFT = floatPreferencesKey("pane_float_left")
        val KEY_PANE_FLOAT_TOP = floatPreferencesKey("pane_float_top")
        val KEY_PANE_FLOAT_WIDTH = floatPreferencesKey("pane_float_width")
        val KEY_PANE_FLOAT_HEIGHT = floatPreferencesKey("pane_float_height")
        val KEY_PANE_MAXIMIZED = booleanPreferencesKey("pane_maximized")
    }
}
