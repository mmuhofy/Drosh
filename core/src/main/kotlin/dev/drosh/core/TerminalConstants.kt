package dev.drosh.core

object TerminalConstants {
    const val NOTIFICATION_ID = 1337

    const val CHANNEL_ID = "dev.drosh_terminal_service"

    const val ACTION_STOP = "dev.drosh.action.STOP_SERVICE"

    // ── System overlay pane ───────────────────────────────────────────────────

    /**
     * Notification for the floating pane.
     *
     * Deliberately not [NOTIFICATION_ID]: two foreground services sharing an id
     * replace each other's notification, so the one the user sees would depend on
     * which service updated last, and stopping one would cancel the other's.
     */
    const val OVERLAY_NOTIFICATION_ID = 1338

    const val OVERLAY_CHANNEL_ID = "dev.drosh_overlay_service"

    const val ACTION_STOP_OVERLAY = "dev.drosh.action.STOP_OVERLAY"

    // ── MOTD default text ───────────────────────────────────────────────────────

    /** Default MOTD banner echoed by the shell when mode is PlainText. */
    val DEFAULT_MOTD_TEXT: String = """
        ╔══════════════════════════════════════════╗
        ║        Welcome to Drosh v1.0           ║
        ║     Your phone is a Unix machine.     ║
        ╚══════════════════════════════════════════╝
    """.trimIndent()
}
