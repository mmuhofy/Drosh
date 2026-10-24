package dev.drosh.terminal.session

import dev.drosh.terminal.engine.core.EngineSession

/**
 * A wrapper that bundles a live [EnginePane] with its persistent
 * metadata (id, name, shell pid).
 *
 * The Rust-engine counterpart of [DroshSession]: where [DroshSession]
 * wraps a vendored terminal session, this wraps an
 * [EnginePane] that owns its own [EngineSession] and view.
 *
 * Ported from: mmuhofy/IrisCode — terminal/TerminalManager.kt
 * Adapted for Drosh — dev.drosh
 */
class EngineDroshSession(
    var enginePane: EnginePane? = null,
    val persistentId: String,
    var name: String,
    var pid: Int = 0,
    var isFallbackSession: Boolean = false,
) {
    /** The engine's session behind the pane, or null when the pane has not been registered yet. */
    val session: EngineSession? get() = enginePane?.session
}
