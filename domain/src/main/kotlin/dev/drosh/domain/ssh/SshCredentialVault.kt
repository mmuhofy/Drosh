package dev.drosh.domain.ssh

/**
 * Encrypted store for host passwords and key material.
 *
 * Backed by EncryptedSharedPreferences (data layer). Host passwords and
 * private keys never leave this store.
 */
interface SshCredentialVault {
    suspend fun setHostPassword(hostId: String, password: String)
    suspend fun getHostPassword(hostId: String): String?
    suspend fun clearHostPassword(hostId: String)

    suspend fun setPrivateKey(keyId: String, privateKeyPem: String, passphrase: String?)
    suspend fun getPrivateKeyPem(keyId: String): String?
    suspend fun getPrivateKeyPassphrase(keyId: String): String?
    suspend fun clearPrivateKey(keyId: String)

    suspend fun setHostFingerprint(hostId: String, fingerprintHex: String)
    suspend fun getHostFingerprint(hostId: String): String?
}
