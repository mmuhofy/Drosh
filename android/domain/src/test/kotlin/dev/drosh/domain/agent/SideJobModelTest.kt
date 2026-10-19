package dev.drosh.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which model answers the jobs nobody asked for.
 *
 * The rule that matters is the fallback: a side job that cannot run is not worth
 * an error, so every unresolvable value has to come back as the conversation's
 * own model rather than as a blank or a throw. The tests below hold that to a
 * catalog with exactly one provider holding exactly one model, because the
 * interesting cases are all about what happens when the setting is empty,
 * unknown, or the catalog has not arrived.
 */
class SideJobModelTest {

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
                models = mapOf(
                    "llama-3.1-8b" to CatalogModel(id = "llama-3.1-8b", label = "Llama 3.1 8B"),
                ),
            ),
        ),
    )

    private val mainProvider = "openrouter"
    private val mainModel = "anthropic/claude-sonnet-4"

    @Test
    fun `an empty setting runs the side job on the main model`() {
        val resolved = SideJobModel.resolve(catalog, "", mainProvider, mainModel)
        assertEquals(mainProvider, resolved.providerId)
        assertEquals(mainModel, resolved.modelId)
    }

    @Test
    fun `a blank setting is the same as an empty one`() {
        val resolved = SideJobModel.resolve(catalog, "   ", mainProvider, mainModel)
        assertEquals(mainProvider, resolved.providerId)
        assertEquals(mainModel, resolved.modelId)
    }

    @Test
    fun `a model in the selected provider is used where it is`() {
        val resolved = SideJobModel.resolve(
            catalog, "anthropic/claude-haiku-3", mainProvider, mainModel,
        )
        assertEquals("openrouter", resolved.providerId)
        assertEquals("anthropic/claude-haiku-3", resolved.modelId)
    }

    @Test
    fun `a cheap model on another provider is reached on that provider`() {
        // The whole point of the setting: a side job is worth handing to whoever
        // has the small model, and that is rarely the provider in use.
        val resolved = SideJobModel.resolve(catalog, "llama-3.1-8b", mainProvider, mainModel)
        assertEquals("groq", resolved.providerId)
        assertEquals("llama-3.1-8b", resolved.modelId)
    }

    @Test
    fun `a model no provider has heard of falls back to the main model`() {
        val resolved = SideJobModel.resolve(catalog, "not-a-real-model", mainProvider, mainModel)
        assertEquals(mainProvider, resolved.providerId)
        assertEquals(mainModel, resolved.modelId)
    }

    @Test
    fun `a catalog that has not arrived falls back to the main model`() {
        val resolved = SideJobModel.resolve(
            CatalogState.Loading, "llama-3.1-8b", mainProvider, mainModel,
        )
        assertEquals(mainProvider, resolved.providerId)
        assertEquals(mainModel, resolved.modelId)
    }

    @Test
    fun `a failed catalog falls back to the main model`() {
        val resolved = SideJobModel.resolve(
            CatalogState.Failed("offline", retryable = true), "llama-3.1-8b", mainProvider, mainModel,
        )
        assertEquals(mainProvider, resolved.providerId)
        assertEquals(mainModel, resolved.modelId)
    }

    @Test
    fun `a setting with padding around the id still resolves`() {
        val resolved = SideJobModel.resolve(
            catalog, "  llama-3.1-8b  ", mainProvider, mainModel,
        )
        assertEquals("groq", resolved.providerId)
        assertEquals("llama-3.1-8b", resolved.modelId)
    }

    @Test
    fun `the first provider that declares a model wins, so resolution is stable`() {
        val twoOwners = CatalogState.Ready(
            providers = listOf(
                CatalogProvider(id = "openrouter", label = "OpenRouter", models = mapOf("m" to CatalogModel("m", "M"))),
                CatalogProvider(id = "groq", label = "Groq", models = mapOf("m" to CatalogModel("m", "M"))),
            ),
        )
        assertEquals("openrouter", SideJobModel.resolve(twoOwners, "m", "groq", "other").providerId)
    }

    @Test
    fun `a model is only known once the catalog has it`() {
        assertTrue(catalog.hasModel("llama-3.1-8b"))
        assertFalse(catalog.hasModel("not-a-real-model"))
        assertFalse(catalog.hasModel(""))
        assertFalse(CatalogState.Loading.hasModel("llama-3.1-8b"))
    }

    @Test
    fun `an empty catalog knows nothing`() {
        assertFalse(CatalogState.Ready(emptyList()).hasModel("llama-3.1-8b"))
    }
}
