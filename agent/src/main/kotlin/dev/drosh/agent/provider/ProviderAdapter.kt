package dev.drosh.agent.provider

import dev.drosh.domain.agent.StreamRequest
import dev.drosh.domain.agent.StreamEvent
import kotlinx.coroutines.flow.Flow

/**
 * Provider adapter — streams OpenAI-compatible SSE responses as [StreamEvent].
 *
 * Per AGENT.md §96-97: "api sistemi ilk olarak endpoint girme, isim girme olacak.
 * her llm için ayrı yazmıyoruz." → single SSE-based adapter that works with any
 * OpenAI-compatible endpoint.
 */
interface ProviderAdapter {
    suspend fun stream(request: StreamRequest): Flow<StreamEvent>
}