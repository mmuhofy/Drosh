package dev.drosh.core

object TerminalConstants {
    const val NOTIFICATION_ID = 1337

    const val CHANNEL_ID = "dev.drosh_terminal_service"

    const val ACTION_STOP = "dev.drosh.action.STOP_SERVICE"

    /**
     * The notification's "New session" action. Handled by MainActivity, not the
     * service: a session needs the terminal screen to host its pane, and only
     * the activity can put that screen in front of the user.
     */
    const val ACTION_NEW_SESSION = "dev.drosh.action.NEW_SESSION"

    /** The notification's wake-lock toggle, handled by TerminalService. */
    const val ACTION_WAKE_LOCK = "dev.drosh.action.TOGGLE_WAKE_LOCK"

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
}
