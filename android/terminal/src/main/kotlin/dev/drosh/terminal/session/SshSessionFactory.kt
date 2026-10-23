package dev.drosh.terminal.session

import dev.drosh.domain.ssh.SshHost
import dev.drosh.terminal.engine.core.EngineSession

/**
 * Builds a bridged session for one host: the session's byte source is the SSH
 * channel, not a local subprocess.
 *
 * Returns the native bridged-session handle from
 * [EngineSession.createBridgedSession]. The caller wraps it in an
 * [EngineSession] and registers it via [TerminalManager.addEngineTabWithId].
 *
 * Lives in `:terminal` because it returns a handle owned by the engine core,
 * and the concrete SSHJ wiring lives in `:ssh` (which `:terminal` cannot
 * import). `:app` injects the implementation into [TerminalManager].
 */
interface SshSessionFactory {
    /**
     * Suspending: connection + auth happens here. Throws on failure; the
     * caller surfaces the error before a tab is opened.
     *
     * Returns the native bridged-session handle (> 0), or a negative error
     * code.
     */
    suspend fun createSession(host: SshHost, columns: Int, rows: Int, cellWidth: Int, cellHeight: Int): Long
}
