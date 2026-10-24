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
    /**
     * What the composer's `+` sheet turns.
     *
     * A section of its own rather than two more fields on [AgentSettings],
     * because these are settings about the *tools* rather than about how much
     * the agent is trusted: turning web search off is not a permission, and
     * reading it as one makes the trust level look like the thing that decides
     * whether the agent can reach the network.
     */
    val agentTools: AgentToolSettings = AgentToolSettings(),
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
    /**
     * A font file of the user's own, for the terminal.
     *
     * The URI of a font the user picked with the system picker; empty means
     * the bundled face. The engine is handed the file's bytes and measures
     * its cell from it, so the grid is laid out around the font the user
     * chose.
     */
    val customFontUri: String = "",
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
    /** Which isolation engine runs the Linux guest. */
    val guestIsolation: GuestIsolation = GuestIsolation.DEFAULT,
    /**
     * Which terminal implementation the app runs.
     *
     * Termux until the engine has been through a device: the vendored path is
     * the one the app is known to work on, and the setting is what lets the
     * engine be tried without a rebuild.
     */
    /** The size a session opens at. A pinch moves the session, not this. */
    val defaultFontSizeSp: Float = TerminalZoom.DEFAULT_SP,
    val cursorStyle: CursorStyle = CursorStyle.Block,
    /** Milliseconds; 0 is off. The view accepts 100..2000. */
    val cursorBlinkMs: Int = 500,
    val keepAwake: Boolean = false,
    /**
     * The terminal keeps its place while the user reads.
     *
     * New output pulls the viewport back to the live edge, which is right
     * for output the shell produced on its own and wrong for the user who
     * has scrolled up to read something. With this on, a terminal that is
     * scrolled up stays where it is; scrolling to the bottom by hand is
     * still what brings it back.
     */
    val keepPositionWhileReading: Boolean = false,
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
    /** Custom guest start command; "" is the default shell. */
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

/**
 * What the composer can hand to a run.
 *
 * ## Why these two and not one
 *
 * Web search and reasoning effort are the two things the `+` sheet toggles, and
 * they are settings about *the tools* rather than about trust or appearance —
 * which is what `[agent]` in the file holds. They are their own section so a
 * later addition to the trust settings does not have to grow a data class that
 * this one already answers for.
 *
 * ## Why the side-job model is here and not in [AgentSettings]
 *
 * It is a question about *how the agent works*, not about how much of it is
 * trusted: naming a conversation is not a permission, and reading it as one
 * would put a model choice next to "always allow `git status`" and imply the
 * two are the same kind of decision. It sits beside `effort`, which is the other
 * capability knob the `+` sheet turns.
 *
 * ## Why they are in the file at all
 *
 * Both survive a process restart by being in `settings.toml`, which is also
 * where a shell or a script reads them. A web-search preference that lived only
 * in a `StateFlow` would quietly turn itself back on at the next launch.
 */
data class AgentToolSettings(
    /**
     * Whether `web_search` is offered to the model at all.
     *
     * Off means the tool is not declared to the model, rather than declared and
     * refused: a tool the model can see but cannot use is one it keeps trying,
     * and each refusal costs a turn of the run's budget.
     *
     * On by default. An agent that cannot look something up guesses instead,
     * and guessing is the more expensive failure.
     */
    val webSearch: Boolean = true,
    /**
     * Reasoning effort, spelled the way the model names it.
     *
     * Empty means the model's own default, which is what a user who has never
     * thought about it wants. See `dev.drosh.agent.provider.EffortMapper` for
     * how each protocol spells the same value.
     */
    val effort: String = "",
    /**
     * The model that answers the jobs the user did not ask for.
     *
     * Naming a conversation is the one today: a second request, made beside the
     * answer, whose only input is the first prompt. It is also a request nobody
     * is waiting on, which makes it the obvious thing to hand to a small model —
     * and a session on a frontier model that pays frontier prices for a title is
     * the obvious way to make that session feel expensive.
     *
     * A bare model id, resolved against the catalog rather than against the
     * provider in use: the cheap model is usually on some other provider. Empty
     * means the conversation's own model handles it, which is what a user who
     * never opened this setting gets. See `SideJobModel.resolve` for how an id
     * becomes a provider and a model, and for the fallback when it resolves to
     * nothing.
     */
    val sideJobModel: String = "",
) {
    companion object {
        val DEFAULT = AgentToolSettings()
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
 * How much the agent may do before it has to ask.
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
) {
    companion object {
        val DEFAULT = AgentSettings()
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

