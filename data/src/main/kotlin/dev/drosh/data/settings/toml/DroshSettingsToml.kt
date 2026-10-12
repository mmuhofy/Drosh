package dev.drosh.data.settings.toml

import dev.drosh.core.toml.TomlDocument
import dev.drosh.domain.settings.AppearanceSettings
import dev.drosh.domain.settings.AutoLockTimeout
import dev.drosh.domain.settings.BackgroundSettings
import dev.drosh.domain.settings.CursorStyle
import dev.drosh.domain.settings.DroshSettings
import dev.drosh.domain.settings.FontPack
import dev.drosh.domain.settings.InputSettings
import dev.drosh.domain.settings.MetaSettings
import dev.drosh.domain.settings.MotdMode
import dev.drosh.domain.settings.SecuritySettings
import dev.drosh.domain.settings.SessionSettings
import dev.drosh.domain.settings.ShellSettings
import dev.drosh.domain.settings.TerminalMode
import dev.drosh.domain.settings.TerminalSettings
import dev.drosh.domain.settings.ThemeMode
import dev.drosh.domain.settings.ThemePalette
import dev.drosh.domain.terminal.PanePresentation

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
            putBoolean("appearance", "session_surface", sessionSurface)
        }
        with(settings.terminal) {
            putString("terminal", "mode", mode.tomlName())
            putFloat("terminal", "default_font_size_sp", defaultFontSizeSp)
            putString("terminal", "cursor_style", cursorStyle.tomlName())
            putInt("terminal", "cursor_blink_ms", cursorBlinkMs)
            putBoolean("terminal", "keep_awake", keepAwake)
            putBoolean("terminal", "bold_brightens", boldBrightens)
            putBoolean("terminal", "ligatures", ligatures)
            putBoolean("terminal", "dim_inactive_panes", dimInactivePanes)
            putString("terminal", "theme_id", themeId)
        }
        with(settings.background) {
            putBoolean("background", "image_enabled", imageEnabled)
            putInt("background", "image_opacity", imageOpacity)
            putInt("background", "blur_radius", blurRadius)
            putInt("background", "content_padding_dp", contentPaddingDp)
            putInt("background", "corner_radius_dp", cornerRadiusDp)
        }
        with(settings.terminal.palette) {
            putString("palette", "background", background)
            putString("palette", "foreground", foreground)
            putString("palette", "cursor", cursor)
            putString("palette", "selection_background", selectionBackground)
            putString("palette", "selection_foreground", selectionForeground)
            putString("palette", "accent", accent)
            putString("palette", "black", black)
            putString("palette", "red", red)
            putString("palette", "green", green)
            putString("palette", "yellow", yellow)
            putString("palette", "blue", blue)
            putString("palette", "magenta", magenta)
            putString("palette", "cyan", cyan)
            putString("palette", "white", white)
            putString("palette", "bright_black", brightBlack)
            putString("palette", "bright_red", brightRed)
            putString("palette", "bright_green", brightGreen)
            putString("palette", "bright_yellow", brightYellow)
            putString("palette", "bright_blue", brightBlue)
            putString("palette", "bright_magenta", brightMagenta)
            putString("palette", "bright_cyan", brightCyan)
            putString("palette", "bright_white", brightWhite)
        }
        with(settings.input) {
            putBoolean("input", "extra_keys_bar", extraKeysBar)
        }
        with(settings.shell) {
            putString("shell", "motd_mode", motdMode.tomlName())
            putString("shell", "motd_text", motdText)
            putString("shell", "startup_command", startupCommand)
            putString("shell", "locale", locale)
        }
        with(settings.security) {
            putString("security", "auto_lock", autoLock.tomlName())
            putInt("security", "pin_length", pinLength)
            putBoolean("security", "block_screenshots", blockScreenshots)
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
                themeMode = ThemeMode.fromName(document.getStringOrNull("appearance", "theme_mode")),
                fontPack = FontPack.fromName(document.getStringOrNull("appearance", "font_pack")),
                language = document.getString("appearance", "language", base.appearance.language),
                sessionSurface = document.getBoolean(
                    "appearance", "session_surface", base.appearance.sessionSurface,
                ),
            ),
            terminal = base.terminal.copy(
                mode = TerminalMode.fromToml(document.getStringOrNull("terminal", "mode")),
                defaultFontSizeSp = document.getFloat(
                    "terminal", "default_font_size_sp", base.terminal.defaultFontSizeSp,
                ),
                cursorStyle = CursorStyle.fromString(document.getString("terminal", "cursor_style", "")),
                cursorBlinkMs = document.getInt("terminal", "cursor_blink_ms", base.terminal.cursorBlinkMs),
                keepAwake = document.getBoolean("terminal", "keep_awake", base.terminal.keepAwake),
                boldBrightens = document.getBoolean("terminal", "bold_brightens", base.terminal.boldBrightens),
                ligatures = document.getBoolean("terminal", "ligatures", base.terminal.ligatures),
                dimInactivePanes = document.getBoolean("terminal", "dim_inactive_panes", base.terminal.dimInactivePanes),
                themeId = document.getString("terminal", "theme_id", base.terminal.themeId),
                palette = readPalette(document, base.terminal.palette),
            ),
            background = base.background.copy(
                imageEnabled = document.getBoolean("background", "image_enabled", base.background.imageEnabled),
                imageOpacity = document.getInt("background", "image_opacity", base.background.imageOpacity),
                blurRadius = document.getInt("background", "blur_radius", base.background.blurRadius),
                contentPaddingDp = document.getInt("background", "content_padding_dp", base.background.contentPaddingDp),
                cornerRadiusDp = document.getInt("background", "corner_radius_dp", base.background.cornerRadiusDp),
            ),
            input = base.input.copy(
                extraKeysBar = document.getBoolean("input", "extra_keys_bar", base.input.extraKeysBar),
            ),
            shell = base.shell.copy(
                motdMode = MotdMode.fromString(document.getString("shell", "motd_mode", "")),
                motdText = document.getString("shell", "motd_text", base.shell.motdText),
                startupCommand = document.getString("shell", "startup_command", base.shell.startupCommand),
                locale = document.getString("shell", "locale", base.shell.locale),
            ),
            security = base.security.copy(
                autoLock = AutoLockTimeout.fromString(document.getString("security", "auto_lock", "")),
                pinLength = document.getInt("security", "pin_length", base.security.pinLength),
                blockScreenshots = document.getBoolean(
                    "security", "block_screenshots", base.security.blockScreenshots,
                ),
            ),
            session = base.session.copy(
                secondarySessionId = document.getString(
                    "session", "secondary_session_id", base.session.secondarySessionId,
                ),
                presentation = panePresentationFrom(document.getStringOrNull("session", "presentation")),
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
internal val SETTINGS_FILE_HEADER: String = """
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
private fun MotdMode.tomlName(): String = name.lowercase()
private fun AutoLockTimeout.tomlName(): String = name.lowercase()
private fun PanePresentation.tomlName(): String = name.lowercase()
private fun TerminalMode.tomlName(): String = name.lowercase()

private fun TerminalMode.Companion.fromToml(value: String?): TerminalMode =
    TerminalMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: TerminalMode.Stream

private fun panePresentationFrom(value: String?): PanePresentation =
    PanePresentation.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PanePresentation.DOCKED


/** Reads the twenty-two colours; each missing key keeps its default. */
private fun readPalette(document: TomlDocument, base: ThemePalette): ThemePalette =
    base.copy(
        background = document.getString("palette", "background", base.background),
        foreground = document.getString("palette", "foreground", base.foreground),
        cursor = document.getString("palette", "cursor", base.cursor),
        selectionBackground = document.getString("palette", "selection_background", base.selectionBackground),
        selectionForeground = document.getString("palette", "selection_foreground", base.selectionForeground),
        accent = document.getString("palette", "accent", base.accent),
        black = document.getString("palette", "black", base.black),
        red = document.getString("palette", "red", base.red),
        green = document.getString("palette", "green", base.green),
        yellow = document.getString("palette", "yellow", base.yellow),
        blue = document.getString("palette", "blue", base.blue),
        magenta = document.getString("palette", "magenta", base.magenta),
        cyan = document.getString("palette", "cyan", base.cyan),
        white = document.getString("palette", "white", base.white),
        brightBlack = document.getString("palette", "bright_black", base.brightBlack),
        brightRed = document.getString("palette", "bright_red", base.brightRed),
        brightGreen = document.getString("palette", "bright_green", base.brightGreen),
        brightYellow = document.getString("palette", "bright_yellow", base.brightYellow),
        brightBlue = document.getString("palette", "bright_blue", base.brightBlue),
        brightMagenta = document.getString("palette", "bright_magenta", base.brightMagenta),
        brightCyan = document.getString("palette", "bright_cyan", base.brightCyan),
        brightWhite = document.getString("palette", "bright_white", base.brightWhite),
    )
