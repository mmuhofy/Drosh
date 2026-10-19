package dev.drosh.data.ssh

import android.content.SharedPreferences
import dev.drosh.domain.ssh.SshCredentialVault
import javax.inject.Inject
import javax.inject.Singleton
import dev.drosh.data.di.SecurityModule.SshPref

/**
 * EncryptedSharedPreferences-backed credential vault.
 *
 * Values are only encrypted-at-rest strings; access is gated through the
 * Hilt-bound [SshCredentialVault] interface so UI never touches the raw
 * prefs.
 */
@Singleton
class SshCredentialVaultImpl @Inject constructor(
    @SshPref private val prefs: SharedPreferences,
) : SshCredentialVault {

    override suspend fun setHostPassword(hostId: String, password: String) {
        prefs.edit().putString("password_$hostId", password).apply()
    }

    override suspend fun getHostPassword(hostId: String): String? =
        prefs.getString("password_$hostId", null)

    override suspend fun clearHostPassword(hostId: String) {
        prefs.edit().remove("password_$hostId").apply()
    }

    override suspend fun setPrivateKey(keyId: String, privateKeyPem: String, passphrase: String?) {
        prefs.edit()
            .putString("pem_$keyId", privateKeyPem)
            .putString("passphrase_$keyId", passphrase)
            .apply()
    }

    override suspend fun getPrivateKeyPem(keyId: String): String? =
        prefs.getString("pem_$keyId", null)

    override suspend fun getPrivateKeyPassphrase(keyId: String): String? =
        prefs.getString("passphrase_$keyId", null)

    override suspend fun clearPrivateKey(keyId: String) {
        prefs.edit().remove("pem_$keyId").remove("passphrase_$keyId").apply()
    }

    override suspend fun setHostFingerprint(hostId: String, fingerprintHex: String) {
        prefs.edit().putString("fingerprint_$hostId", fingerprintHex).apply()
    }

    override suspend fun getHostFingerprint(hostId: String): String? =
        prefs.getString("fingerprint_$hostId", null)
}
