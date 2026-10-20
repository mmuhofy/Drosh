package dev.drosh.agent.runtime

import dev.drosh.agent.provider.ProviderRegistry
import dev.drosh.domain.agent.ChatTitleRequest
import dev.drosh.domain.agent.FinishReason
import dev.drosh.domain.agent.LlmMessage
import dev.drosh.domain.agent.LlmStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.runTest

/**
 * Naming a conversation, from the other side of the seam.
 *
 * Every failure has to come back as null, because null is what the chat screen
 * reads as "keep the name you already had". A thrown exception here would be
 * reported as a run that failed, for something the user never asked to fail.
 */
class AgentChatTitleSuggesterTest {

    private val adapter = ScriptedAdapter()

    private fun suggester(
        repository: FakeProviderRepository = FakeProviderRepository(),
        registry: ProviderRegistry = ProviderRegistry(setOf(adapter)),
    ) = AgentChatTitleSuggester(repository, registry)

    private fun request(prompt: String = "why does the gradle sync fail on this branch") =
        ChatTitleRequest(
            providerId = "openrouter",
            modelId = "anthropic/claude-sonnet-4",
            prompt = prompt,
        )

    /** One turn that answers with [text] and stops. */
    private fun answering(text: String): List<LlmStreamEvent> = listOf(
        LlmStreamEvent.TextDelta(text),
        LlmStreamEvent.Finished(FinishReason.STOP),
    )

    @Test
    fun `a title the model wrote becomes the chat name`() = runTest {
        adapter.script += listOf(answering("Gradle sync fails on CI"))

        assertEquals("Gradle sync fails on CI", suggester().suggest(request()))
    }

    @Test
    fun `the naming request carries the prompt, not a summary of it`() = runTest {
        adapter.script += listOf(answering("Gradle sync fails on CI"))

        suggester().suggest(request())

        val sent = adapter.requests.single()
        assertEquals(
            "why does the gradle sync fail on this branch",
            (sent.messages.single() as LlmMessage.User).text,
        )
        // No tools: a model asked for a title that starts calling tools has been
        // asked the wrong question, and every call costs a round trip.
        assertTrue(sent.tools.isEmpty())
    }

    @Test
    fun `a first message too short to describe is not sent at all`() = runTest {
        assertNull(suggester().suggest(request(prompt = "hi")))
        assertTrue("nothing should reach the provider", adapter.requests.isEmpty())
    }

    @Test
    fun `a provider failure costs the name and nothing else`() = runTest {
        adapter.script += listOf(listOf(LlmStreamEvent.Failed("429 slow down", retryable = true)))

        assertNull(suggester().suggest(request()))
    }

    @Test
    fun `no key means no name and no request`() = runTest {
        assertNull(suggester(FakeProviderRepository(key = null)).suggest(request()))
        assertTrue(adapter.requests.isEmpty())
    }

    @Test
    fun `an unknown provider means no name and no request`() = runTest {
        assertNull(suggester().suggest(request().copy(providerId = "nope")))
        assertTrue(adapter.requests.isEmpty())
    }

    @Test
    fun `a model that answers with prose is not a title`() = runTest {
        adapter.script += listOf(answering("I cannot help with that request."))

        assertNull(suggester().suggest(request()))
    }

    @Test
    fun `a provider no adapter speaks fails to nothing`() = runTest {
        assertNull(suggester(registry = ProviderRegistry(emptySet())).suggest(request()))
    }

    @Test
    fun `a title longer than the limit is dropped rather than clipped`() = runTest {
        adapter.script += listOf(answering("a".repeat(200)))

        assertNull(suggester().suggest(request()))
    }
}