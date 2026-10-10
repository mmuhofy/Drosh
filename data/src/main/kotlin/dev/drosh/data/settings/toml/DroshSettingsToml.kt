package dev.drosh.data.settings.toml

import dev.drosh.core.toml.TomlDocument
import dev.drosh.domain.settings.AppearanceSettings
import dev.drosh.domain.settings.AutoLockTimeout
import dev.drosh.domain.settings.BellMode
import dev.drosh.domain.settings.CursorStyle
import dev.drosh.domain.settings.DroshSettings
import dev.drosh.domain.settings.EditorSettings
import dev.drosh.domain.settings.FontPack
import dev.drosh.domain.settings.InputSettings
import dev.drosh.domain.settings.MetaSettings
import dev.drosh.domain.settings.MotdMode
import dev.drosh.domain.settings.PanePresentationHolder
import dev.drosh.domain.settings.RootfsSettings
import dev.drosh.domain.settings.SecuritySettings
import dev.drosh.domain.settings.SessionSettings
import dev.drosh.domain.settings.ShellSettings
import dev.drosh.domain.settings.TerminalMode
import dev.drosh.domain.settings.TerminalSettings
import dev.drosh.domain.settings.ThemeMode
import dev.drosh.domain.terminal.PackageProfile
import dev.drosh.domain.terminal.PanePresentation
import dev.drosh.domain.terminal.ShellChoice

/**
 * The mapping between [DroshSettings] and the TOML document on disk.
 *
 * Kept in `:data` rather than `:domain` because the document type lives in
 * `:core`, which `:domain` deliberately does not depend on — the settings
 * model stays pure Kotlin and the file format stays an implementation detail
 * of the store.
 *
 * Enum names are lower-cased in the file (`theme_mode = "dark"`, not
 * `"Dark"`): the file is hand-editable from a shell, and it should read like
 * prose there. Parsing is case-insensitive, so an old build's PascalCase
 * value still loads.
 */
internal object DroshSettingsToml {

    fun toDocument(settings: DroshSettings): TomlDocument = TomlDocument().apply {
        with(settings.appearance) {
            putString("appearance", "theme_mode", themeMode.tomlName())
            putString("appearance", "font_pack", fontPack.tomlName())
            putString("appearance", "language", language)
        }
        with(settings.terminal) {
            putString("terminal", "mode", mode.tomlName())
            putFloat("terminal", "default_font_size_sp", defaultFontSizeSp)
            putString("terminal", "cursor_style", cursorStyle.tomlName())
            putInt("terminal", "cursor_blink_ms", cursorBlinkMs)
            putInt("terminal", "scrollback_rows", scrollbackRows)
            putString("terminal", "bell", bell.tomlName())
            putBoolean("terminal", "keep_awake", keepAwake)
            putBoolean("terminal", "immersive", immersive)
            putBoolean("terminal", "link_detection", linkDetection)
            putBoolean("terminal", "glass_effects", glassEffects)
            // No settings row for the colour triad — the file and the agent
            // set these. Written every time so an edit survives the next save.
            putString("terminal", "background", background)
            putString("terminal", "foreground", foreground)
            putString("terminal", "accent", accent)
        }
        with(settings.input) {
            putBoolean("input", "extra_keys_bar", extraKeysBar)
            putBoolean("input", "hide_bar_hardware_keyboard", hideBarOnHardwareKeyboard)
            putBoolean("input", "hide_bar_drosh_ime", hideBarWhenDroshIme)
        }
        with(settings.shell) {
            putString("shell", "shell", shell.tomlName())
            putString("shell", "omz_theme", omzTheme)
            putStringList("shell", "omz_plugins", omzPlugins)
            putInt("shell", "history_size", historySize)
            putString("shell", "motd_mode", motdMode.tomlName())
            putString("shell", "motd_text", motdText)
            putString("shell", "startup_command", startupCommand)
            putBoolean("shell", "shell_integration", shellIntegration)
        }
        with(settings.rootfs) {
            putString("rootfs", "package_profile", packageProfile.tomlName())
            putStringList("rootfs", "extra_packages", extraPackages)
            putBoolean("rootfs", "install_dedit", installDedit)
            putBoolean("rootfs", "optimize_on_setup", optimizeOnSetup)
            putInt("rootfs", "max_edit_size_kb", maxEditSizeKb)
        }
        with(settings.editor) {
            putFloat("editor", "font_size_sp", fontSizeSp)
            putBoolean("editor", "word_wrap", wordWrap)
            putInt("editor", "tab_width", tabWidth)
            putBoolean("editor", "line_numbers", lineNumbers)
        }
        with(settings.security) {
            putString("security", "auto_lock", autoLock.tomlName())
            putInt("security", "pin_length", pinLength)
            putBoolean("security", "biometric_unlock", biometricUnlock)
            putBoolean("security", "block_screenshots", blockScreenshots)
            putBoolean("security", "clipboard_sensitive", clipboardSensitive)
        }
        with(settings.session) {
            putString("session", "secondary_session_id", secondarySessionId)
            putString("session", "presentation", presentation.tomlName())
            putFloat("session", "split_fraction", splitFraction)
            putFloat("session", "float_left", floatLeft)
            putFloat("session", "float_top", floatTop)
            putFloat("session", "float_width", floatWidth)
            putFloat("session", "float_height", floatHeight)
            putBoolean("session", "maximized", maximized)
        }
        with(settings.meta) {
            putBoolean("meta", "first_launch_completed", firstLaunchCompleted)
            putInt("meta", "settings_version", settingsVersion)
        }
    }

    fun fromDocument(document: TomlDocument): DroshSettings {
        val base = DroshSettings.DEFAULT
        return DroshSettings(
            appearance = base.appearance.copy(
                themeMode = ThemeMode.fromName(document.getString("appearance", "theme_mode", null)),
                fontPack = FontPack.fromName(document.getString("appearance", "font_pack", null)),
                language = document.getString("appearance", "language", base.appearance.language),
            ),
            terminal = base.terminal.copy(
                mode = TerminalMode.fromToml(document.getString("terminal", "mode", null)),
                defaultFontSizeSp = document.getFloat(
                    "terminal", "default_font_size_sp", base.terminal.defaultFontSizeSp,
                ),
                cursorStyle = CursorStyle.fromString(document.getString("terminal", "cursor_style", "")),
                cursorBlinkMs = document.getInt("terminal", "cursor_blink_ms", base.terminal.cursorBlinkMs),
                scrollbackRows = document.getInt("terminal", "scrollback_rows", base.terminal.scrollbackRows),
                bell = BellMode.fromName(document.getString("terminal", "bell", null)),
                keepAwake = document.getBoolean("terminal", "keep_awake", base.terminal.keepAwake),
                immersive = document.getBoolean("terminal", "immersive", base.terminal.immersive),
                linkDetection = document.getBoolean("terminal", "link_detection", base.terminal.linkDetection),
                glassEffects = document.getBoolean("terminal", "glass_effects", base.terminal.glassEffects),
                background = document.getString("terminal", "background", base.terminal.background),
                foreground = document.getString("terminal", "foreground", base.terminal.foreground),
                accent = document.getString("terminal", "accent", base.terminal.accent),
            ),
            input = base.input.copy(
                extraKeysBar = document.getBoolean("input", "extra_keys_bar", base.input.extraKeysBar),
                hideBarOnHardwareKeyboard = document.getBoolean(
                    "input", "hide_bar_hardware_keyboard", base.input.hideBarOnHardwareKeyboard,
                ),
                hideBarWhenDroshIme = document.getBoolean(
                    "input", "hide_bar_drosh_ime", base.input.hideBarWhenDroshIme,
                ),
            ),
            shell = base.shell.copy(
                shell = shellChoiceFrom(document.getString("shell", "shell", null)),
                omzTheme = document.getString("shell", "omz_theme", base.shell.omzTheme),
                omzPlugins = document.getStringList("shell", "omz_plugins", base.shell.omzPlugins),
                historySize = document.getInt("shell", "history_size", base.shell.historySize),
                motdMode = MotdMode.fromString(document.getString("shell", "motd_mode", "")),
                motdText = document.getString("shell", "motd_text", base.shell.motdText),
                startupCommand = document.getString("shell", "startup_command", base.shell.startupCommand),
                shellIntegration = document.getBoolean(
                    "shell", "shell_integration", base.shell.shellIntegration,
                ),
            ),
            rootfs = base.rootfs.copy(
                packageProfile = packageProfileFrom(document.getString("rootfs", "package_profile", null)),
                extraPackages = document.getStringList("rootfs", "extra_packages", base.rootfs.extraPackages),
                installDedit = document.getBoolean("rootfs", "install_dedit", base.rootfs.installDedit),
                optimizeOnSetup = document.getBoolean(
                    "rootfs", "optimize_on_setup", base.rootfs.optimizeOnSetup,
                ),
                maxEditSizeKb = document.getInt("rootfs", "max_edit_size_kb", base.rootfs.maxEditSizeKb),
            ),
            editor = base.editor.copy(
                fontSizeSp = document.getFloat("editor", "font_size_sp", base.editor.fontSizeSp),
                wordWrap = document.getBoolean("editor", "word_wrap", base.editor.wordWrap),
                tabWidth = document.getInt("editor", "tab_width", base.editor.tabWidth),
                lineNumbers = document.getBoolean("editor", "line_numbers", base.editor.lineNumbers),
            ),
            security = base.security.copy(
                autoLock = AutoLockTimeout.fromString(document.getString("security", "auto_lock", "")),
                pinLength = document.getInt("security", "pin_length", base.security.pinLength),
                biometricUnlock = document.getBoolean(
                    "security", "biometric_unlock", base.security.biometricUnlock,
                ),
                blockScreenshots = document.getBoolean(
                    "security", "block_screenshots", base.security.blockScreenshots,
                ),
                clipboardSensitive = document.getBoolean(
                    "security", "clipboard_sensitive", base.security.clipboardSensitive,
                ),
            ),
            session = base.session.copy(
                secondarySessionId = document.getString(
                    "session", "secondary_session_id", base.session.secondarySessionId,
                ),
                presentation = panePresentationFrom(document.getString("session", "presentation", null)),
                splitFraction = document.getFloat("session", "split_fraction", base.session.splitFraction),
                floatLeft = document.getFloat("session", "float_left", base.session.floatLeft),
                floatTop = document.getFloat("session", "float_top", base.session.floatTop),
                floatWidth = document.getFloat("session", "float_width", base.session.floatWidth),
                floatHeight = document.getFloat("session", "float_height", base.session.floatHeight),
                maximized = document.getBoolean("session", "maximized", base.session.maximized),
            ),
            meta = base.meta.copy(
                firstLaunchCompleted = document.getBoolean(
                    "meta", "first_launch_completed", base.meta.firstLaunchCompleted,
                ),
                settingsVersion = document.getInt("meta", "settings_version", base.meta.settingsVersion),
            ),
        )
    }
}

/**
 * The header written above the settings.
 *
 * Comments matter here more than anywhere else in the app: this file is meant
 * to be read from a shell, and the first thing a reader should learn is that
 * editing it is expected and safe.
 */
internal const val SETTINGS_FILE_HEADER: String = """
    # Drosh settings.
    #
    # This file is the source of truth: the Settings screen reads and writes
    # it, and so can you — from a shell, from a script, or from the agent.
    # The app picks up edits made here while it runs. Values are lower-case;
    # a bad line is refused rather than guessed at, and the app falls back to
    # its backup copy at <files>/.drosh/settings.toml.
    #
    # API keys do not live here. They stay in encrypted app storage.
""".trimIndent()


/**
 * Lower-case in the file, case-insensitive on the way back in — the shape a
 * hand-edit expects (`shell = "bash"`), and the safety an older build's
 * PascalCase value needs.
 */
private fun ThemeMode.tomlName(): String = name.lowercase()
private fun FontPack.tomlName(): String = name.lowercase()
private fun CursorStyle.tomlName(): String = name.lowercase()
private fun BellMode.tomlName(): String = name.lowercase()
private fun MotdMode.tomlName(): String = name.lowercase()
private fun AutoLockTimeout.tomlName(): String = name.lowercase()
private fun ShellChoice.tomlName(): String = name.lowercase()
private fun PackageProfile.tomlName(): String = name.lowercase()
private fun PanePresentation.tomlName(): String = name.lowercase()
private fun TerminalMode.tomlName(): String = name.lowercase()

private fun BellMode.Companion.fromName(value: String?): BellMode =
    BellMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: BellMode.None

private fun shellChoiceFrom(value: String?): ShellChoice =
    ShellChoice.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ShellChoice.Zsh

private fun packageProfileFrom(value: String?): PackageProfile =
    PackageProfile.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PackageProfile.Standard

private fun panePresentationFrom(value: String?): PanePresentation =
    PanePresentation.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PanePresentation.DOCKED
