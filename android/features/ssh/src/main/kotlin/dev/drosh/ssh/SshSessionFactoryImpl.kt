package dev.drosh.ssh

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.drosh.domain.ssh.AuthMethod
import dev.drosh.domain.ssh.SshCredentialVault
import dev.drosh.domain.ssh.SshHost
import dev.drosh.terminal.engine.core.EngineSession
import dev.drosh.terminal.session.SshSessionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.PTYMode
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connects over SSHJ and bridges the shell channel into a bridged session
 * running in the Rust engine core.
 *
 * Returns the native bridged-session handle; the caller wraps it in an
 * [EngineSession] and registers it via [TerminalManager.addEngineTabWithId].
 */
@Singleton
class SshSessionFactoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vault: SshCredentialVault,
) : SshSessionFactory {

    override suspend fun createSession(
        host: SshHost,
        columns: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
    ): Long {
        val config = DefaultConfig()
        config.setKeepAliveProvider(KeepAliveProvider.KEEP_ALIVE)

        val ssh = SSHClient(config)
        ssh.addHostKeyVerifier(TrustOnFirstUseVerifier(vault, host.id))
        withContext(Dispatchers.IO) {
            try {
                ssh.connect(host.hostname, host.port)
                ssh.connection.keepAlive.keepAliveInterval = KEEP_ALIVE_INTERVAL_S
                authenticate(ssh, host)
            } catch (t: Throwable) {
                runCatching { ssh.disconnect() }
                throw t
            }
        }

        val channel = ssh.startSession()
        channel.allocatePTY("xterm-256color", columns, rows, 0, 0, emptyMap<PTYMode, Int>())
        val shell = channel.startShell()

        // Create the bridged session in the engine core.
        val handle = EngineSession.createBridgedSession(columns, rows, cellWidth, cellHeight)
        if (handle <= 0L) {
            runCatching { channel.close() }
            runCatching { ssh.disconnect() }
            throw IllegalStateException("Failed to create bridged session for ${host.name}")
        }

        // Forward channel output into the bridged session.
        Thread({
            try {
                val input = shell.inputStream
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    EngineSession.feedBridgedSession(handle, buffer.copyOfRange(0, read))
                }
            } catch (_: Exception) {
                // Connection dropped — fall through to finish.
            } finally {
                EngineSession.closeBridgedSession(handle)
                runCatching { channel.close() }
                runCatching { ssh.disconnect() }
            }
        }, "SshChannelReader[${host.id}]").start()

        return handle
    }

    private suspend fun authenticate(ssh: SSHClient, host: SshHost) {
        when (host.authMethod) {
            AuthMethod.PASSWORD -> {
                val password = vault.getHostPassword(host.id)
                    ?: throw IllegalStateException("No saved password for ${host.name}")
                withContext(Dispatchers.IO) { ssh.authPassword(host.username, password.toCharArray()) }
            }
            AuthMethod.KEY,
            AuthMethod.KEY_WITH_PASSPHRASE,
            -> {
                val pem = vault.getPrivateKeyPem(host.keyId ?: "")
                    ?: throw IllegalStateException("No saved private key for ${host.keyId}")
                val passphrase = vault.getPrivateKeyPassphrase(host.keyId ?: "")
                val pemFile = File(context.cacheDir, "ssh_pem_${host.keyId}")
                pemFile.writeText(pem)
                try {
                    withContext(Dispatchers.IO) {
                        ssh.loadKeys(pemFile.absolutePath, passphrase)
                            .let { provider -> ssh.authPublickey(host.username, provider) }
                    }
                } finally {
                    pemFile.delete()
                }
            }
        }
    }

    /**
     * First-seen a host key → accept and remember; changed key → reject.
     *
     * Fingerprint stored in the credential prefs (encrypted at rest), keyed
     * per host.
     */
    private class TrustOnFirstUseVerifier(
        private val vault: SshCredentialVault,
        private val hostId: String,
    ) : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(key.encoded)
                .joinToString("") { "%02x".format(it) }
            val stored = kotlinx.coroutines.runBlocking { vault.getHostFingerprint(hostId) }
            return when {
                stored == null -> {
                    kotlinx.coroutines.runBlocking { vault.setHostFingerprint(hostId, fingerprint) }
                    true
                }
                stored == fingerprint -> true
                else -> false
            }
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    private companion object {
        const val KEEP_ALIVE_INTERVAL_S = 30
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
    }
}
