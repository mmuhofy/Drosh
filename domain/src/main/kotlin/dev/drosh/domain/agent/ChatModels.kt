package dev.drosh.domain.agent

/**
 * UI-facing message model — chat transcript item.
 * Adapted from IrisCode's ChatMessage pattern for Drosh' inline rendering.
 */
sealed class ChatMessage {
    abstract val id: String

    data class UserMessage(
        override val id: String,
        val text: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()

    data class AgentText(
        override val id: String,
        val text: String,
        val isStreaming: Boolean = true,
        val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()

    data class Thinking(
        override val id: String,
        val text: String,
        val elapsedMs: Long = 0L,
        val isThinking: Boolean = true,
        val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()

    data class ToolCall(
        override val id: String,
        val toolName: String,
        val args: Map<String, Any>,
        val isStreaming: Boolean = true,
        val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()

    data class ToolResult(
        override val id: String,
        val toolName: String,
        val result: dev.drosh.domain.agent.ToolResult,
        val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()

    data class BashCommand(
        override val id: String,
        val command: String,
        val output: String,
        val exitCode: Int? = null,
        val isRunning: Boolean = false,
        val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()

    data class Error(
        override val id: String,
        val message: String,
        val cause: Throwable? = null,
        val timestamp: Long = System.currentTimeMillis()
    ) : ChatMessage()
}

/**
 * LLM provider config — entered via UI (endpoint + name).
 * Per user instruction: "endpoint girme, isim girme" — no per-LLM adapters.
 */
data class ProviderConfig(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val isDefault: Boolean = false
)

/**
 * UI state for the agent chat screen.
 */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val currentProvider: ProviderConfig? = null,
    val providers: List<ProviderConfig> = emptyList(),
    val availableModels: List<String> = emptyList(),
    val isFetchingModels: Boolean = false,
    val workMode: WorkMode = WorkMode.AUTO,
    val isStreaming: Boolean = false,
    val isTyping: Boolean = false,
    val errorMessage: String? = null
)