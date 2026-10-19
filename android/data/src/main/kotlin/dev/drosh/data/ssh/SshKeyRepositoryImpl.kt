package dev.drosh.data.ssh

import dev.drosh.data.local.DroshDatabase
import dev.drosh.domain.ssh.KeyAlgorithm
import dev.drosh.domain.ssh.SshKey
import dev.drosh.domain.ssh.SshKeyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshKeyRepositoryImpl @Inject constructor(
    database: DroshDatabase,
) : SshKeyRepository {

    private val dao: SshKeyDao = database.sshKeyDao()

    override fun observeAll(): Flow<List<SshKey>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observe(id: String): Flow<SshKey?> =
        dao.observe(id).map { it?.toDomain() }

    override suspend fun get(id: String): SshKey? = dao.get(id)?.toDomain()

    override suspend fun add(key: SshKey): String {
        dao.upsert(key.toEntity())
        return key.id
    }

    override suspend fun delete(id: String) {
        dao.delete(id)
    }

    override suspend fun touch(id: String, nowMs: Long) {
        dao.touch(id, nowMs)
    }

    private fun SshKeyEntity.toDomain(): SshKey = SshKey(
        id = id,
        name = name,
        algorithm = KeyAlgorithm.valueOf(algorithm),
        publicKeyOpenssh = publicKeyOpenssh,
        comment = comment,
        createdAtMs = createdAtMs,
        lastUsedAtMs = lastUsedAtMs,
    )

    private fun SshKey.toEntity(): SshKeyEntity = SshKeyEntity(
        id = id,
        name = name,
        algorithm = algorithm.name,
        publicKeyOpenssh = publicKeyOpenssh,
        comment = comment,
        createdAtMs = createdAtMs,
        lastUsedAtMs = lastUsedAtMs,
    )
}
