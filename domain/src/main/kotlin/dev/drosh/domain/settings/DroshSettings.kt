package dev.drosh.domain.settings

import dev.drosh.domain.agent.ApprovalRule
import dev.drosh.domain.agent.AutonomyLevel
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
    /** The agent's own trust settings. */
    val agent: AgentSettings = AgentSettings(),
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
    /**
     * Whether the sidebar's session rows sit on a surface of their own.
     *
     * Off, they are lines on the drawer — the current look. On, each row is
     * a tile with a gap between it and the next, which is what matches the
     * settings screen and what a dragged session wants to look like when it
     * lands. The accent mark and the press highlight stay either way.
     */
    val sessionSurface: Boolean = true,
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
    /**
     * Whether the status bar and the pill row hide themselves while the
     * terminal is being scrolled.
     *
     * This was a placeholder for years, which is why it was taken out. It
     * came back as a real setting because the behaviour it names is real:
     * the chrome hides on a scroll down and returns on the way back, and
     * someone who wants the clock always on screen has had no way to say so.
     * Off, the chrome never leaves — the bars stay put whatever the terminal
     * is doing.
     */
    val immersive: Boolean = true,
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

/**
 * How the agent behaves, and how much it may do on its own.
 *
 * The level and the rules live in the file because the file is what survives
 * an app restart, and a permission that evaporates when the process dies is
 * a permission the user has to grant again at the worst possible moment.
 */
data class AgentSettings(
    /**
     * How much the agent may run without asking.
     *
     * [AutonomyLevel.AUTO_SAFE] is the default: read-only commands run, and
     * anything that writes, deletes, installs, escalates or reaches the network
     * is asked. The one below it asks for everything, the one above it asks for
     * nothing — and the deterministic layer in [CommandSafety] refuses its list
     * at every level, because a level is a preference and the list is a floor.
     */
    val autonomy: AutonomyLevel = AutonomyLevel.AUTO_SAFE,
    /**
     * Commands the user has said "always" to.
     *
     * Patterns rather than whole commands: `git status *` covers the hundred
     * statuses a session runs and not the hundred git commands there are.
     */
    val alwaysAllow: List<ApprovalRule> = emptyList(),
    /**
     * Whether the agent may reach the web on its own.
     *
     * On by default because a terminal agent that cannot look something up is
     * an agent that guesses; a user who does not want that can say so here
     * rather than by removing the tool and finding out later.
     */
    val webSearch: Boolean = true,
    /**
     * The reasoning effort a run uses when the user has not picked one for the
     * model in hand.
     *
     * A default rather than a per-model choice because a model that does no
     * reasoning has no effort to pick and a user should not have to know that
     * before typing their first prompt. Kept as the catalog's own spelling;
     * `dev.drosh.agent.provider.EffortMapper` translates it per protocol, and a
     * value the model does not declare falls back to the model's own default.
     */
    val effort: String = AgentEffort.MEDIUM,
) {
    companion object {
        val DEFAULT = AgentSettings()
    }
}

/**
 * The effort levels a run may be started at.
 *
 * Not an enum, because the catalog names them as strings and a closed set here
 * would have to be edited every time a provider invents one. The values below
 * are the ones every current protocol accepts.
 */
object AgentEffort {

    const val MINIMAL: String = "minimal"
    const val LOW: String = "low"
    const val MEDIUM: String = "medium"
    const val HIGH: String = "high"

    /** Default when the file says nothing, or says something unreadable. */
    const val DEFAULT: String = MEDIUM

    /** In ascending order, which is the order a picker renders them in. */
    val VALUES: List<String> = listOf(MINIMAL, LOW, MEDIUM, HIGH)

    /**
     * A value from the file, or [DEFAULT].
     *
     * Case-insensitive so `Medium` in a hand-edited file is the same setting,
     * matching every other string in the settings file.
     */
    fun fromName(value: String?): String = value
        ?.trim()
        ?.lowercase()
        ?.takeIf { it in VALUES }
        ?: DEFAULT
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

