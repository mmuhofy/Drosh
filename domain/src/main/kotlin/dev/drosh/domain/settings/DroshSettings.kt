package dev.drosh.domain.settings

import dev.drosh.domain.terminal.PanePresentation
import dev.drosh.domain.terminal.TerminalZoom

/**
 * Every user preference, as one value.
 *
 * The shape replaces the per-key interface the settings surface grew out of:
 * forty-odd keys are forty-odd flows under that model, and every consumer
 * collects the five it cares about from five places. Here one snapshot
 * travels, a consumer maps the part it needs, and a write is a single
 * transform over the whole thing — which is also what makes the file the
 * store writes behind it cheap to reason about: one snapshot, one render,
 * one atomic replace.
 *
 * Sections mirror the categories of the settings screen, in the order they
 * are shown: the file reads like the screen that edits it.
 */
data class DroshSettings(
    val appearance: AppearanceSettings = AppearanceSettings(),
    val terminal: TerminalSettings = TerminalSettings(),
    val background: BackgroundSettings = BackgroundSettings(),
    val input: InputSettings = InputSettings(),
    val shell: ShellSettings = ShellSettings(),
    val security: SecuritySettings = SecuritySettings(),
    val session: SessionSettings = SessionSettings(),
    val meta: MetaSettings = MetaSettings(),
) {
    companion object {
        val DEFAULT = DroshSettings()
    }
}

/** Theme, type and language — how Drosh looks. */
data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.System,
    val fontPack: FontPack = FontPack.Geist,
    /** BCP-47-ish tag; "" follows the system. */
    val language: String = "",
) {
    companion object {
        val DEFAULT = AppearanceSettings()
    }
}

/**
 * The terminal's own behaviour.
 *
 * The colours are a whole palette rather than three keys: a theme is
 * twenty-two of them, and the file is where they live — the theme store
 * fills them, the agent can edit them, and the screen shows which theme is
 * active rather than each colour.
 */
data class TerminalSettings(
    val mode: TerminalMode = TerminalMode.Stream,
    /** The size a session opens at. A pinch moves the session, not this. */
    val defaultFontSizeSp: Float = TerminalZoom.DEFAULT_SP,
    val cursorStyle: CursorStyle = CursorStyle.Block,
    /** Milliseconds; 0 is off. The view accepts 100..2000. */
    val cursorBlinkMs: Int = 500,
    val keepAwake: Boolean = false,
    /** Bold text in the first eight colours uses the bright half. */
    val boldBrightens: Boolean = true,
    /** Programming-ligature shaping, where the typeface carries them. */
    val ligatures: Boolean = true,
    /** The unfocused pane of a split is drawn dimmer. */
    val dimInactivePanes: Boolean = true,
    /** The colours the terminal draws with; a theme writes all of them. */
    val palette: ThemePalette = ThemePalette.DEFAULT,
    /** Which installed theme put the palette there; "" means the file's own. */
    val themeId: String = "",
) {
    companion object {
        val DEFAULT = TerminalSettings()
    }
}

/**
 * What sits behind and around the terminal.
 *
 * The image is a convention rather than a setting: a file called
 * `background.<ext>` in `.drosh/` *is* the background, so there is no path
 * to get wrong and a shell can put one there as easily as the picker can.
 * [blurRadius] is applied once, to a cached file, rather than every frame.
 */
data class BackgroundSettings(
    /** A background file in `.drosh/` is used when there is one. */
    val imageEnabled: Boolean = true,
    /** 0..100 — how much of the image shows through. */
    val imageOpacity: Int = 100,
    /** 0 is none; anything else is a radius, applied once and cached. */
    val blurRadius: Int = 0,
    /** Pixels of empty space around the terminal grid. */
    val contentPaddingDp: Int = 0,
    /** Rounded corners on the terminal content. */
    val cornerRadiusDp: Int = 0,
) {
    companion object {
        val DEFAULT = BackgroundSettings()
    }
}

/** How the keyboard and its bars behave. */
data class InputSettings(
    val extraKeysBar: Boolean = true,
) {
    companion object {
        val DEFAULT = InputSettings()
    }
}

/**
 * The Linux environment's shell, prompt and greeting.
 *
 * Only what the app actually controls at runtime: the shell it launches,
 * how it greets, and the language it speaks. Anything else — zsh themes,
 * plugins, history size — belongs to the user's dotfiles, which are
 * theirs, not ours to duplicate.
 */
data class ShellSettings(
    val motdMode: MotdMode = MotdMode.PlainText,
    val motdText: String = MotdDefaults.DEFAULT_MOTD_TEXT,
    /** Custom PRoot start command; "" is the default shell. */
    val startupCommand: String = "",
    /**
     * The shell's locale, as LANG/LC_ALL on every session — from the file
     * rather than from the user's dotfiles, so the agent's shell and a
     * session opened next week speak the same one. "" leaves the image's
     * own default alone.
     */
    val locale: String = "",
) {
    companion object {
        val DEFAULT = ShellSettings()
    }
}

/** Locking and privacy. */
data class SecuritySettings(
    /** The PIN itself lives in encrypted prefs; this is only the timeout. */
    val autoLock: AutoLockTimeout = AutoLockTimeout.Immediately,
    val pinLength: Int = 4,
    val blockScreenshots: Boolean = false,
) {
    companion object {
        val DEFAULT = SecuritySettings()
    }
}

/** Pane arrangement — runtime state, but state the user leaves behind. */
data class SessionSettings(
    val secondarySessionId: String = "",
    val presentation: PanePresentation = PanePresentation.DOCKED,
    val splitFraction: Float = 0.5f,
    // The same numbers as NormalizedRect.DEFAULT, which lives in :domain's
    // terminal package and cannot be referenced from here without dragging
    // the pane model into the settings surface.
    val floatLeft: Float = 0.04f,
    val floatTop: Float = 0.12f,
    val floatWidth: Float = 0.92f,
    val floatHeight: Float = 0.70f,
    val maximized: Boolean = false,
) {
    companion object {
        val DEFAULT = SessionSettings()
    }
}

/** Bookkeeping the file carries about itself. */
data class MetaSettings(
    val firstLaunchCompleted: Boolean = false,
    /** Bumped when a load migrates an older layout; read, never shown. */
    val settingsVersion: Int = 1,
) {
    companion object {
        val DEFAULT = MetaSettings()
    }
}

/** Block engine or plain stream. */
enum class TerminalMode {
    Blocks,
    Stream,
    ;

    /** Lower-case in the file, so a hand-edit reads like prose. */
    fun tomlName(): String = name.lowercase()

    companion object {
        fun fromToml(value: String?): TerminalMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: Stream
    }
}

