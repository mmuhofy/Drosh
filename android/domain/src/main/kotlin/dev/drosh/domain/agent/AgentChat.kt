package dev.drosh.domain.agent

import kotlinx.coroutines.flow.Flow

/**
 * An agent chat.
 *
 * A chat is first-class: it owns a working directory and, while it is open, its
 * own terminal session. It does **not** require a terminal session to exist —
 * creating a chat with no session is the normal path, not a special case. "Run
 * this in the session I'm looking at" is just a new chat created with that
 * session's directory already filled in.
 *
 * ## What survives a process death
 *
 * Chat metadata persists (name, directory, timestamps, last status). The
 * terminal session and the live transcript do not: they belong to the process.
 * After a restart a chat is a record you can resume into, with an empty
 * transcript, rather than a half-restored conversation.
 */
data class AgentChat(
    val id: String,
    val name: String,
    /** Absolute path inside the guest filesystem. */
    val workingDirectory: String,
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
    val status: ChatStatus,
    /**
     * Last thing the model said, for the list preview. Empty until the chat has
     * produced output.
     */
    val lastMessagePreview: String = "",
)

enum class ChatStatus {
    /** No run in progress. */
    Idle,

    /** Streaming or running tools. */
    Running,

    /** Parked on an approval the user has not answered. */
    WaitingApproval,

    /** Last run completed normally. */
    Done,

    /** Last run failed. */
    Failed,
}

interface AgentChatRepository {

    /** All chats, most recently used first. */
    fun observeAll(): Flow<List<AgentChat>>

    fun observe(id: String): Flow<AgentChat?>

    /**
     * @param workingDirectory absolute guest path; defaults to the home
     *        directory when blank
     */
    suspend fun create(name: String, workingDirectory: String): AgentChat

    suspend fun rename(id: String, name: String)

    suspend fun updateStatus(id: String, status: ChatStatus)

    /** Bump `lastUsedAtMs`. Called when a run starts. */
    suspend fun touch(id: String)

    /**
     * Delete the chat record.
     *
     * If a terminal session is still attached to this chat, the caller is
     * responsible for closing it first — the repository does not reach into the
     * terminal module.
     */
    suspend fun delete(id: String)
}
