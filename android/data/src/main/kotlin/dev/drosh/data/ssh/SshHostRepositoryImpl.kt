package dev.drosh.data.ssh

import dev.drosh.data.local.DroshDatabase
import dev.drosh.domain.ssh.AuthMethod
import dev.drosh.domain.ssh.SshHost
import dev.drosh.domain.ssh.SshHostRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshHostRepositoryImpl @Inject constructor(
    database: DroshDatabase,
) : SshHostRepository {

    private val dao: SshHostDao = database.sshHostDao()

    private val json = Json { ignoreUnknownKeys = true }

    override fun observeAll(): Flow<List<SshHost>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observe(id: String): Flow<SshHost?> =
        dao.observe(id).map { it?.toDomain() }

    override suspend fun get(id: String): SshHost? = dao.get(id)?.toDomain()

    override suspend fun add(host: SshHost): String {
        dao.upsert(host.toEntity())
        return host.id
    }

    override suspend fun update(host: SshHost) {
        dao.upsert(host.toEntity())
    }

    override suspend fun delete(id: String) {
        dao.delete(id)
    }

    override suspend fun touch(id: String, nowMs: Long) {
        dao.touch(id, nowMs)
    }

    private fun SshHostEntity.toDomain(): SshHost = SshHost(
        id = id,
        name = name,
        hostname = hostname,
        port = port,
        username = username,
        authMethod = AuthMethod.valueOf(authMethod),
        keyId = keyId,
        jumpHostId = jumpHostId,
        isProduction = isProduction,
        tags = json.decodeFromString(ListSerializer(String.serializer()), tagsJson),
        lastUsedAtMs = lastUsedAtMs,
        createdAtMs = createdAtMs,
    )

    private fun SshHost.toEntity(): SshHostEntity = SshHostEntity(
        id = id,
        name = name,
        hostname = hostname,
        port = port,
        username = username,
        authMethod = authMethod.name,
        keyId = keyId,
        jumpHostId = jumpHostId,
        isProduction = isProduction,
        tagsJson = json.encodeToString(ListSerializer(String.serializer()), tags),
        lastUsedAtMs = lastUsedAtMs,
        createdAtMs = createdAtMs,
    )
}
