package com.iris.irisshell.domain.settings

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

    /** Persists a new 4-digit PIN. Throws on invalid input. */
    suspend fun setPin(pin: String)

    /** Returns true iff [pin] matches the stored hash. Must be called only when [isEnabled] is true. */
    suspend fun verify(pin: String): Boolean

    /** Removes the PIN entirely (disables the lock). */
    suspend fun clearPin()

    /** Convenience: enable/disable without clearing the stored PIN. */
    suspend fun setEnabled(enabled: Boolean)

    companion object {
        const val PIN_LENGTH = 4
    }
}
