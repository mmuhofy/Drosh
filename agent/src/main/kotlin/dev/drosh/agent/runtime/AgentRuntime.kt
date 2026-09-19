package dev.drosh.agent.runtime

import dev.drosh.agent.provider.ProviderAdapter
import dev.drosh.domain.agent.AccumulatedToolCall
import dev.drosh.domain.agent.AgentEvent
import dev.drosh.domain.agent.LlmStep
import dev.drosh.domain.agent.SessionState
import dev.drosh.domain.agent.StreamEvent
import dev.drosh.domain.agent.StreamRequest
import dev.drosh.domain.agent.ToolResult
import dev.drosh.domain.agent.WorkMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class AgentRuntime @Inject constructor(
    private val providerAdapter: ProviderAdapter,
    private val toolRegistry: ToolRegistry
) {
    private val _sessionState = MutableSharedFlow<SessionState>(replay = 1)
    val sessionState = _sessionState.asSharedFlow()

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
        _sessionState.tryEmit(SessionState.Cancelled)
    }

    suspend fun runTurn(
        apiKey: String,
        model: String,
        userMessage: String,
        history: MutableList<LlmStep>,
        systemPrompt: String,
        endpoint: String,
        workMode: WorkMode,
        workingDirectory: String
    ): Flow<AgentEvent> = flow {
        cancelled = false
        var inputTokens = 0
        var outputTokens = 0
        var reasoningStartMs = 0L
        val reasoningEventId = "thinking"

        _sessionState.tryEmit(SessionState.Busy(1))
        emit(AgentEvent.SessionStateChanged(SessionState.Busy(1)))
        emit(AgentEvent.TurnStarted(1))

        history.add(LlmStep.User(userMessage))

        try {
            for (step in 1..MAX_STEPS) {
                if (cancelled) {
                    _sessionState.tryEmit(SessionState.Cancelled)
                    emit(AgentEvent.Cancelled)
                    return@flow
                }

                emit(AgentEvent.ProgressUpdate("Step $step: requesting LLM..."))

                val toolDeclarations = toolRegistry.getToolDeclarations(workMode)
                val request = StreamRequest(
                    apiKey = apiKey,
                    model = model,
                    messages = history.toList(),
                    tools = toolDeclarations,
                    systemPrompt = systemPrompt,
                    endpoint = endpoint
                )

                val accumulatedText = StringBuilder()
                val pendingToolCalls = mutableMapOf<Int, AccumulatedToolCall>()
                var reasoningActive = false

                providerAdapter.stream(request).collect { event ->
                    when (event) {
                        is StreamEvent.TextDelta -> {
                            accumulatedText.append(event.text)
                            emit(AgentEvent.TextChunk(event.text))
                        }
                        is StreamEvent.ReasoningDelta -> {
                            if (!reasoningActive) {
                                reasoningActive = true
                                reasoningStartMs = System.currentTimeMillis()
                            }
                            val elapsed = System.currentTimeMillis() - reasoningStartMs
                            emit(AgentEvent.ThinkingChunk(
                                eventId = reasoningEventId,
                                text = event.text,
                                elapsedMs = elapsed
                            ))
                        }
                        is StreamEvent.ReasoningComplete -> {
                            if (reasoningActive) {
                                val total = System.currentTimeMillis() - reasoningStartMs
                                reasoningActive = false
                                reasoningStartMs = 0L
                                emit(AgentEvent.ThinkingComplete(reasoningEventId, total))
                            }
                        }
                        is StreamEvent.ToolCallStart -> {
                            pendingToolCalls[event.index] = AccumulatedToolCall(
                                index = event.index,
                                id = event.id,
                                name = event.name
                            )
                            emit(AgentEvent.ToolCallStarted(event.name, emptyMap()))
                        }
                        is StreamEvent.ToolCallDelta -> {
                            val call = pendingToolCalls[event.index]
                            if (call != null) {
                                call.appendArgs(event.argsDelta)
                            }
                        }
                        is StreamEvent.StreamEnd -> {}
                        is StreamEvent.Error -> {
                            emit(AgentEvent.FatalError(event.message))
                        }
                    }
                }

                val assistantText = accumulatedText.toString()
                inputTokens += countTokens(assistantText)

                if (pendingToolCalls.isNotEmpty()) {
                    val calls = pendingToolCalls.values.sortedBy { it.index }
                    val toolCallSteps = mutableListOf<LlmStep.ToolCall>()

                    for (call in calls) {
                        val args = tryParseArgs(call.argumentsJson)
                        val llmStep = LlmStep.ToolCall(
                            id = call.id,
                            name = call.name,
                            arguments = args
                        )
                        toolCallSteps.add(llmStep)

                        emit(AgentEvent.ToolCallStarted(call.name, args))

                        val toolResult = toolRegistry.execute(call.name, args, workMode)
                        emit(AgentEvent.ToolCallCompleted(call.name, toolResult))

                        outputTokens += countTokens(toolResult.toResponseString())

                        history.add(LlmStep.Assistant("", toolCalls = toolCallSteps))
                        history.add(LlmStep.ToolResult(
                            callId = call.id,
                            name = call.name,
                            result = toolResult.toResponseString()
                        ))

                        if (toolResult is ToolResult.AwaitingApproval) {
                            emit(AgentEvent.ToolCallCompleted(call.name, toolResult))
                            return@flow
                        }
                    }

                    emit(AgentEvent.TokenUsageUpdate(
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
                        totalTokens = inputTokens + outputTokens
                    ))
                } else {
                    emit(AgentEvent.TokenUsageUpdate(
                        inputTokens = inputTokens,
                        outputTokens = outputTokens,
                        totalTokens = inputTokens + outputTokens
                    ))
                    break
                }
            }

            if (!cancelled) {
                emit(AgentEvent.ProviderError(
                    message = "Max steps ($MAX_STEPS) reached",
                    retryable = false
                ))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(AgentEvent.FatalError("Agent runtime error: ${e.message}", e))
        } finally {
            _sessionState.tryEmit(SessionState.Idle)
            emit(AgentEvent.SessionStateChanged(SessionState.Idle))
            emit(AgentEvent.TurnComplete)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun tryParseArgs(json: String): Map<String, Any> {
        if (json.isBlank()) return emptyMap()
        return try {
            val parser = Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
            val element = parser.parseToJsonElement(json)
            if (element is JsonObject) {
                val result = mutableMapOf<String, Any>()
                for ((key, value) in element) {
                    result[key] = jsonValueToString(value)
                }
                result
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun jsonValueToString(value: kotlinx.serialization.json.JsonElement): String {
        return when (value) {
            is kotlinx.serialization.json.JsonPrimitive -> value.content
            else -> value.toString()
        }
    }

    private fun countTokens(text: String): Int {
        if (text.isBlank()) return 0
        return (text.length / 4.0).roundToInt().coerceAtLeast(1)
    }

    companion object {
        const val MAX_STEPS = 20
    }
}

private fun <T : Any> kotlinx.serialization.json.JsonObject.toMap(): Map<String, T> {
    val result = mutableMapOf<String, T>()
    for ((key, value) in this) {
        @Suppress("UNCHECKED_CAST")
        result[key] = value as T
    }
    return result
}