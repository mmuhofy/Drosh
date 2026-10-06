package dev.drosh.domain.ssh

/**
 * Opens an interactive SSH session for a saved host.
 *
 * The live session is created in the terminal layer and appears as a
 * regular session tab — it is not reconciled against Room rows the way
 * a local shell session is.
 */
interface SshSessionLauncher {
    /**
     * Returns the new session's persistent id, or throws when the connect
     * fails (auth, network, unknown key).
     */
    suspend fun openSession(hostId: String): String
}
