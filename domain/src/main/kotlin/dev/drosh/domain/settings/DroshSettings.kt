package dev.drosh.domain.settings

import dev.drosh.domain.terminal.PackageProfile
import dev.drosh.domain.terminal.PanePresentation
import dev.drosh.domain.terminal.ShellChoice
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
    val input: InputSettings = InputSettings(),
    val shell: ShellSettings = ShellSettings(),
    val rootfs: RootfsSettings = RootfsSettings(),
    val editor: EditorSettings = EditorSettings(),
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
 * [background], [foreground] and [accent] have no settings row on purpose:
 * they are what the file and the agent can set, and what a user edits when
 * they want to. The screen shows theme and font; the colour triad stays in
 * the file.
 */
data class TerminalSettings(
    val mode: TerminalMode = TerminalMode.Stream,
    /** The size a session opens at. A pinch moves the session, not this. */
    val defaultFontSizeSp: Float = TerminalZoom.DEFAULT_SP,
    val cursorStyle: CursorStyle = CursorStyle.Block,
    /** Milliseconds; 0 is off. The view accepts 100..2000. */
    val cursorBlinkMs: Int = 500,
    val scrollbackRows: Int = 3000,
    val bell: BellMode = BellMode.None,
    val keepAwake: Boolean = false,
    val immersive: Boolean = true,
    val linkDetection: Boolean = true,
    val glassEffects: Boolean = true,
    val background: String = "#000000",
    val foreground: String = "#E8E8E8",
    val accent: String = "#3B82F6",
) {
    companion object {
        val DEFAULT = TerminalSettings()
    }
}

/** How the keyboard and its bars behave. */
data class InputSettings(
    val extraKeysBar: Boolean = true,
    val hideBarOnHardwareKeyboard: Boolean = true,
    val hideBarWhenDroshIme: Boolean = true,
) {
    companion object {
        val DEFAULT = InputSettings()
    }
}

/** The Linux environment's shell, prompt and greeting. */
data class ShellSettings(
    val shell: ShellChoice = ShellChoice.Zsh,
    val omzTheme: String = "agnoster",
    val omzPlugins: List<String> = listOf("git", "zsh-autosuggestions", "zsh-syntax-highlighting"),
    val historySize: Int = 10000,
    val motdMode: MotdMode = MotdMode.PlainText,
    val motdText: String = MotdDefaults.DEFAULT_MOTD_TEXT,
    /** Custom PRoot start command; "" is the default shell. */
    val startupCommand: String = "",
    /** OSC 133/1337 markers and the `editor` shell function. */
    val shellIntegration: Boolean = true,
) {
    companion object {
        val DEFAULT = ShellSettings()
    }
}

/** The rootfs and what the bootstrap does to it. */
data class RootfsSettings(
    val packageProfile: PackageProfile = PackageProfile.Standard,
    val extraPackages: List<String> = emptyList(),
    val installDedit: Boolean = true,
    val optimizeOnSetup: Boolean = true,
    val maxEditSizeKb: Int = 2048,
) {
    companion object {
        val DEFAULT = RootfsSettings()
    }
}

/** The in-app code editor. */
data class EditorSettings(
    val fontSizeSp: Float = 14f,
    val wordWrap: Boolean = false,
    val tabWidth: Int = 4,
    val lineNumbers: Boolean = true,
) {
    companion object {
        val DEFAULT = EditorSettings()
    }
}

/** Locking and privacy. */
data class SecuritySettings(
    /** The PIN itself lives in encrypted prefs; this is only the timeout. */
    val autoLock: AutoLockTimeout = AutoLockTimeout.Immediately,
    val pinLength: Int = 4,
    val biometricUnlock: Boolean = false,
    val blockScreenshots: Boolean = false,
    /** Marks the clipboard as sensitive so the OS hides a preview of it. */
    val clipboardSensitive: Boolean = true,
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

/** What the terminal does when the shell rings the bell. */
enum class BellMode {
    None,
    Vibrate,
    Sound,
    ;

    fun tomlName(): String = name.lowercase()

    companion object {
        fun fromToml(value: String?): BellMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: None
    }
}
