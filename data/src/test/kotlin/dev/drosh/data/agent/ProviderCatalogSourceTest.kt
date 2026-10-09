package dev.drosh.data.agent

import dev.drosh.domain.agent.CatalogModel
import dev.drosh.domain.agent.CatalogProvider
import dev.drosh.domain.agent.ModelStatus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The catalog trim, asserted against a miniature of the real document.
 *
 * Every JSON literal is kept on one line and free of `$`, because the upstream
 * document contains `${VAR}` placeholders that a Kotlin template would eat — the
 * one case that needs one writes it as `${'$'}{` so it survives.
 *
 * `fold` is pure and touches no Android API, so this runs on the JVM.
 */
class ProviderCatalogSourceTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val providersSerializer = ListSerializer(CatalogProvider.serializer())

    private val source = ProviderCatalogSource(File.createTempFile("catalog", ".json"))

    @Test
    fun `folds a provider down to the fields drosh reads`() {
        val document = """
            {"deepinfra":{"id":"deepinfra","name":"Deep Infra","env":["DEEPINFRA_API_KEY"],
            "npm":"@ai-sdk/deepinfra","api":"https://api.deepinfra.com/v1",
            "doc":"https://deepinfra.com/models",
            "models":{"tencent/hy4":{"id":"tencent/hy4","name":"Hy4 preview",
            "reasoning":true,"tool_call":true,"attachment":false,"temperature":true,
            "limit":{"context":262144,"output":128000},
            "modalities":{"input":["text"],"output":["text"]},
            "cost":{"input":0.13,"output":0.53},
            "release_date":"2026-07-06","family":"Hy"}}}}
        """.trimIndent()

        val providers = source.fold(document)

        assertEquals(1, providers.size)
        val provider = providers.single()
        assertEquals("deepinfra", provider.id)
        assertEquals("Deep Infra", provider.label)
        // The first documented env var names the key field.
        assertEquals("DEEPINFRA_API_KEY", provider.keyEnvName)
        assertEquals("@ai-sdk/deepinfra", provider.npm)
        // Reachable with a key, so nothing to ask the user for.
        assertNull(provider.unsupportedReason)

        val model = provider.models.getValue("tencent/hy4")
        assertEquals("Hy4 preview", model.label)
        assertEquals(128000, model.outputTokenLimit)
        assertTrue(model.toolCall)
        assertFalse(model.attachment)
        assertTrue(model.reasoning)
        assertEquals(ModelStatus.ACTIVE, model.status)
    }

    @Test
    fun `an unsupported provider says why`() {
        val document = """
            {"amazon-bedrock":{"id":"amazon-bedrock","name":"Amazon Bedrock",
            "env":["AWS_ACCESS_KEY_ID"],"npm":"@ai-sdk/amazon-bedrock",
            "models":{"claude":{"id":"claude","name":"Claude"}}}}
        """.trimIndent()

        val provider = source.fold(document).single()

        assertTrue(provider.isUnsupported)
        assertTrue(provider.unsupportedReason!!.contains("SigV4"))
    }

    @Test
    fun `reads effort values, mapping a null to none`() {
        // A null in `values` is how the catalog writes "no reasoning".
        val document = """
            {"x":{"id":"x","name":"X","api":"https://x.test/v1",
            "models":{"a":{"id":"a","name":"A","reasoning_options":[{"type":"effort",
            "values":["low","medium",null]}]},
            "b":{"id":"b","name":"B","reasoning_options":[{"type":"toggle"}]},
            "c":{"id":"c","name":"C"}}}}
        """.trimIndent()

        val models = source.fold(document).single().models

        assertEquals(listOf("low", "medium", "none"), models.getValue("a").reasoningEfforts)
        // A toggle option carries no effort values, so nothing is offered.
        assertTrue(models.getValue("b").reasoningEfforts.isEmpty())
        assertTrue(models.getValue("c").reasoningEfforts.isEmpty())
    }

    @Test
    fun `status is carried through`() {
        val document = """
            {"x":{"id":"x","name":"X","api":"https://x.test/v1","models":{
            "a":{"id":"a","name":"A","status":"deprecated"},
            "b":{"id":"b","name":"B","status":"beta"},
            "c":{"id":"c","name":"C","status":"alpha"}}}}
        """.trimIndent()

        val models = source.fold(document).single().models

        assertEquals(ModelStatus.DEPRECATED, models.getValue("a").status)
        assertEquals(ModelStatus.BETA, models.getValue("b").status)
        assertEquals(ModelStatus.ALPHA, models.getValue("c").status)
    }

    @Test
    fun `a provider with no models is dropped`() {
        val document = """
            {"empty":{"id":"empty","name":"Empty","api":"https://x.test/v1","models":{}},
            "full":{"id":"full","name":"Full","api":"https://x.test/v1",
            "models":{"m":{"id":"m","name":"M"}}}}
        """.trimIndent()

        val providers = source.fold(document)

        assertEquals(1, providers.size)
        assertEquals("full", providers.single().id)
    }

    @Test
    fun `a malformed entry costs that entry, not the catalog`() {
        val document = """
            {"good":{"id":"good","name":"Good","api":"https://x.test/v1",
            "models":{"m":{"id":"m","name":"M"}}},
            "bad":{"id":"bad","name":"Bad","api":"https://x.test/v1"}}
        """.trimIndent()

        val providers = source.fold(document)

        // "bad" has no `models` at all, which the walk cannot use.
        assertEquals(1, providers.size)
        assertEquals("good", providers.single().id)
    }

    @Test
    fun `a document that is not json yields nothing rather than throwing`() {
        assertTrue(source.fold("not json at all").isEmpty())
        assertTrue(source.fold("[1,2,3]").isEmpty())
    }

    @Test
    fun `placeholders in an endpoint become the config fields`() {
        val document = """
            {"cf":{"id":"cf","name":"Cloudflare","env":["CLOUDFLARE_ACCOUNT_ID"],
            "api":"https://api.cloudflare.com/client/v4/accounts/${'$'}{CLOUDFLARE_ACCOUNT_ID}/ai/v1",
            "models":{"m":{"id":"m","name":"M"}}}}
        """.trimIndent()

        val provider = source.fold(document).single()

        assertEquals(listOf("CLOUDFLARE_ACCOUNT_ID"), provider.configKeys)
    }

    @Test
    fun `an endpoint resolves from the values it names and nothing else`() {
        val provider = CatalogProvider(
            id = "cf",
            label = "Cloudflare",
            apiTemplate = "https://api.cloudflare.com/client/v4/accounts/ACCOUNT_ID_PLACEHOLDER/ai/v1"
                .replace("ACCOUNT_ID_PLACEHOLDER", "\${CLOUDFLARE_ACCOUNT_ID}"),
            models = mapOf("m" to CatalogModel(id = "m", label = "M")),
        )

        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/abc123/ai/v1",
            provider.resolveEndpoint(mapOf("CLOUDFLARE_ACCOUNT_ID" to "abc123")),
        )
    }

    @Test
    fun `a missing value leaves the placeholder rather than a broken url`() {
        // The caller treats a surviving `${...}` as "not configured", which is
        // the difference between asking for a value and sending a URL with a
        // variable name inside it.
        val provider = CatalogProvider(
            id = "cf",
            label = "Cloudflare",
            apiTemplate = "https://x.test/accounts/\${MISSING}/v1",
            models = mapOf("m" to CatalogModel(id = "m", label = "M")),
        )

        val resolved = provider.resolveEndpoint(emptyMap())

        assertEquals("https://x.test/accounts/\${MISSING}/v1", resolved)
        assertTrue(resolved!!.contains("\${"))
    }

    @Test
    fun `an endpoint with no placeholders is returned unchanged`() {
        val provider = CatalogProvider(
            id = "x",
            label = "X",
            apiTemplate = "https://x.test/v1",
            models = mapOf("m" to CatalogModel(id = "m", label = "M")),
        )

        assertEquals("https://x.test/v1", provider.resolveEndpoint(emptyMap()))
    }

    @Test
    fun `a folded provider survives the cache round-trip`() {
        // Exercises the serialisers the source actually uses, which is what a
        // catalog that could be folded but not written back would break.
        val providers = source.fold(
            """
            {"x":{"id":"x","name":"X","api":"https://x.test/v1","models":{"m":{"id":"m","name":"M"}}}}
            """.trimIndent(),
        )

        val text = json.encodeToString(providersSerializer, providers)
        val restored = json.decodeFromString(providersSerializer, text)

        assertEquals(providers, restored)
    }
}
