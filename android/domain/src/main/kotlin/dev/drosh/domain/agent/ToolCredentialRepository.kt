package dev.drosh.domain.agent

import kotlinx.coroutines.flow.Flow

/**
 * A credential for a tool service that is not a model provider.
 *
 * Separate from [LlmProviderRepository] because it is a different kind of secret
 * with a different lifecycle: it belongs to one tool, it is never sent to a
 * model, and missing it disables that tool rather than the agent. Today that is
 * only Exa, but the shape is what a second one would need too.
 */
interface ToolCredentialRepository {

    /** Null when nothing is stored. Never throws for "not configured". */
    suspend fun credential(serviceId: String): String?

    /** A blank [apiKey] clears it, matching the provider credential behaviour. */
    suspend fun setCredential(serviceId: String, apiKey: String)

    suspend fun clearCredential(serviceId: String)

    /** Service ids that currently have a key, for the settings screen. */
    fun observeConfigured(): Flow<Set<String>>
}

/** Identifiers for tool services that need their own key. */
object ToolService {
    /** Exa web search. Keys are created at dashboard.exa.ai/api-keys. */
    const val EXA = "exa"
}
