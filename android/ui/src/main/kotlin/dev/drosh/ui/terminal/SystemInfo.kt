package dev.drosh.ui.terminal

/**
 * Lightweight snapshot of system information shown in the MOTD widget.
 *
 * Fetched by the `app` layer (TerminalScreen) via Android system APIs and
 * passed into [MotdWidget] as an immutable parameter so the widget stays
 * a pure Composable with no Android imports.
 */
data class SystemInfo(
    /** App version string (e.g. "1.0.0"). */
    val version: String,
    /** Human-readable total RAM (e.g. "7.8 GB"). */
    val ramTotal: String,
    /** Human-readable available RAM (e.g. "3.2 GB"). */
    val ramAvailable: String,
    /** Human-readable total storage (e.g. "128 GB"). */
    val storageTotal: String,
    /** Human-readable available storage (e.g. "52 GB"). */
    val storageAvailable: String,
)
