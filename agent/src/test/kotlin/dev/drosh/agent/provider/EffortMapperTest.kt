package dev.drosh.agent.provider

import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.ProviderKind
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The effort translation table, pinned.
 *
 * Each protocol gets its own spelling and each Anthropic generation its own
 * combination, so a regression here is silent — the request still goes out, the
 * provider just ignores or rejects the field.
 */
class EffortMapperTest {

    private val mapper = EffortMapper()

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
        val allowed = mapper.allowedEfforts(provider(ProviderKind.OPENAI_COMPAT), "m", allEfforts)

        assertEquals(allEfforts, allowed)
    }

    @Test
    fun `openai responses drops max, which is not in its union`() {
        val allowed = mapper.allowedEfforts(provider(ProviderKind.OPENAI_RESPONSES), "gpt-5", allEfforts)

        assertFalse(allowed.contains("max"))
        // Order and content are otherwise preserved — this is a filter, not a
        // reordering, and the picker shows the catalog's order.
        assertEquals(allEfforts.filterNot { it == "max" }, allowed)
    }

    @Test
    fun `modern claude is held to its five-level tier`() {
        val allowed = mapper.allowedEfforts(provider(ProviderKind.ANTHROPIC), "claude-opus-4-7", allEfforts)

        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), allowed)
    }

    @Test
    fun `claude 4-6 has no xhigh`() {
        val allowed = mapper.allowedEfforts(provider(ProviderKind.ANTHROPIC), "claude-sonnet-4.6", allEfforts)

        assertEquals(listOf("low", "medium", "high", "max"), allowed)
    }

    @Test
    fun `a claude outside every known tier keeps the catalog list`() {
        val declared = listOf("low", "high", "max")

        assertEquals(
            declared,
            mapper.allowedEfforts(provider(ProviderKind.ANTHROPIC), "claude-3-5-haiku", declared),
        )
    }

    @Test
    fun `gemini 2-5 offers nothing because it predates thinkingLevel`() {
        // Verified from Google's docs: 2.5 takes a numeric thinkingBudget, which
        // the adapter does not send. Better no selector than a field the endpoint
        // ignores.
        val allowed = mapper.allowedEfforts(provider(ProviderKind.GEMINI), "gemini-2.5-pro", allEfforts)

        assertTrue(allowed.isEmpty())
    }

    @Test
    fun `gemini 3-1 pro rejects minimal`() {
        val allowed = mapper.allowedEfforts(provider(ProviderKind.GEMINI), "gemini-3.1-pro", allEfforts)

        assertEquals(listOf("low", "medium", "high"), allowed)
    }

    @Test
    fun `gemini 3 pro-preview rejects minimal and medium`() {
        val allowed = mapper.allowedEfforts(provider(ProviderKind.GEMINI), "gemini-3-pro-preview", allEfforts)

        assertEquals(listOf("low", "high"), allowed)
    }

    @Test
    fun `gemini 3-8 flash rejects minimal`() {
        val allowed = mapper.allowedEfforts(provider(ProviderKind.GEMINI), "gemini-3.8-flash", allEfforts)

        assertEquals(listOf("low", "medium", "high"), allowed)
    }

    @Test
    fun `gemini 3 flash keeps the full range`() {
        val allowed = mapper.allowedEfforts(provider(ProviderKind.GEMINI), "gemini-3-flash", allEfforts)

        assertEquals(listOf("minimal", "low", "medium", "high"), allowed)
    }

    // ── wire fields ────────────────────────────────────────────────────────

    @Test
    fun `a plain gateway sends the flat spelling`() {
        val fields = mapper.bodyFields(provider(ProviderKind.OPENAI_COMPAT), "m", "high", null)

        assertEquals("high", fields["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openrouter nests the effort`() {
        val fields = mapper.bodyFields(
            provider(ProviderKind.OPENAI_COMPAT, npm = "@openrouter/ai-sdk-provider"),
            "m",
            "high",
            null,
        )

        assertFalse(fields.containsKey("reasoning_effort"))
        assertEquals("high", fields["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai responses asks for the encrypted reasoning payload too`() {
        val fields = mapper.bodyFields(provider(ProviderKind.OPENAI_RESPONSES), "gpt-5", "medium", null)

        val reasoning = fields["reasoning"]!!.jsonObject
        assertEquals("medium", reasoning["effort"]!!.jsonPrimitive.content)
        assertEquals("auto", reasoning["summary"]!!.jsonPrimitive.content)
        assertEquals(
            "reasoning.encrypted_content",
            fields["include"]!!.jsonArray[0].jsonPrimitive.content,
        )
    }

    @Test
    fun `anthropic puts effort in output_config and thinking in adaptive mode`() {
        val fields = mapper.bodyFields(
            provider(ProviderKind.ANTHROPIC),
            "claude-opus-4-7",
            "high",
            outputTokenLimit = 32_000,
        )

        assertEquals("high", fields["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("adaptive", fields["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic 4-7 asks for summarized thinking text`() {
        val fields = mapper.bodyFields(
            provider(ProviderKind.ANTHROPIC),
            "claude-opus-4-7",
            "high",
            outputTokenLimit = 32_000,
        )

        assertEquals("summarized", fields["thinking"]!!.jsonObject["display"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic 4-6 omits display, which it does not support`() {
        val fields = mapper.bodyFields(
            provider(ProviderKind.ANTHROPIC),
            "claude-sonnet-4-6",
            "high",
            outputTokenLimit = 64_000,
        )

        val thinking = fields["thinking"]!!.jsonObject
        assertNull(thinking["display"])
        assertEquals("adaptive", thinking["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `opus 4-5 keeps the token budget it predates adaptive thinking with`() {
        val fields = mapper.bodyFields(
            provider(ProviderKind.ANTHROPIC),
            "claude-opus-4-5",
            "high",
            outputTokenLimit = 64_000,
        )

        val thinking = fields["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
        // Half the ceiling, capped at 16k by the port.
        assertEquals("16000", thinking["budget_tokens"]!!.jsonPrimitive.content)
        assertEquals("high", fields["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `opus 4-5 with a small ceiling halves it`() {
        val fields = mapper.bodyFields(
            provider(ProviderKind.ANTHROPIC),
            "claude-opus-4-5",
            "high",
            outputTokenLimit = 20_000,
        )

        assertEquals("9999", fields["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content)
    }

    @Test
    fun `opus 4-5 with no declared ceiling sends effort without a budget`() {
        // Inventing either 1024 or max_tokens would be a guess, and the budget
        // must sit between the two.
        val fields = mapper.bodyFields(
            provider(ProviderKind.ANTHROPIC),
            "claude-opus-4-5",
            "high",
            outputTokenLimit = null,
        )

        assertFalse(fields.containsKey("thinking"))
        assertTrue(fields.containsKey("output_config"))
    }

    @Test
    fun `a kimi model on a generic endpoint still asks for summarized text`() {
        val fields = mapper.bodyFields(
            provider(ProviderKind.ANTHROPIC, id = "minimax"),
            "kimi-k2",
            "high",
            outputTokenLimit = 32_000,
        )

        assertEquals("summarized", fields["thinking"]!!.jsonObject["display"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini effort comes back as thinkingConfig, not top-level fields`() {
        val config = mapper.geminiThinkingConfig("gemini-3-flash", "high")

        assertEquals("high", config!!["thinkingLevel"]!!.jsonPrimitive.content)
        // Without this, thought parts arrive as ordinary answer text and the UI
        // cannot tell reasoning from an answer.
        assertEquals("true", config["includeThoughts"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini 2-5 has no thinkingConfig to send`() {
        assertNull(mapper.geminiThinkingConfig("gemini-2.5-flash", "high"))
    }
}
