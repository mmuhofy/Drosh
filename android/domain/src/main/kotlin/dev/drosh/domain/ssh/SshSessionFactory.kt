package dev.drosh.domain.ssh

/**
 * Builds a bridged session for one host: the session's byte source is the SSH
 * channel, not a local subprocess.
 *
 * ## Why it lives in the domain
 *
 * The seam between the app and the SSH feature is this interface, and the app
 * is the only thing that knows about both. When it lived in `:terminal`, the
 * SSH feature had to import the terminal module to implement it, and the data
 * module had to import the terminal module to hold it — two module boundaries
 * crossed for one interface.
 *
 * The domain owns the SSH vocabulary, so it owns the seam. The handle it
 * returns is the engine's, and the engine is the terminal module's business:
 * the caller wraps the handle in a session and registers it with the terminal
 * manager, which is where a handle belongs.
 */
interface SshSessionFactory {

    /**
     * Suspending: connection and auth happens here. Throws on failure; the
     * caller surfaces the error before a tab is opened.
     *
     * @return the session handle (> 0), or a negative error code.
     */
    suspend fun createSession(
        host: SshHost,
        columns: Int,
        rows: Int,
        cellWidth: Int,
        cellHeight: Int,
    ): Long
}
