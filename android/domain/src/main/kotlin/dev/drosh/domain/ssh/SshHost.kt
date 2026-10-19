package dev.drosh.domain.ssh

/**
 * A saved remote host.
 *
 * Metadata only — passwords and private keys are never stored on the row.
 * [keyId] points into the key vault; [authMethod] decides how a session
 * authenticates. Mirrors MEMORYBANK.md §12.
 */
data class SshHost(
    val id: String,
    val name: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val authMethod: AuthMethod,
    val keyId: String? = null,
    val jumpHostId: String? = null,
    val isProduction: Boolean = false,
    val tags: List<String> = emptyList(),
    val lastUsedAtMs: Long = 0L,
    val createdAtMs: Long = 0L,
)

enum class AuthMethod {
    PASSWORD,
    KEY,
    KEY_WITH_PASSPHRASE,
}
