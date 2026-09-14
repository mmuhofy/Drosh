package com.iris.irisshell.data.settings

import android.content.SharedPreferences
import com.iris.irisshell.domain.settings.PinLockRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import com.iris.irisshell.data.di.SecurityModule.PinPref

/**
 * EncryptedSharedPreferences-backed impl of [PinLockRepository].
 *
 * PIN is hashed with SHA-256 before storage. The shared-prefs file itself
 * is encrypted at rest via AndroidX Security Crypto AES-256.
 *
 * Security notes:
 *  - PIN_LENGTH is fixed at 4 (per spec).
 *  - No salt — acceptable for a 4-digit PIN; the real protection is the
 *    EncryptedSharedPreferences master key tied to Android Keystore.
 *  - On Android 7-8 (API 26-27), EncryptedSharedPreferences uses fallback
 *    unencrypted prefs with a warning; acceptable per minSdk 26 target.
 */
@Singleton
class PinLockRepositoryImpl @Inject constructor(
    @PinPref private val prefs: SharedPreferences,
) : PinLockRepository {

    private val _enabled = MutableStateFlow(isPinStored())

    override val isEnabled: Flow<Boolean> = _enabled.asStateFlow()

    override suspend fun setPin(pin: String) {
        require(pin.length == PinLockRepository.PIN_LENGTH) {
            "PIN must be ${PinLockRepository.PIN_LENGTH} digits"
        }
        require(pin.all { it.isDigit() }) {
            "PIN must be all digits"
        }

        val hash = sha256Hex(pin)
        prefs.edit()
            .putString(KEY_PIN_HASH, hash)
            .putBoolean(KEY_PIN_ENABLED, true)
            .apply()

        _enabled.value = true
    }

    override suspend fun verify(pin: String): Boolean {
        val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val inputHash = sha256Hex(pin)
        return inputHash == storedHash
    }

    override suspend fun clearPin() {
        prefs.edit()
            .remove(KEY_PIN_HASH)
            .putBoolean(KEY_PIN_ENABLED, false)
            .apply()
        _enabled.value = false
    }

    override suspend fun setEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_PIN_ENABLED, enabled)
            .apply()
        _enabled.value = enabled && isPinStored()
    }

    private fun isPinStored(): Boolean =
        prefs.contains(KEY_PIN_HASH) && prefs.getBoolean(KEY_PIN_ENABLED, false)

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val KEY_PIN_HASH = "pin_hash"
        const val KEY_PIN_ENABLED = "pin_enabled"
    }
}
