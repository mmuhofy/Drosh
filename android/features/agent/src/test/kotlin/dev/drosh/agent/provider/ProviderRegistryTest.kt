package dev.drosh.agent.provider

import dev.drosh.domain.agent.ChatAdapter
import dev.drosh.domain.agent.LlmCredential
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.LlmRequest
import dev.drosh.domain.agent.LlmStreamEvent
import dev.drosh.domain.agent.ProviderKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the failure that shipped: `@Multibinds` declared an adapter set and no
 * adapter was ever bound into it, so the set was valid and empty, Dagger
 * compiled, and the first run failed at the user's prompt rather than at build
 * time.
 *
 * The registry cannot see its own bindings — that is what Hilt assembles — so
 * this pins the behaviour that must not regress: a protocol with no adapter is
 * named at the call site, and one with an adapter returns that adapter.
 */
class ProviderRegistryTest {

    private val openAiCompat = LlmProvider(
        id = "openrouter",
        label = "OpenRouter",
        kind = ProviderKind.OPENAI_COMPAT,
        baseUrl = "https://openrouter.ai/api/v1",
    )

    private val gemini = LlmProvider(
        id = "gemini",
        label = "Gemini",
        kind = ProviderKind.GEMINI,
        baseUrl = "https://generativelanguage.googleapis.com",
    )

    @Test
    fun `returns the adapter matching the provider protocol`() {
        val adapter = StubAdapter(ProviderKind.OPENAI_COMPAT)
        val registry = ProviderRegistry(setOf(adapter))

        assertSame(adapter, registry.adapterFor(openAiCompat))
    }

    @Test
    fun `an unimplemented protocol names itself and what is available`() {
        // Gemini is the protocol nothing implements in this test's registry —
        // not in the app, where all four are bound. Kept as the "missing
        // adapter" case because a future protocol will need it again.
        val registry = ProviderRegistry(setOf(StubAdapter(ProviderKind.OPENAI_COMPAT)))

        val error = runCatching { registry.adapterFor(gemini) }.exceptionOrNull()

        // The message is the whole point: "Gemini is not implemented yet" tells
        // the user what to do, where "no adapter implements" told them nothing
        // about which half of their setup was wrong.
        val message = error?.message.orEmpty()
        assertTrue(message, message.contains("Gemini"))
        assertTrue(message, message.contains("GEMINI"))
        assertTrue(message, message.contains("OPENAI_COMPAT"))
    }

    @Test
    fun `an empty registry fails with the same guidance rather than a bare null`() {
        val error = runCatching { ProviderRegistry(emptySet()).adapterFor(openAiCompat) }
            .exceptionOrNull()

        assertTrue(error is UnsupportedOperationException)
        assertTrue(
            "should say nothing is registered, not just that nothing matched",
            error!!.message!!.contains("none"),
        )
    }

    @Test
    fun `supportedKinds reflects what was actually bound`() {
        val registry = ProviderRegistry(
            setOf(
                StubAdapter(ProviderKind.OPENAI_COMPAT),
                StubAdapter(ProviderKind.GEMINI),
                StubAdapter(ProviderKind.ANTHROPIC),
                StubAdapter(ProviderKind.OPENAI_RESPONSES),
            ),
        )

        assertEquals(
            setOf(
                ProviderKind.OPENAI_COMPAT,
                ProviderKind.GEMINI,
                ProviderKind.ANTHROPIC,
                ProviderKind.OPENAI_RESPONSES,
            ),
            registry.supportedKinds(),
        )
    }

    private class StubAdapter(override val kind: ProviderKind) : ChatAdapter {
        override fun stream(
            provider: LlmProvider,
            request: LlmRequest,
            credential: LlmCredential,
        ): Flow<LlmStreamEvent> = emptyFlow()
    }
}
