package dev.drosh.domain.settings

import kotlinx.coroutines.flow.Flow

/**
 * Secure PIN-lock storage backed by EncryptedSharedPreferences.
 *
 * PIN hash is stored as SHA-256 (hex). Salt is fixed for simplicity —
 * the real protection is the master key from EncryptedSharedPreferences.
 *
 * All methods are suspend except [isEnabled] (a cold Flow that emits on every
 * value change so Settings/PIN gate react instantly).
 */
interface PinLockRepository {

    /** Hot stream: true if a PIN is enrolled and enabled. */
    val isEnabled: Flow<Boolean>

    /**
     * Persists a new PIN of [length] digits. Throws on invalid input.
     *
     * The length comes from the settings file, so the check belongs here
     * rather than in a screen: a PIN enrolled at one length and verified
     * against another would be a lock nobody can open.
     */
    suspend fun setPin(pin: String, length: Int = PIN_LENGTH)

    /** Returns true iff [pin] matches the stored hash. Must be called only when [isEnabled] is true. */
    suspend fun verify(pin: String): Boolean

    /** Removes the PIN entirely (disables the lock). */
    suspend fun clearPin()

    /** Convenience: enable/disable without clearing the stored PIN. */
    suspend fun setEnabled(enabled: Boolean)

    companion object {
        const val PIN_LENGTH = 4

        /** The lengths the entry screens offer. */
        val PIN_LENGTH_RANGE = 4..8
    }
}
