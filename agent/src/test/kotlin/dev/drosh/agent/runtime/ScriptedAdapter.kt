package dev.drosh.agent.runtime

import dev.drosh.domain.agent.ChatAdapter
import dev.drosh.domain.agent.ChatMessage
import dev.drosh.domain.agent.TranscriptStore
import dev.drosh.domain.agent.assembleModelHistory
import dev.drosh.domain.agent.FinishReason
import dev.drosh.domain.agent.LlmCredential
import dev.drosh.domain.agent.LlmMessage
import dev.drosh.domain.agent.LlmModel
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.LlmProviderRepository
import dev.drosh.domain.agent.LlmRequest
import dev.drosh.domain.agent.LlmStreamEvent
import dev.drosh.domain.agent.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A scripted provider.
 *
 * Each call to [stream] consumes the next turn from [script]. A turn that runs
 * out of entries yields a plain "done" answer, so a test that only cares about the
 * first tool call does not have to spell out the follow-up.
 */
internal class ScriptedAdapter(
    override val kind: ProviderKind = ProviderKind.OPENAI_COMPAT,
) : ChatAdapter {

    val requests: MutableList<LlmRequest> = mutableListOf()
    var script: MutableList<List<LlmStreamEvent>> = mutableListOf()

    override fun stream(
        provider: LlmProvider,
        request: LlmRequest,
        credential: LlmCredential,
    ): Flow<LlmStreamEvent> = flow {
        requests += request
        val turn = if (script.isEmpty()) listOf(finishedEvent()) else script.removeAt(0)
        turn.forEach { emit(it) }
    }
}

/** A model that answers in plain text and asks for nothing. */
internal fun answerTurn(text: String): List<LlmStreamEvent> = listOf(
    LlmStreamEvent.TextDelta(text),
    LlmStreamEvent.Finished(FinishReason.STOP, TokenUsageFixture()),
)

/** A model that asks for one tool and nothing else. */
internal fun toolTurn(
    toolName: String,
    arguments: JsonObject,
    callId: String = "call_1",
): List<LlmStreamEvent> = listOf(
    LlmStreamEvent.ToolCallStarted(callId, toolName),
    LlmStreamEvent.ToolCallCompleted(callId, toolName, arguments),
    LlmStreamEvent.Finished(FinishReason.TOOL_CALLS, TokenUsageFixture()),
)

/** A model that asks for the same tool call over and over. */
internal fun repeatingToolTurn(toolName: String, arguments: JsonObject): List<LlmStreamEvent> =
    toolTurn(toolName, arguments, callId = "call_repeat")

internal fun failureTurn(message: String, retryable: Boolean): List<LlmStreamEvent> =
    listOf(LlmStreamEvent.Failed(message, retryable))

/** A turn that streams some text and *then* fails. */
internal fun textThenFailureTurn(text: String, message: String): List<LlmStreamEvent> = listOf(
    LlmStreamEvent.TextDelta(text),
    LlmStreamEvent.Failed(message, retryable = true),
)

internal fun finishedEvent() = LlmStreamEvent.Finished(FinishReason.STOP, TokenUsageFixture())

private fun TokenUsageFixture() = dev.drosh.domain.agent.TokenUsage(input = 100, output = 20)

/** An in-memory provider catalog with a fixed key. */
internal class FakeProviderRepository(
    key: String? = "sk-test",
) : LlmProviderRepository {

    /** Mutable so the new setCredential / clearCredential can be exercised. */
    private var key: String? = key

    var providers: List<LlmProvider> = listOf(
        LlmProvider(
            id = "openrouter",
            label = "OpenRouter",
            kind = ProviderKind.OPENAI_COMPAT,
            baseUrl = "https://openrouter.ai/api/v1",
            modelsPath = "models",
        ),
    )

    override fun observeProviders(): Flow<List<LlmProvider>> = flow { emit(providers) }

    override fun observeCredentials(): Flow<Map<String, LlmCredential>> = flow {
        emit(key?.let { mapOf("openrouter" to LlmCredential("openrouter", it)) } ?: emptyMap())
    }

    override fun provider(id: String): LlmProvider? = providers.firstOrNull { it.id == id }

    override suspend fun credential(providerId: String): LlmCredential? =
        key?.let { LlmCredential(providerId, it) }

    override suspend fun setCredential(providerId: String, apiKey: String) {
        this.key = apiKey.takeIf { it.isNotBlank() }
    }

    override suspend fun clearCredential(providerId: String) {
        key = null
    }

    override suspend fun setConfigValue(providerId: String, key: String, value: String) = Unit

    override suspend fun setCustomBaseUrl(baseUrl: String) = Unit

    override suspend fun fetchModels(providerId: String, forceRefresh: Boolean): List<LlmModel> =
        emptyList()

    override suspend fun setSelectedModel(providerId: String, modelId: String) = Unit

    override suspend fun selectedModel(providerId: String): String? = null

    override suspend fun setSelectedProvider(providerId: String) = Unit

    override suspend fun selectedProvider(): String? = null

    /** Empty by default: no model declares effort, so the loop asks for none. */
    var effortsByModel: Map<String, List<String>> = emptyMap()

    override fun reasoningEfforts(providerId: String, modelId: String): List<String> =
        effortsByModel[modelId].orEmpty()

    override suspend fun setReasoningEffort(providerId: String, modelId: String, effort: String?) = Unit

    override suspend fun reasoningEffort(providerId: String, modelId: String): String? = null

    override fun outputTokenLimit(providerId: String, modelId: String): Int? = 4096
}

/** Convenience for building tool arguments in tests. */
internal fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
    pairs.forEach { (key, value) -> put(key, value) }
}

/** The assistant/tool history the loop built, as a flat list for assertions. */
internal fun LlmRequest.describeHistory(): List<String> = messages.map { message ->
    when (message) {
        is LlmMessage.User -> "user: ${message.text}"
        is LlmMessage.Assistant -> if (message.toolCalls.isEmpty()) {
            "assistant: ${message.text}"
        } else {
            "assistant(tool): ${message.toolCalls.joinToString { it.name }}"
        }

        is LlmMessage.ToolResultMessage -> "result(${message.name}): ${message.content.take(40)}"
    }
}


/**
 * An in-memory transcript store.
 *
 * Keeps everything the loop wrote so a test can assert on what a restored chat
 * would actually be sent — which is the only way to check that the model
 * remembers without a database.
 */
internal class FakeTranscriptStore : TranscriptStore {
    val modelViews = mutableMapOf<String, List<LlmMessage>>()
    val messages = mutableMapOf<String, List<ChatMessage>>()

    override suspend fun load(chatId: String): List<ChatMessage> = messages[chatId].orEmpty()

    override suspend fun save(chatId: String, messages: List<ChatMessage>) {
        this.messages[chatId] = messages
    }

    override suspend fun saveModelView(chatId: String, messages: List<LlmMessage>) {
        modelViews[chatId] = messages
    }

    override suspend fun replaceAll(chatId: String, messages: List<ChatMessage>) {
        this.messages[chatId] = messages
    }

    override suspend fun clear(chatId: String) {
        messages.remove(chatId)
        modelViews.remove(chatId)
    }

    override fun assembleHistory(messages: List<ChatMessage>): List<LlmMessage> =
        assembleModelHistory(messages)
}
