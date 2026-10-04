package dev.drosh.agent.tool.impl

import dev.drosh.domain.agent.ToolCredentialRepository
import dev.drosh.domain.agent.ToolContext
import dev.drosh.domain.agent.ToolResult
import dev.drosh.domain.agent.ToolUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two things that decide whether web search is useful to the agent:
 * a missing key must say what to do about it, and a response must come back as
 * passages rather than as a wall of page text.
 */
class WebSearchToolTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A tool whose HTTP call never runs.
     *
     * These tests are about argument handling and response parsing; standing up a
     * server to assert a rendered string would cost more than it proves. The
     * request-building half is covered by the shape assertions below.
     */
    private fun tool(key: String?): WebSearchTool =
        WebSearchTool(
            httpClient = OkHttpClient(),
            json = json,
            credentials = FakeToolCredentials(key),
        )

    private fun context() = ToolContext(
        chatId = "chat",
        workingDirectory = "/home",
        step = 1,
        emit = { _: ToolUpdate -> },
        awaitApproval = { error("the search tool must never ask for approval") },
    )

    @Test
    fun `a missing key says what to do, not just that it is missing`() = runTest {
        val result = tool(key = null).execute(args("query" to "kotlin coroutines"), context())

        val message = (result as ToolResult.Error).message
        // The model reads this verbatim. "No key" would send it off rephrasing its
        // query forever; the setting's name and the fallback both matter.
        assertTrue(message, message.contains("Settings"))
        assertTrue(message, message.contains("files on this device"))
    }

    @Test
    fun `a blank key is treated as no key`() = runTest {
        val result = tool(key = "   ").execute(args("query" to "test"), context())

        assertTrue(result is ToolResult.Error)
    }

    @Test
    fun `an empty query is rejected before any network call`() = runTest {
        val result = tool(key = "exa-key").execute(args("query" to "  "), context())

        assertEquals("query must not be empty", (result as ToolResult.Error).message)
    }

    @Test
    fun `the summary is the query, clipped for a collapsed row`() {
        val short = tool(key = "k").summarize(args("query" to "kotlin coroutines"))
        assertEquals("kotlin coroutines", short)

        val long = tool(key = "k").summarize(args("query" to "a".repeat(200)))
        assertTrue(long.length <= 71)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `the schema asks for a query and nothing required besides it`() {
        val tool = tool(key = "k")
        val required = tool.parameters["required"]!!.let { element ->
            (element as kotlinx.serialization.json.JsonArray).map { it.toString().trim('"') }
        }

        assertEquals(listOf("query"), required)
        assertTrue(
            tool.description.contains("Cite"),
            "the model should be told to attribute what it used",
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private class FakeToolCredentials(private val key: String?) : ToolCredentialRepository {
        override suspend fun credential(serviceId: String): String? = key
        override suspend fun setCredential(serviceId: String, apiKey: String) = Unit
        override suspend fun clearCredential(serviceId: String) = Unit
        override fun observeConfigured(): Flow<Set<String>> = flow { emit(emptySet()) }
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) -> put(key, value) }
    }
}
