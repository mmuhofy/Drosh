package dev.drosh.agent.provider

import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.ProviderKind
import dev.drosh.domain.agent.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which effort values survive the trip from what a model declares to what its
 * protocol can send.
 *
 * Lives apart from EffortMapperTest because the two answer different questions:
 * this one is pure policy with no wire format in it, and the settings screen
 * depends on it without depending on any adapter.
 */
class ReasoningEffortTest {

    private val allEfforts = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

    private fun provider(
        kind: ProviderKind,
        npm: String? = null,
        id: String = "test",
        baseUrl: String = "https://example.com/v1",
    ) = LlmProvider(id = id, label = id, kind = kind, baseUrl = baseUrl, npm = npm)

    // ── allowed values ─────────────────────────────────────────────────────

    @Test
    fun `a plain gateway offers every declared value`() {
        val allowed = ReasoningEffort.allowed(provider(ProviderKind.OPENAI_COMPAT), "m", allEfforts)

        assertEquals(allEfforts, allowed)
    }

    @Test
    fun `openai responses drops max, which is not in its union`() {
        val allowed = ReasoningEffort.allowed(provider(ProviderKind.OPENAI_RESPONSES), "gpt-5", allEfforts)

        assertFalse(allowed.contains("max"))
        // Order and content are otherwise preserved — this is a filter, not a
        // reordering, and the picker shows the catalog's order.
        assertEquals(allEfforts.filterNot { it == "max" }, allowed)
    }

    @Test
    fun `modern claude is held to its five-level tier`() {
        val allowed = ReasoningEffort.allowed(provider(ProviderKind.ANTHROPIC), "claude-opus-4-7", allEfforts)

        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), allowed)
    }

    @Test
    fun `claude 4-6 has no xhigh`() {
        val allowed = ReasoningEffort.allowed(provider(ProviderKind.ANTHROPIC), "claude-sonnet-4.6", allEfforts)

        assertEquals(listOf("low", "medium", "high", "max"), allowed)
    }

    @Test
    fun `a claude outside every known tier keeps the catalog list`() {
        val declared = listOf("low", "high", "max")

        assertEquals(
            declared,
            ReasoningEffort.allowed(provider(ProviderKind.ANTHROPIC), "claude-3-5-haiku", declared),
        )
    }

    @Test
    fun `gemini 2-5 offers nothing because it predates thinkingLevel`() {
        // Verified from Google's docs: 2.5 takes a numeric thinkingBudget, which
        // the adapter does not send. Better no selector than a field the endpoint
        // ignores.
        val allowed = ReasoningEffort.allowed(provider(ProviderKind.GEMINI), "gemini-2.5-pro", allEfforts)

        assertTrue(allowed.isEmpty())
    }

    @Test
    fun `gemini 3-1 pro rejects minimal`() {
        val allowed = ReasoningEffort.allowed(provider(ProviderKind.GEMINI), "gemini-3.1-pro", allEfforts)

        assertEquals(listOf("low", "medium", "high"), allowed)
    }

    @Test
    fun `gemini 3 pro-preview rejects minimal and medium`() {
        val allowed = ReasoningEffort.allowed(provider(ProviderKind.GEMINI), "gemini-3-pro-preview", allEfforts)

        assertEquals(listOf("low", "high"), allowed)
    }

    @Test
    fun `gemini 3-8 flash rejects minimal`() {
        val allowed = ReasoningEffort.allowed(provider(ProviderKind.GEMINI), "gemini-3.8-flash", allEfforts)

        assertEquals(listOf("low", "medium", "high"), allowed)
    }

    @Test
    fun `gemini 3 flash keeps the full range`() {
        val allowed = ReasoningEffort.allowed(provider(ProviderKind.GEMINI), "gemini-3-flash", allEfforts)

        assertEquals(listOf("minimal", "low", "medium", "high"), allowed)
    }
}
