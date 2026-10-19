package dev.drosh.domain.settings

/**
 * How the shell greeting (MOTD) is rendered when a PTY session connects.
 *
 * - [Disabled]:  No MOTD is shown at all.
 * - [PlainText]: The shell prints [SettingsRepository.motdText] via `.zshrc`;
 *                captured by the block engine as a boot block (terminal-native look).
 * - [Compose]:   The shell does NOT print anything; the UI renders an interactive
 *                Compose widget (with actions and system info) instead.
 */
enum class MotdMode {
    Disabled,
    PlainText,
    Compose;

    companion object {
        fun fromString(value: String): MotdMode = entries.find {
            it.name.equals(value, ignoreCase = true)
        } ?: PlainText
    }
}
