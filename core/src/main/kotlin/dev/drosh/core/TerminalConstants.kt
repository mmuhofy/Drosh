package dev.drosh.core

object TerminalConstants {
    const val NOTIFICATION_ID = 1337

    const val CHANNEL_ID = "dev.drosh_terminal_service"

    const val ACTION_STOP = "dev.drosh.action.STOP_SERVICE"

    // ── MOTD default text ───────────────────────────────────────────────────────

    /** Default MOTD banner echoed by the shell when mode is PlainText. */
    val DEFAULT_MOTD_TEXT: String = """
        ╔══════════════════════════════════════════╗
        ║        Welcome to Drosh v1.0           ║
        ║     Your phone is a Unix machine.     ║
        ╚══════════════════════════════════════════╝
    """.trimIndent()
}
