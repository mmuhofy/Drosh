package dev.drosh.domain.agent

import kotlinx.coroutines.flow.Flow

/**
 * Agent session interface — consumed by UI layer via Hilt.
 *
 * Per AGENT.md §142: "ui/ never imports from data/, agent/, terminal/ directly."
 * The ui layer injects this domain interface; the data layer binds AgentRuntime
 * via Hilt @Binds in BindingsModule.kt.
 */
interface AgentSession {
    suspend fun runTurn(config: AgentConfig): Flow<AgentEvent>
    fun cancel()
    val sessionState: kotlinx.coroutines.flow.StateFlow<SessionState>
}

/**
 * Configuration for a single agent turn — endpoint + name + model.
 * Per user instruction: "endpoint girme, isim girme" — no per-LLM adapters.
 */
data class AgentConfig(
    val apiKey: String,
    val model: String,
    val userMessage: String,
    val history: MutableList<LlmStep>,
    val systemPrompt: String,
    val endpoint: String,
    val workMode: WorkMode = WorkMode.AUTO,
    val workingDirectory: String = "",
    val maxSteps: Int = 20
)