package dev.drosh.data.workspace

import dev.drosh.data.session.SessionDao
import dev.drosh.domain.workspace.Workspace
import dev.drosh.domain.workspace.WorkspaceEdit
import dev.drosh.domain.workspace.WorkspaceRepository
import dev.drosh.domain.workspace.forStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkspaceRepositoryImpl @Inject constructor(
    private val workspaceDao: WorkspaceDao,
    private val sessionDao: SessionDao,
) : WorkspaceRepository {

    override fun observeAll(): Flow<List<Workspace>> =
        workspaceDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeArchived(): Flow<List<Workspace>> =
        workspaceDao.observeArchived().map { rows -> rows.map { it.toDomain() } }

    override fun observe(id: String): Flow<Workspace?> =
        workspaceDao.observe(id).map { it?.toDomain() }

    override suspend fun get(id: String): Workspace? = workspaceDao.get(id)?.toDomain()

    override suspend fun create(edit: WorkspaceEdit): Workspace {
        val stored = edit.forStorage()
        val now = System.currentTimeMillis()
        val entity = WorkspaceEntity(
            id = UUID.randomUUID().toString(),
            name = stored.name,
            rootPath = stored.rootPath,
            description = stored.description.trim(),
            colorSeed = stored.colorSeed,
            createdAtMs = now,
            // Created and opened are the same moment, so a brand new workspace
            // sorts to the top instead of last.
            lastOpenedAtMs = now,
            archived = false,
        )
        workspaceDao.upsert(entity)
        return entity.toDomain()
    }

    override suspend fun update(id: String, edit: WorkspaceEdit) {
        val stored = edit.forStorage()
        workspaceDao.update(
            id = id,
            name = stored.name,
            rootPath = stored.rootPath,
            description = stored.description.trim(),
            colorSeed = stored.colorSeed,
        )
    }

    override suspend fun setArchived(id: String, archived: Boolean) {
        workspaceDao.setArchived(id, archived)
    }

    override suspend fun delete(id: String) {
        // Ungroup first, explicitly. The column is `ON DELETE SET NULL`, which
        // SQLite only honours when the foreign_keys pragma is on, and Room does
        // not guarantee it is. Doing the write here means a deleted workspace
        // never leaves a session pointing at a row that is gone, regardless of
        // the pragma — and the FK is still there to catch anything this misses.
        sessionDao.ungroupWorkspace(id)
        workspaceDao.delete(id)
    }

    override suspend fun touch(id: String) {
        workspaceDao.touch(id, System.currentTimeMillis())
    }

    private fun WorkspaceEntity.toDomain(): Workspace = Workspace(
        id = id,
        name = name,
        rootPath = rootPath,
        description = description,
        colorSeed = colorSeed,
        createdAtMs = createdAtMs,
        lastOpenedAtMs = lastOpenedAtMs,
        archived = archived,
    )
}