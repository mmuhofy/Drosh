package dev.drosh.agent.runtime

import dev.drosh.agent.provider.ProviderAdapter
import dev.drosh.domain.agent.AccumulatedToolCall
import dev.drosh.domain.agent.AgentConfig
import dev.drosh.domain.agent.AgentEvent
import dev.drosh.domain.agent.AgentSession
import dev.drosh.domain.agent.LlmStep
import dev.drosh.domain.agent.SessionState
import dev.drosh.domain.agent.StreamEvent
import dev.drosh.domain.agent.StreamRequest
import dev.drosh.domain.agent.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
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
) : AgentSession {

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    @Volatile
    private var cancelled = false

    override fun cancel() {
        cancelled = true
        _sessionState.value = SessionState.Cancelled
    }

    override suspend fun runTurn(config: AgentConfig): Flow<AgentEvent> = flow {
        cancelled = false
        var inputTokens = 0
        var outputTokens = 0
        var reasoningStartMs = 0L
        val reasoningEventId = "thinking"

        _sessionState.value = SessionState.Busy(1)
        emit(AgentEvent.SessionStateChanged(SessionState.Busy(1)))
        emit(AgentEvent.TurnStarted(1))

        config.history.add(LlmStep.User(config.userMessage))

        try {
            for (step in 1..config.maxSteps) {
                if (cancelled) {
                    _sessionState.value = SessionState.Cancelled
                    emit(AgentEvent.Cancelled)
                    return@flow
                }

                emit(AgentEvent.ProgressUpdate("Step $step: requesting LLM..."))

                val toolDeclarations = toolRegistry.getToolDeclarations(config.workMode)
                val request = StreamRequest(
                    apiKey = config.apiKey,
                    model = config.model,
                    messages = config.history.toList(),
                    tools = toolDeclarations,
                    systemPrompt = config.systemPrompt,
                    endpoint = config.endpoint
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

                        val toolResult = toolRegistry.execute(call.name, args, config.workMode)
                        emit(AgentEvent.ToolCallCompleted(call.name, toolResult))

                        outputTokens += countTokens(toolResult.toResponseString())

                        config.history.add(LlmStep.Assistant("", toolCalls = toolCallSteps))
                        config.history.add(LlmStep.ToolResult(
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
                    message = "Max steps (${config.maxSteps}) reached",
                    retryable = false
                ))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(AgentEvent.FatalError("Agent runtime error: ${e.message}", e))
        } finally {
            _sessionState.value = SessionState.Idle
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
                element.mapValues { (_, v) ->
                    when (v) {
                        is JsonPrimitive -> v.content
                        else -> v.toString()
                    }
                }
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun countTokens(text: String): Int {
        if (text.isBlank()) return 0
        return (text.length / 4.0).roundToInt().coerceAtLeast(1)
    }
}