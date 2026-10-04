package dev.drosh.agent.tool.impl

import dev.drosh.agent.di.AgentNetworkModule.AgentHttpClient
import dev.drosh.domain.agent.ToolCredentialRepository
import dev.drosh.domain.agent.ToolService
import dev.drosh.domain.agent.Tool
import dev.drosh.domain.agent.ToolContext
import dev.drosh.domain.agent.ToolResult
import dev.drosh.domain.agent.intArg
import dev.drosh.domain.agent.stringArg
import dev.drosh.domain.agent.toolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Web search through Exa.
 *
 * ## Why search results come back as highlights, not full pages
 *
 * A full page is 20–100 KB of boilerplate around the two paragraphs that matter.
 * Five full pages would fill the context on their own and leave nothing for the
 * conversation. `contents.highlights` asks Exa for the passages their model
 * considers relevant to the query, which is roughly an order of magnitude
 * smaller and usually contains the answer.
 *
 * A tool that hands back a wall of text is a tool the model learns to stop
 * calling.
 *
 * ## Why `type: auto`
 *
 * `auto` balances depth against latency and is what Exa recommends for
 * interactive use. The cheaper modes (`instant`, `fast`) trade result quality
 * for speed, and the deep modes cost several seconds plus tokens per call. A
 * tool the agent reaches for mid-task should not be the slow one.
 */
@Singleton
class WebSearchTool @Inject constructor(
    @AgentHttpClient private val httpClient: OkHttpClient,
    private val json: Json,
    private val credentials: ToolCredentialRepository,
) : Tool {

    override val name: String = NAME
    override val description: String =
        "Search the web and get back the relevant passages from the best results. " +
            "Use it for anything you cannot answer from the files on this device: a " +
            "library's current API, an error message you have not seen, whether a " +
            "package exists, what a flag does. Cite what you use. If the answer is " +
            "already in this project, read the project instead."
    override val requiresApproval: Boolean = false
    override val parameters: JsonObject = toolSchema {
        string("query", "what to search for, as a natural-language question or phrase")
        integer(
            name = "num_results",
            description = "how many sources to return, 1-10",
            required = false,
        )
        string(
            name = "objective",
            description = "what you are ultimately trying to find out, if the query " +
                "alone does not say it",
            required = false,
        )
    }

    override fun summarize(input: JsonObject): String {
        val query = input.stringArg("query").ifBlank { "(empty)" }
        return if (query.length <= SUMMARY_LIMIT) query else query.take(SUMMARY_LIMIT) + "…"
    }

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val query = input.stringArg("query").trim()
        if (query.isEmpty()) return ToolResult.Error("query must not be empty")

        val key = credentials.credential(ToolService.EXA)
        if (key.isNullOrBlank()) {
            return ToolResult.Error(
                "No web-search key configured. Add an Exa API key in Settings, or " +
                    "answer from the files on this device instead.",
            )
        }

        val numResults = input.intArg("num_results", DEFAULT_RESULTS).coerceIn(1, MAX_RESULTS)

        val payload = buildJsonObject {
            put("query", query)
            put("type", "auto")
            put("numResults", numResults)
            // Highlights, not text: see the class note. The query is passed along so
            // the passages are selected against what was actually asked rather than
            // against the page's generic content.
            put(
                "contents",
                buildJsonObject {
                    put("highlights", buildJsonObject { put("query", query) })
                },
            )
            input.stringArg("objective").trim().takeIf { it.isNotEmpty() }?.let {
                put("objective", it.take(MAX_OBJECTIVE_CHARS))
            }
        }

        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(SEARCH_URL)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("x-api-key", key)
                .header("Accept", "application/json")
                .build()

            val response = try {
                httpClient.newCall(request).execute()
            } catch (e: java.io.IOException) {
                return@withContext ToolResult.Error("web search failed: ${e.message ?: "network error"}")
            }

            response.use {
                val body = it.body.string()
                if (!it.isSuccessful) {
                    return@withContext ToolResult.Error(describeFailure(it.code, body))
                }
                parseResults(body, query)
            }
        }
    }

    private fun parseResults(body: String, query: String): ToolResult {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return ToolResult.Error("web search returned a response we could not read")

        val results = root["results"] as? JsonArray ?: return ToolResult.Success("No results for \"$query\".")

        if (results.isEmpty()) {
            return ToolResult.Success("No results for \"$query\". Try different wording.")
        }

        val rendered = results.mapIndexedNotNull { index, element ->
            renderResult(index, element as? JsonObject ?: return@mapIndexedNotNull null)
        }

        return ToolResult.Success("Results for \"$query\":\n\n" + rendered.joinToString("\n\n"))
    }

    private fun renderResult(index: Int, result: JsonObject): String? {
        val url = result.stringField("url") ?: return null
        val title = result.stringField("title")?.takeIf { it.isNotBlank() } ?: url

        val passages = (result["highlights"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.filter { it.isNotBlank() }
            ?.take(MAX_PASSAGES_PER_RESULT)
            .orEmpty()

        val published = result.stringField("publishedDate")?.take(DATE_CHARS)

        return buildString {
            append('[').append(index + 1).append("] ").append(title).append('\n')
            append(url).append('\n')
            if (!published.isNullOrBlank()) append(published).append('\n')
            if (passages.isEmpty()) {
                append("  (no passage extracted — fetch the page if you need it)\n")
            } else {
                passages.forEach { append("  ").append(it.trim()).append('\n') }
            }
        }
    }

    /**
     * Turn an HTTP failure into something the model can act on.
     *
     * The distinction that matters is 402: out of credits is not retryable and not
     * a query problem, and a model told "unauthorized" will happily rephrase its
     * query forever.
     */
    private fun describeFailure(code: Int, body: String): String {
        val message = runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root.stringField("error")
        }.getOrNull()

        val detail = message?.takeIf { it.isNotBlank() } ?: "no message"
        return when (code) {
            401 -> "Web search key was rejected (401): $detail"
            402 -> "Web search is out of credits (402): $detail"
            429 -> "Web search rate limited (429): $detail"
            in 500..599 -> "Web search is unavailable ($code): $detail"
            else -> "Web search failed ($code): $detail"
        }
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val NAME = "web_search"
        const val SEARCH_URL = "https://api.exa.ai/search"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Five, not ten.
         *
         * Exa bills per result, and the model rarely reads past the first three
         * useful ones — the rest are context spent for nothing. It can always ask
         * again with different wording.
         */
        const val DEFAULT_RESULTS = 5
        const val MAX_RESULTS = 10

        /** Per result. Beyond this the passages stop being a summary. */
        const val MAX_PASSAGES_PER_RESULT = 3

        const val SUMMARY_LIMIT = 70
        const val DATE_CHARS = 10
        const val MAX_OBJECTIVE_CHARS = 4_096
    }
}
