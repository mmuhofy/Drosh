package dev.drosh.domain.ssh

/**
 * A key held in the vault.
 *
 * Material is stored encrypted (biometric unlock); this row only carries
 * the public half and the reference used to look the private half up.
 */
data class SshKey(
    val id: String,
    val name: String,
    val algorithm: KeyAlgorithm,
    val publicKeyOpenssh: String,
    val comment: String = "",
    val createdAtMs: Long = 0L,
    val lastUsedAtMs: Long = 0L,
)

enum class KeyAlgorithm {
    ED25519,
    RSA,
    ECDSA,
}
