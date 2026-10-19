package dev.drosh.agent.runtime

import dev.drosh.agent.provider.ProviderRegistry
import dev.drosh.agent.tool.ToolRegistry
import dev.drosh.domain.agent.AgentEvent
import dev.drosh.domain.agent.AgentRequest
import dev.drosh.domain.agent.LlmMessage
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.MessageAttachment
import dev.drosh.domain.agent.ProviderKind
import dev.drosh.domain.agent.RunOutcome
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the loop does with media, and what it refuses before sending.
 *
 * The refusal matters more than the pass-through: a turn the protocol cannot
 * carry must not enter the history, because the next turn would then replay a
 * picture the provider never saw and answer for a conversation that never
 * included it.
 */
class AgentMediaTest {

    // Replaced per turn: the registry resolves a provider by kind, so the
    // scripted adapter has to speak whichever kind the test is about.
    private var adapter = ScriptedAdapter()
    private val transcripts = FakeTranscriptStore()

    private val png = MessageAttachment.fromBase64(
        id = "a1",
        mediaType = "image/png",
        base64 = "AAAA",
        fileName = "shot.png",
        byteSize = 3,
    )

    private val clip = MessageAttachment.fromBase64(
        id = "v1",
        mediaType = "video/mp4",
        base64 = "AAAA",
        fileName = "clip.mp4",
        byteSize = 3,
    )

    private fun loop(provider: LlmProvider = openRouter()): AgentLoop {
        adapter = ScriptedAdapter(provider.kind)
        return AgentLoop(
            providers = FakeProviderRepository().apply { providers = listOf(provider) },
            registry = ProviderRegistry(setOf(adapter)),
            toolRegistry = ToolRegistry(setOf(FakeTool("shell"))),
            transcripts = transcripts,
            settings = FakeSettingsStore(),
        )
    }

    private fun openRouter() = LlmProvider(
        id = "openrouter",
        label = "OpenRouter",
        kind = ProviderKind.OPENAI_COMPAT,
        baseUrl = "https://openrouter.ai/api/v1",
    )

    private fun anthropic() = LlmProvider(
        id = "anthropic",
        label = "Anthropic",
        kind = ProviderKind.ANTHROPIC,
        baseUrl = "https://api.anthropic.com/v1",
    )

    private fun gemini() = LlmProvider(
        id = "google",
        label = "Google",
        kind = ProviderKind.GEMINI,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
    )

    private fun request(
        provider: LlmProvider,
        prompt: String = "what is wrong here",
        attachments: List<MessageAttachment> = emptyList(),
    ) = AgentRequest(
        chatId = "chat-media",
        providerId = provider.id,
        modelId = "vendor/model",
        prompt = prompt,
        workingDirectory = "/home",
        attachments = attachments,
    )

    @Test
    fun `attachments ride in the same user turn as the words`() = runTest {
        val provider = openRouter()
        val loop = loop(provider)
        adapter.script += listOf(answerTurn("A stack trace."))

        loop.send(request(provider, attachments = listOf(png))).toList()

        val sent = adapter.requests.single().messages.first() as LlmMessage.User
        assertEquals("what is wrong here", sent.text)
        assertEquals(listOf(png), sent.attachments)
    }

    @Test
    fun `a screenshot with nothing written under it is a turn of its own`() = runTest {
        val provider = openRouter()
        val loop = loop(provider)
        adapter.script += listOf(answerTurn("A stack trace."))

        val events = loop.send(request(provider, prompt = "", attachments = listOf(png))).toList()

        assertTrue(events.last().outcome() is RunOutcome.Completed)
        val sent = adapter.requests.single().messages.first() as LlmMessage.User
        assertEquals("", sent.text)
        assertEquals(1, sent.attachments.size)
    }

    @Test
    fun `a protocol with no video part refuses the turn instead of sending half of it`() = runTest {
        val provider = anthropic()
        val loop = loop(provider)

        val events = loop.send(request(provider, attachments = listOf(png, clip))).toList()

        val outcome = events.last().outcome()
        assertTrue("expected a refusal, got $outcome", outcome is RunOutcome.Failed)
        assertTrue(
            (outcome as RunOutcome.Failed).message.contains("video"),
        )
        // Nothing reached the provider, and nothing entered the history.
        assertTrue(adapter.requests.isEmpty())
    }

    @Test
    fun `a refused turn is not remembered, so the next turn is not about a picture nobody saw`() =
        runTest {
            val provider = anthropic()
            val loop = loop(provider)
            loop.send(request(provider, attachments = listOf(png, clip))).toList()

            adapter.script += listOf(answerTurn("ok"))
            loop.send(request(provider, prompt = "carry on")).toList()

            val history = adapter.requests.single().messages
            assertEquals(1, history.size)
            assertEquals("carry on", (history.first() as LlmMessage.User).text)
        }

    @Test
    fun `the one protocol with a video part sends the whole turn`() = runTest {
        val provider = gemini()
        val loop = loop(provider)
        adapter.script += listOf(answerTurn("A clip."))

        loop.send(request(provider, attachments = listOf(png, clip))).toList()

        val sent = adapter.requests.single().messages.first() as LlmMessage.User
        assertEquals(2, sent.attachments.size)
    }

    private fun AgentEvent.outcome(): RunOutcome = when (this) {
        is AgentEvent.RunFinished -> outcome
        else -> error("last event was $this")
    }
}