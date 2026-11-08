package dev.drosh.terminal.session

import dev.drosh.terminal.engine.core.EngineSession

/**
 * A wrapper that bundles a live engine pane with its persistent metadata
 * (id, name, shell pid).
 *
 * ## Why the pane is typed to the interface
 *
 * The manager and the session list do not care which engine draws the pane,
 * only that it is one it can focus, release and name. Typing the pane to the
 * interface keeps the session grid engine-agnostic; the one place that needs
 * the old engine's own session object casts for it.
 *
 * Ported from: mmuhofy/IrisCode — terminal/TerminalManager.kt
 * Adapted for Drosh — dev.drosh
 */
class EngineDroshSession(
    var enginePane: TerminalEngine? = null,
    val persistentId: String,
    var name: String,
    var pid: Int = 0,
    var isFallbackSession: Boolean = false,
) {
    /**
     * The old engine's session behind the pane, or null when the pane runs on
     * the new one (which has no `EngineSession` object) or has not been
     * registered yet.
     */
    val session: EngineSession? get() = (enginePane as? EngineTerminal)?.session
}
