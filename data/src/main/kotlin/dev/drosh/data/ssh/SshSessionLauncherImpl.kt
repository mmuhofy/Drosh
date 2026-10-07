package dev.drosh.data.ssh

import dev.drosh.domain.ssh.SshCredentialVault
import dev.drosh.domain.ssh.SshHostRepository
import dev.drosh.domain.ssh.SshSessionLauncher
import dev.drosh.terminal.SshSessionFactory
import dev.drosh.terminal.TerminalManager
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wires SshHost (domain/Room) → SshSessionFactory (:ssh) → TerminalManager
 * (:terminal). Lives in :data because it is the only allowed consumer of
 * all three; Hilt binding lives in BindingsModule.
 *
 * The live session is a plain TerminalSession tab — it is not persisted as
 * a Room session row, so it disappears on process death, exactly like a
 * cached PTY session.
 */
@Singleton
class SshSessionLauncherImpl @Inject constructor(
    private val hosts: SshHostRepository,
    private val vault: SshCredentialVault,
    private val terminalManager: TerminalManager,
    private val factory: SshSessionFactory,
) : SshSessionLauncher {

    override suspend fun openSession(hostId: String): String {
        val host = hosts.get(hostId) ?: throw IllegalStateException("Unknown host: $hostId")
        val terminalSession = factory.createSession(host, terminalManager.sessionClient)
        val sessionId = "ssh_${UUID.randomUUID()}"
        terminalManager.addSshSession(sessionId, host.name, terminalSession)
        touch(hostId)
        return sessionId
    }

    private suspend fun touch(hostId: String) = hosts.touch(hostId, System.currentTimeMillis())
}
