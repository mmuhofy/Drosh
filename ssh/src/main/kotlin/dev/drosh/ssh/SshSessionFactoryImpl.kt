package dev.drosh.ssh

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.drosh.domain.ssh.AuthMethod
import dev.drosh.domain.ssh.SshCredentialVault
import dev.drosh.domain.ssh.SshHost
import dev.drosh.terminal.SshSessionFactory
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
 * Connects over SSHJ and bridges the shell channel into a
 * [TerminalSession] running in bridged mode.
 */
@Singleton
class SshSessionFactoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vault: SshCredentialVault,
) : SshSessionFactory {

    override suspend fun createSession(host: SshHost, client: TerminalSessionClient): TerminalSession {
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
        channel.allocatePTY("xterm-256color", DEFAULT_COLS, DEFAULT_ROWS, 0, 0, emptyMap<PTYMode, Int>())
        val shell = channel.startShell()

        val session = TerminalSession(
            "",
            "/",
            arrayOf(),
            arrayOf("TERM=xterm-256color"),
            null,
            client,
        )
        session.bridged = true
        session.mSessionName = host.name

        session.externalInputSink = { data, offset, count ->
            runCatching {
                shell.outputStream.write(data, offset, count)
                shell.outputStream.flush()
            }
        }
        session.bridgedResizeListener = { cols, rows, width, height ->
            runCatching { shell.changeWindowDimensions(cols, rows, width, height) }
        }

        Thread({
            // The emulator is created on attach (updateSize on the main
            // thread). Bytes from the channel can sit in the stream buffer
            // until then — the channel's receive window holds them, so the
            // pause loses nothing.
            while (session.emulator == null) {
                try { Thread.sleep(50) } catch (_: InterruptedException) { return@Thread }
            }
            try {
                val input = shell.inputStream
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    session.feedBridgedInput(buffer, read)
                }
            } catch (_: Exception) {
                // Connection dropped — fall through to finish.
            } finally {
                session.finishBridged(0)
                runCatching { channel.close() }
                runCatching { ssh.disconnect() }
            }
        }, "SshChannelReader[${host.id}]").start()

        return session
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
                    ?: throw IllegalStateException("No saved private key for ${host.name}")
                val passphrase = vault.getPrivateKeyPassphrase(host.keyId ?: "")
                // loadKeys wants a filesystem path, and the vault keeps the
                // PEM in-memory — so write a temporary, then delete it.
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
