package dev.drosh.data.agent

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An agent chat, as persisted.
 *
 * ## What does not live here
 *
 * The transcript. A run's conversation belongs to its runtime, and a runtime dies
 * with the process — a transcript restored from disk would describe a conversation
 * the model no longer has any memory of, and the first turn after a restart would
 * be answered against a history the model cannot see. So the chat is a record you
 * resume *into*, not a conversation that reloads.
 *
 * That also means [lastMessagePreview] is a convenience for the list, not a
 * transcript. It is the last thing the model said, kept so the Agent Home list can
 * say what a chat was about without opening it.
 */
@Entity(tableName = "agent_chats")
data class AgentChatEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    /** Absolute path inside the guest filesystem. */
    @ColumnInfo(name = "working_directory")
    val workingDirectory: String,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    @ColumnInfo(name = "last_used_at_ms")
    val lastUsedAtMs: Long,

    /** Persisted as the [dev.drosh.domain.agent.ChatStatus] name. */
    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "last_message_preview")
    val lastMessagePreview: String,
)
