package dev.drosh.data.ssh

import android.content.Context
import dev.drosh.domain.ssh.SshCredentialVault
import dev.drosh.domain.ssh.SshHostRepository
import dev.drosh.domain.ssh.SshSessionLauncher
import dev.drosh.terminal.engine.core.EngineSession
import dev.drosh.terminal.session.EnginePane
import dev.drosh.terminal.session.SshSessionFactory
import dev.drosh.terminal.session.TerminalManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wires SshHost (domain/Room) → SshSessionFactory (:ssh) → TerminalManager
 * (:terminal). Lives in :data because it is the only allowed consumer of
 * all three; Hilt binding lives in BindingsModule.
 *
 * The live session is an engine-backed terminal tab — it is not persisted as
 * a Room session row, so it disappears on process death, exactly like a
 * cached PTY session.
 */
@Singleton
class SshSessionLauncherImpl @Inject constructor(
    private val hosts: SshHostRepository,
    private val vault: SshCredentialVault,
    private val terminalManager: TerminalManager,
    private val factory: SshSessionFactory,
    @ApplicationContext private val context: Context,
) : SshSessionLauncher {

    override suspend fun openSession(hostId: String): String {
        val host = hosts.get(hostId) ?: throw IllegalStateException("Unknown host: $hostId")

        val handle = factory.createSession(host, DEFAULT_COLS, DEFAULT_ROWS, DEFAULT_CELL_WIDTH, DEFAULT_CELL_HEIGHT)

        val pane = EnginePane(context)
        val sessionId = "ssh_${UUID.randomUUID()}"

        pane.attachExisting(handle, DEFAULT_COLS, DEFAULT_ROWS, DEFAULT_CELL_WIDTH, DEFAULT_CELL_HEIGHT)

        terminalManager.addEngineTabWithId(sessionId, host.name, pane)

        touch(hostId)
        return sessionId
    }

    private suspend fun touch(hostId: String) = hosts.touch(hostId, System.currentTimeMillis())

    private companion object {
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
        const val DEFAULT_CELL_WIDTH = 1
        const val DEFAULT_CELL_HEIGHT = 1
    }
}
