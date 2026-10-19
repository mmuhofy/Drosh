package dev.drosh.data.agent

import dev.drosh.domain.agent.AgentChat
import dev.drosh.domain.agent.AgentChatRepository
import dev.drosh.domain.agent.ChatStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent chat storage.
 *
 * Status handling is deliberately forgiving: a row whose stored status is not a
 * known name reads back as [ChatStatus.Idle] rather than throwing. A renamed enum
 * constant must not make every chat in the list unreadable, and the alternative —
 * propagating the exception — would take the whole Agent Home screen down.
 */
@Singleton
class AgentChatRepositoryImpl @Inject constructor(
    private val dao: AgentChatDao,
) : AgentChatRepository {

    override fun observeAll(): Flow<List<AgentChat>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observe(id: String): Flow<AgentChat?> =
        dao.observe(id).map { it?.toDomain() }

    override suspend fun create(name: String, workingDirectory: String): AgentChat {
        val now = System.currentTimeMillis()
        val trimmedName = name.trim().ifEmpty { DEFAULT_NAME }
        val chat = AgentChat(
            id = UUID.randomUUID().toString(),
            name = trimmedName,
            workingDirectory = workingDirectory.trim().ifEmpty { DEFAULT_DIRECTORY },
            createdAtMs = now,
            lastUsedAtMs = now,
            status = ChatStatus.Idle,
        )
        dao.upsert(chat.toEntity())
        return chat
    }

    override suspend fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) dao.rename(id, trimmed)
    }

    override suspend fun updateStatus(id: String, status: ChatStatus) {
        dao.updateStatus(id, status.name)
    }

    override suspend fun touch(id: String) {
        dao.touch(id, System.currentTimeMillis())
    }

    /** Record what the model last said, for the list preview. */
    suspend fun updatePreview(id: String, preview: String) {
        val singleLine = preview.replace(Regex("\\s+"), " ").trim().take(PREVIEW_LIMIT)
        dao.updatePreview(id, singleLine, System.currentTimeMillis())
    }

    override suspend fun delete(id: String) {
        dao.delete(id)
    }

    private fun AgentChatEntity.toDomain() = AgentChat(
        id = id,
        name = name,
        workingDirectory = workingDirectory,
        createdAtMs = createdAtMs,
        lastUsedAtMs = lastUsedAtMs,
        status = runCatching { ChatStatus.valueOf(status) }.getOrDefault(ChatStatus.Idle),
        lastMessagePreview = lastMessagePreview,
    )

    private fun AgentChat.toEntity() = AgentChatEntity(
        id = id,
        name = name,
        workingDirectory = workingDirectory,
        createdAtMs = createdAtMs,
        lastUsedAtMs = lastUsedAtMs,
        status = status.name,
        lastMessagePreview = lastMessagePreview,
    )

    companion object {
        /**
         * The guest home.
         *
         * `ProotRunner` sets HOME=/home, and a chat created with no directory
         * should land where a user's first `cd` would.
         */
        const val DEFAULT_DIRECTORY = "/home"

        /** An agent asked to fix the build is not named "zsh". */
        private const val DEFAULT_NAME = "agent"

        /** Long enough to identify the chat, short enough for two list lines. */
        private const val PREVIEW_LIMIT = 140
    }
}
