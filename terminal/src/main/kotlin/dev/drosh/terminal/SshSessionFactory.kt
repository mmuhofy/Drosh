package dev.drosh.terminal

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dev.drosh.domain.ssh.SshHost

/**
 * Builds a bridged [TerminalSession] for one host: the session's byte source
 * is the SSH channel, not a local subprocess.
 *
 * Lives in `:terminal` because it must return a `TerminalSession`, and the
 * concrete SSHJ wiring lives in `:ssh` (which `:terminal` cannot import).
 * `:app` injects the implementation into [TerminalManager].
 */
interface SshSessionFactory {
    /**
     * Suspending: connection + auth happens here. Throws on failure; the
     * caller surfaces the error before a tab is opened.
     */
    suspend fun createSession(host: SshHost, client: TerminalSessionClient): TerminalSession
}
