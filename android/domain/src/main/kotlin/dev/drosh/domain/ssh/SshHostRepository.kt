package dev.drosh.domain.ssh

import kotlinx.coroutines.flow.Flow

/**
 * Host CRUD. Backed by Room via `data/ssh/SshRepositoryImpl`.
 */
interface SshHostRepository {
    fun observeAll(): Flow<List<SshHost>>
    fun observe(id: String): Flow<SshHost?>
    suspend fun get(id: String): SshHost?
    suspend fun add(host: SshHost): String
    suspend fun update(host: SshHost)
    suspend fun delete(id: String)
    suspend fun touch(id: String, nowMs: Long)
}
