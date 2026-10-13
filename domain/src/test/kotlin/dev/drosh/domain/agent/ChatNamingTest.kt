package dev.drosh.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The call that goes out beside the answer.
 *
 * The rules that matter are the two "nothing to ask" cases and the fallback: a
 * later message is not a new session, a short message is not describable, and a
 * side-job model that resolves to nothing has to come back as the chat's own
 * model rather than as a call sent to a provider that cannot answer.
 */
class ChatNamingTest {

    private val catalog = CatalogState.Ready(
        providers = listOf(
            CatalogProvider(
                id = "openrouter",
                label = "OpenRouter",
                models = mapOf(
                    "anthropic/claude-sonnet-4" to CatalogModel(
                        id = "anthropic/claude-sonnet-4",
                        label = "Sonnet 4",
                    ),
                    "anthropic/claude-haiku-3" to CatalogModel(
                        id = "anthropic/claude-haiku-3",
                        label = "Haiku 3",
                    ),
                ),
            ),
            CatalogProvider(
                id = "groq",
                label = "Groq",
                models = mapOf("llama-3.1-8b" to CatalogModel(id = "llama-3.1-8b", label = "Llama")),
            ),
        ),
    )

    private val mainProvider = "openrouter"
    private val mainModel = "anthropic/claude-sonnet-4"

    /** Long enough to be worth naming: [ChatTitle.MIN_WORDS] distinct words. */
    private val prompt = "fix the failing build in this project"

    @Test
    fun `a first message earns a naming call`() {
        val request = ChatNaming.request(
            firstMessage = true,
            prompt = prompt,
            sideJobModel = "",
            catalog = catalog,
            mainProviderId = mainProvider,
            mainModelId = mainModel,
        )
        assertEquals(mainProvider, request?.providerId)
        assertEquals(mainModel, request?.modelId)
        // The prompt is carried verbatim — see ChatTitle on why nothing here
        // summarises it.
        assertEquals(prompt, request?.prompt)
    }

    @Test
    fun `a later message earns nothing`() {
        // The second half of the rule that makes this affordable: the naming
        // call is made once per conversation, not once per prompt.
        assertNull(
            ChatNaming.request(
                firstMessage = false,
                prompt = prompt,
                sideJobModel = "",
                catalog = catalog,
                mainProviderId = mainProvider,
                mainModelId = mainModel,
            ),
        )
    }

    @Test
    fun `a message too short to describe earns nothing`() {
        assertNull(
            ChatNaming.request(
                firstMessage = true,
                prompt = "ok",
                sideJobModel = "",
                catalog = catalog,
                mainProviderId = mainProvider,
                mainModelId = mainModel,
            ),
        )
    }

    @Test
    fun `a configured side job is who the title is asked of`() {
        val request = ChatNaming.request(
            firstMessage = true,
            prompt = prompt,
            sideJobModel = "llama-3.1-8b",
            catalog = catalog,
            mainProviderId = mainProvider,
            mainModelId = mainModel,
        )
        assertEquals("groq", request?.providerId)
        assertEquals("llama-3.1-8b", request?.modelId)
    }

    @Test
    fun `the chat's own model answers when no side job is set`() {
        val request = ChatNaming.request(
            firstMessage = true,
            prompt = prompt,
            sideJobModel = "  ",
            catalog = catalog,
            mainProviderId = mainProvider,
            mainModelId = mainModel,
        )
        assertEquals(mainProvider, request?.providerId)
        assertEquals(mainModel, request?.modelId)
    }

    @Test
    fun `a model no provider has falls back to the chat's own`() {
        val request = ChatNaming.request(
            firstMessage = true,
            prompt = prompt,
            sideJobModel = "not-a-real-model",
            catalog = catalog,
            mainProviderId = mainProvider,
            mainModelId = mainModel,
        )
        assertEquals(mainProvider, request?.providerId)
        assertEquals(mainModel, request?.modelId)
    }

    @Test
    fun `a catalog that has not arrived still asks the chat's own model`() {
        val request = ChatNaming.request(
            firstMessage = true,
            prompt = prompt,
            sideJobModel = "llama-3.1-8b",
            catalog = CatalogState.Loading,
            mainProviderId = mainProvider,
            mainModelId = mainModel,
        )
        assertEquals(mainProvider, request?.providerId)
        assertEquals(mainModel, request?.modelId)
    }

    @Test
    fun `the later half of the rule outranks the side job`() {
        // The order matters: a second message with a side job configured is still
        // nothing to ask, and checking the model first would spend the call.
        assertNull(
            ChatNaming.request(
                firstMessage = false,
                prompt = prompt,
                sideJobModel = "llama-3.1-8b",
                catalog = catalog,
                mainProviderId = mainProvider,
                mainModelId = mainModel,
            ),
        )
    }
}
