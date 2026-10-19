package dev.drosh.domain.ssh

import kotlinx.coroutines.flow.Flow

/**
 * Key metadata CRUD. The private material itself lives in the key vault
 * (EncryptedSharedPreferences + biometric unlock), not in Room.
 */
interface SshKeyRepository {
    fun observeAll(): Flow<List<SshKey>>
    fun observe(id: String): Flow<SshKey?>
    suspend fun get(id: String): SshKey?
    suspend fun add(key: SshKey): String
    suspend fun delete(id: String)
    suspend fun touch(id: String, nowMs: Long)
}
