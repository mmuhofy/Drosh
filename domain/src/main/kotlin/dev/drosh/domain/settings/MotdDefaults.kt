package dev.drosh.domain.settings

/**
 * Defaults that live in `:domain` because the settings model needs them.
 *
 * [DEFAULT_MOTD_TEXT] used to sit in `:core`'s TerminalConstants, which
 * `:domain` cannot see — the module graph runs the other way. It moved here
 * with the rest of the settings surface it belongs to; `:core` keeps the
 * constants that are genuinely cross-cutting (notification ids, channels).
 */
object MotdDefaults {

    /** Default MOTD banner echoed by the shell when mode is PlainText. */
    val DEFAULT_MOTD_TEXT: String = """
        ╔══════════════════════════════════════════╗
        ║        Welcome to Drosh v1.0           ║
        ║     Your phone is a Unix machine.     ║
        ╚══════════════════════════════════════════╝
    """.trimIndent()
}
