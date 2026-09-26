package dev.drosh.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drosh.domain.agent.AgentConfig
import dev.drosh.domain.agent.AgentEvent
import dev.drosh.domain.agent.AgentSession
import dev.drosh.domain.agent.ChatMessage
import dev.drosh.domain.agent.ChatUiState
import dev.drosh.domain.agent.LlmStep
import dev.drosh.domain.agent.ProviderConfig
import dev.drosh.domain.agent.SessionState
import dev.drosh.domain.agent.WorkMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class AgentViewModel @Inject constructor(
    private val agentSession: AgentSession,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val history = mutableListOf<LlmStep>()
    private var currentProvider: ProviderConfig? = null
    private var workMode: WorkMode = WorkMode.AUTO
    private var bashOutputBuilders = mutableMapOf<String, StringBuilder>()

    init {
        _uiState.value = ChatUiState(
            currentProvider = null,
            providers = defaultProviders(),
            workMode = workMode
        )
    }

    private fun defaultProviders(): List<ProviderConfig> = listOf(
        ProviderConfig(
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "",
            model = "meta-llama/llama-3-8b-instruct",
        ),
        ProviderConfig(
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            apiKey = "",
            model = "gpt-4o-mini",
        ),
        ProviderConfig(
            name = "Custom",
            baseUrl = "",
            apiKey = "",
            model = "",
        )
    )

    fun addCustomProvider() {
        val newProvider = ProviderConfig(
            name = "Custom ${System.currentTimeMillis() % 10000}",
            baseUrl = "",
            apiKey = "",
            model = "",
        )
        val updated = _uiState.value.providers + newProvider
        _uiState.value = _uiState.value.copy(providers = updated)
        currentProvider = newProvider
        _uiState.value = _uiState.value.copy(currentProvider = newProvider)
    }

    fun setProvider(provider: ProviderConfig) {
        currentProvider = provider
        _uiState.value = _uiState.value.copy(currentProvider = provider)
    }

    fun fetchModels() {
        val provider = currentProvider
        if (provider == null || provider.baseUrl.isBlank()) {
            addError("Enter base URL first")
            return
        }

        val modelsUrl = "${provider.baseUrl.trimEnd('/')}/models"
        _uiState.value = _uiState.value.copy(isFetchingModels = true, errorMessage = null)

        viewModelScope.launch {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url(modelsUrl)
                    .header("Authorization", "Bearer ${provider.apiKey}")
                    .apply {
                        if (provider.baseUrl.contains("openrouter", ignoreCase = true)) {
                            header("HTTP-Referer", "https://github.com/mmuhofy/IrisCode")
                            header("X-OpenRouter-Title", "Drosh")
                        }
                    }
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    val modelIds = try {
                        Json.parseToJsonElement(body ?: "{}").jsonObject["data"]
                            ?.jsonArray?.mapNotNull {
                                it.jsonObject["id"]?.jsonPrimitive?.content
                            }
                    } catch (_: Exception) {
                        emptyList()
                    }
                    _uiState.value = _uiState.value.copy(
                        availableModels = modelIds ?: emptyList(),
                        isFetchingModels = false
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isFetchingModels = false,
                        errorMessage = "Failed to fetch models: HTTP ${response.code}"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isFetchingModels = false,
                    errorMessage = "Failed to fetch models: ${e.message}"
                )
            }
        }
    }

    fun selectModel(model: String) {
        val provider = currentProvider ?: return
        val updated = provider.copy(model = model)
        updateCurrentProvider(updated)
    }

    fun updateCurrentProvider(provider: ProviderConfig) {
        currentProvider = provider
        _uiState.value = _uiState.value.copy(
            currentProvider = provider,
            providers = _uiState.value.providers.map {
                if (it.name == provider.name) provider else it
            }
        )
    }

    fun setWorkMode(mode: WorkMode) {
        workMode = mode
        _uiState.value = _uiState.value.copy(workMode = mode)
    }

    fun sendMessage(message: String) {
        val provider = currentProvider
        if (provider == null || provider.baseUrl.isBlank()) {
            addError("No provider configured. Set base URL and API key first.")
            return
        }

        val userMessage = ChatMessage.UserMessage(
            id = UUID.randomUUID().toString(),
            text = message
        )
        val messages = _uiState.value.messages + userMessage
        _uiState.value = _uiState.value.copy(
            messages = messages,
            isStreaming = true,
            isTyping = true
        )

        val systemPrompt = buildSystemPrompt()
        val config = AgentConfig(
            apiKey = provider.apiKey,
            model = provider.model,
            userMessage = message,
            history = history,
            systemPrompt = systemPrompt,
            baseUrl = provider.baseUrl,
            workMode = workMode,
            workingDirectory = "/storage/emulated/0/Android/data/dev.drosh/files/home"
        )

        val agentMsgId = UUID.randomUUID().toString()
        val agentBuilder = StringBuilder()
        var hasToolCallSinceLastText = false
        var thinkingMsgId: String? = null
        var bashMsgId: String? = null

        viewModelScope.launch {
            agentSession.runTurn(config).collect { event ->
                when (event) {
                    is AgentEvent.TextChunk -> {
                        agentBuilder.append(event.text)
                        val existing = _uiState.value.messages.find { it.id == agentMsgId }
                        if (existing == null) {
                            addMessage(ChatMessage.AgentText(
                                id = agentMsgId,
                                text = "",
                                isStreaming = true
                            ))
                        }
                        replaceMessage(agentMsgId, ChatMessage.AgentText(
                            id = agentMsgId,
                            text = agentBuilder.toString(),
                            isStreaming = true
                        ))
                        _uiState.value = _uiState.value.copy(isTyping = true)
                        hasToolCallSinceLastText = false
                    }

                    is AgentEvent.ThinkingChunk -> {
                        val existing = _uiState.value.messages.find { it.id == event.eventId }
                        if (existing is ChatMessage.Thinking) {
                            existing.apply {
                                replaceMessage(id, copy(
                                    text = text + event.text,
                                    elapsedMs = event.elapsedMs
                                ))
                            }
                        } else {
                            val msg = ChatMessage.Thinking(
                                id = event.eventId,
                                text = event.text,
                                elapsedMs = event.elapsedMs
                            )
                            thinkingMsgId = event.eventId
                            addMessage(msg)
                        }
                    }

                    is AgentEvent.ThinkingComplete -> {
                        val existing = _uiState.value.messages.find { it.id == event.eventId }
                        if (existing is ChatMessage.Thinking) {
                            existing.apply {
                                replaceMessage(id, copy(
                                    isThinking = false,
                                    elapsedMs = event.totalMs
                                ))
                            }
                        }
                    }

                    is AgentEvent.ToolCallStarted -> {
                        hasToolCallSinceLastText = true
                        val toolMsg = ChatMessage.ToolCall(
                            id = UUID.randomUUID().toString(),
                            toolName = event.toolName,
                            args = event.args,
                            isStreaming = true
                        )
                        addMessage(toolMsg)
                    }

                    is AgentEvent.ToolCallCompleted -> {
                        val lastToolCall = _uiState.value.messages
                            .filterIsInstance<ChatMessage.ToolCall>()
                            .lastOrNull()
                        if (lastToolCall != null) {
                            replaceMessage(lastToolCall.id, lastToolCall.copy(isStreaming = false))
                        }
                        val resultMsg = ChatMessage.ToolResult(
                            id = UUID.randomUUID().toString(),
                            toolName = event.toolName,
                            result = event.result
                        )
                        addMessage(resultMsg)
                    }

                    is AgentEvent.BashStarted -> {
                        val bashMsg = ChatMessage.BashCommand(
                            id = event.eventId,
                            command = event.command,
                            output = "",
                            isRunning = true
                        )
                        bashMsgId = event.eventId
                        addMessage(bashMsg)
                        bashOutputBuilders[event.eventId] = StringBuilder()
                    }

                    is AgentEvent.BashOutput -> {
                        val builder = bashOutputBuilders[event.eventId] ?: StringBuilder()
                        builder.append(event.line)
                        bashOutputBuilders[event.eventId] = builder
                        val existing = _uiState.value.messages.find { it.id == event.eventId }
                        if (existing is ChatMessage.BashCommand) {
                            existing.apply {
                                replaceMessage(id, copy(
                                    output = builder.toString(),
                                    isRunning = true
                                ))
                            }
                        }
                    }

                    is AgentEvent.BashCompleted -> {
                        val builder = bashOutputBuilders.remove(event.eventId) ?: StringBuilder()
                        builder.append(event.output)
                        val existing = _uiState.value.messages.find { it.id == event.eventId }
                        if (existing is ChatMessage.BashCommand) {
                            existing.apply {
                                replaceMessage(id, copy(
                                    output = builder.toString(),
                                    exitCode = event.exitCode,
                                    isRunning = false
                                ))
                            }
                        }
                    }

                    is AgentEvent.FatalError -> {
                        addMessage(ChatMessage.Error(
                            id = UUID.randomUUID().toString(),
                            message = event.message,
                            cause = event.cause
                        ))
                    }

                    is AgentEvent.ProviderError -> {
                        addError(event.message)
                    }

                    is AgentEvent.TurnComplete -> {
                        _uiState.value = _uiState.value.copy(
                            isStreaming = false,
                            isTyping = false
                        )
                    }

                    is AgentEvent.Cancelled -> {
                        _uiState.value = _uiState.value.copy(
                            isStreaming = false,
                            isTyping = false
                        )
                    }

                    is AgentEvent.SessionStateChanged -> {
                        _uiState.value = _uiState.value.copy(
                            isStreaming = event.state is SessionState.Busy
                        )
                    }

                    is AgentEvent.ProgressUpdate -> {}
                    is AgentEvent.TokenUsageUpdate -> {}
                    is AgentEvent.TurnStarted -> {}
                    is AgentEvent.DoomLoopDetected -> {}
                }
            }
        }
    }

    private fun buildSystemPrompt(): String {
        return """
You are Drosh, an AI coding agent running inside an Android terminal emulator.
You have access to a Linux environment (Ubuntu via PRoot) with shell, file editing, and web search tools.
Be concise but thorough. Use the shell tool for git commands, builds, tests, and file operations.
        """.trimIndent()
    }

    private fun addMessage(message: ChatMessage) {
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + message
        )
    }

    private fun replaceMessage(id: String, message: ChatMessage) {
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.map {
                if (it.id == id) message else it
            }
        )
    }

    private fun addError(message: String) {
        _uiState.value = _uiState.value.copy(errorMessage = message)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun cancel() {
        agentSession.cancel()
    }
}
