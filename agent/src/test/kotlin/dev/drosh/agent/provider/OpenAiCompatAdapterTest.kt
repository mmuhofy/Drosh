package dev.drosh.agent.provider

import dev.drosh.domain.agent.AgentLimits
import dev.drosh.domain.agent.FinishReason
import dev.drosh.domain.agent.LlmCredential
import dev.drosh.domain.agent.LlmMessage
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.LlmRequest
import dev.drosh.domain.agent.LlmToolCall
import dev.drosh.domain.agent.ProviderKind
import dev.drosh.domain.agent.ToolDefinition
import dev.drosh.domain.agent.toolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The wire format is asserted against literal payloads rather than through a
 * stub server: cheaper, and it pins the exact bytes OpenRouter receives instead
 * of whatever a fake happens to produce.
 *
 * No request is ever sent — `parseFrame` and `buildBody` are pure.
 *
 * Every JSON literal below is kept on one line and free of `$`. Multi-line raw
 * strings and templates inside them are legal Kotlin but make a stray imbalance
 * very hard to see, and there is no local build to catch it.
 */
class OpenAiCompatAdapterTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val adapter = OpenAiCompatAdapter(OkHttpClient(), json)

    private val provider = LlmProvider(
        id = "openrouter",
        label = "OpenRouter",
        kind = ProviderKind.OPENAI_COMPAT,
        baseUrl = "https://openrouter.ai/api/v1",
        modelsPath = "models",
        extraHeaders = mapOf("X-Title" to "Drosh"),
    )

    // ── frame parsing ─────────────────────────────────────────────────────

    @Test
    fun `parses a text delta`() {
        val frame = adapter.parseFrame("""{"choices":[{"delta":{"content":"Hello"}}]}""")

        assertTrue(frame is OpenAiCompatAdapter.Frame.Text)
        assertEquals("Hello", (frame as OpenAiCompatAdapter.Frame.Text).delta)
    }

    @Test
    fun `parses reasoning under both spellings providers use`() {
        val plain = adapter.parseFrame("""{"choices":[{"delta":{"reasoning":"hmm"}}]}""")
        val content = adapter.parseFrame("""{"choices":[{"delta":{"reasoning_content":"hmm"}}]}""")

        assertEquals("hmm", (plain as OpenAiCompatAdapter.Frame.Reasoning).delta)
        assertEquals("hmm", (content as OpenAiCompatAdapter.Frame.Reasoning).delta)
    }

    @Test
    fun `parses a tool call fragment`() {
        val payload = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_a",""" +
            """"function":{"name":"shell","arguments":""}}]}}]}"""
        val frame = adapter.parseFrame(payload)

        val fragment = frame as OpenAiCompatAdapter.Frame.ToolFragment
        assertEquals(0, fragment.index)
        assertEquals("call_a", fragment.id)
        assertEquals("shell", fragment.name)
        assertEquals("", fragment.argsDelta)
    }

    @Test
    fun `parses an argument fragment with no id or name`() {
        // Only the first fragment for an index carries identity; the rest are
        // bare argument text.
        val payload = """{"choices":[{"delta":{"tool_calls":[{"index":0,""" +
            """"function":{"arguments":"{}"}}]}}]}"""
        val frame = adapter.parseFrame(payload) as OpenAiCompatAdapter.Frame.ToolFragment

        assertNull(frame.id)
        assertNull(frame.name)
        assertEquals("{}", frame.argsDelta)
    }

    @Test
    fun `a fragment without an index defaults to zero`() {
        // Some gateways omit the index entirely. Assembling everything into one
        // call beats dropping the tool call on the floor.
        val payload = """{"choices":[{"delta":{"tool_calls":[{"id":"call_a",""" +
            """"function":{"name":"shell","arguments":"{}"}}]}}]}"""
        val frame = adapter.parseFrame(payload) as OpenAiCompatAdapter.Frame.ToolFragment

        assertEquals(0, frame.index)
    }

    @Test
    fun `parses every finish reason`() {
        // A regular escaped string rather than a raw one: interpolating into a raw
        // string here would need a four-quote run to put the value inside quotes,
        // which parses but reads as a typo.
        fun reasonOf(raw: String): FinishReason {
            val payload = "{\"choices\":[{\"delta\":{},\"finish_reason\":\"$raw\"}]}"
            return (adapter.parseFrame(payload) as OpenAiCompatAdapter.Frame.Finish).reason
        }

        assertEquals(FinishReason.STOP, reasonOf("stop"))
        assertEquals(FinishReason.MAX_TOKENS, reasonOf("length"))
        assertEquals(FinishReason.TOOL_CALLS, reasonOf("tool_calls"))
        assertEquals(FinishReason.ERROR, reasonOf("error"))
        assertEquals(FinishReason.OTHER, reasonOf("content_filter"))
        assertEquals(FinishReason.OTHER, reasonOf("something_new"))
    }

    @Test
    fun `parses the trailing usage chunk that has empty choices`() {
        val payload = """{"choices":[],"usage":{"prompt_tokens":812,"completion_tokens":96,""" +
            """"cache_read_input_tokens":512,"reasoning_tokens":40}}"""
        val frame = adapter.parseFrame(payload)

        val usage = (frame as OpenAiCompatAdapter.Frame.Usage).usage
        assertEquals(812, requireNotNull(usage.input))
        assertEquals(96, requireNotNull(usage.output))
        assertEquals(512, requireNotNull(usage.cacheRead))
        assertEquals(40, requireNotNull(usage.reasoning))
    }

    @Test
    fun `an error object inside a 200 response is a failure, not text`() {
        val payload = """{"error":{"code":401,"message":"User not found."},""" +
            """"choices":[{"finish_reason":"error"}]}"""
        val frame = adapter.parseFrame(payload)

        assertEquals(
            "User not found.",
            (frame as OpenAiCompatAdapter.Frame.Failure).message,
        )
    }

    @Test
    fun `an error object without a message still reports something`() {
        val frame = adapter.parseFrame("""{"error":{"code":500},"choices":[]}""")

        assertTrue((frame as OpenAiCompatAdapter.Frame.Failure).message.isNotBlank())
    }

    @Test
    fun `malformed json is ignored rather than thrown`() {
        assertTrue(adapter.parseFrame("not json at all") is OpenAiCompatAdapter.Frame.Ignore)
        assertTrue(adapter.parseFrame("") is OpenAiCompatAdapter.Frame.Ignore)
        assertTrue(adapter.parseFrame("[1,2,3]") is OpenAiCompatAdapter.Frame.Ignore)
    }

    @Test
    fun `an unknown field does not break parsing`() {
        val payload = """{"choices":[{"delta":{"content":"hi","some_new_field":42}}],""" +
            """"system_fingerprint":"fp_1"}"""
        val frame = adapter.parseFrame(payload)

        assertEquals("hi", (frame as OpenAiCompatAdapter.Frame.Text).delta)
    }

    @Test
    fun `an empty delta is ignored`() {
        assertTrue(adapter.parseFrame("""{"choices":[{"delta":{}}]}""") is OpenAiCompatAdapter.Frame.Ignore)
    }

    // ── request building ──────────────────────────────────────────────────

    @Test
    fun `body requests a stream and opts into usage`() {
        // Without stream_options.include_usage, streaming responses carry no token
        // counts at all and the UI has nothing to show after a long run.
        val body = adapter.buildBody(
            LlmRequest(model = "anthropic/claude-sonnet-4", messages = listOf(LlmMessage.User("hi"))),
        )

        assertEquals("anthropic/claude-sonnet-4", body["model"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals(
            "true",
            body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `system prompt is sent as a system message`() {
        val body = adapter.buildBody(
            LlmRequest(
                model = "m",
                messages = listOf(LlmMessage.User("hi")),
                systemPrompt = "You are a shell agent.",
            ),
        )

        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("You are a shell agent.", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a blank system prompt is omitted entirely`() {
        val body = adapter.buildBody(
            LlmRequest(model = "m", messages = listOf(LlmMessage.User("hi")), systemPrompt = "   "),
        )

        assertEquals(1, body["messages"]!!.jsonArray.size)
    }

    @Test
    fun `an assistant turn that only called tools sends null content`() {
        // The protocol requires the key to be present, as null, in this case.
        val body = adapter.buildBody(
            LlmRequest(
                model = "m",
                messages = listOf(
                    LlmMessage.Assistant(
                        text = "",
                        toolCalls = listOf(
                            LlmToolCall("call_a", "shell", buildJsonObject { put("command", "ls") }),
                        ),
                    ),
                ),
            ),
        )

        val assistant = body["messages"]!!.jsonArray[0].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        // The key must be present with a null value, not omitted: a provider
        // rejects an assistant message whose content is missing outright.
        assertEquals(JsonNull, assistant["content"])

        val toolCall = assistant["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("call_a", toolCall["id"]!!.jsonPrimitive.content)
        assertEquals("function", toolCall["type"]!!.jsonPrimitive.content)

        val function = toolCall["function"]!!.jsonObject
        assertEquals("shell", function["name"]!!.jsonPrimitive.content)
        // Arguments travel as a JSON string, not as a nested object.
        assertEquals("""{"command":"ls"}""", function["arguments"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an assistant turn with text keeps both text and tool calls`() {
        val body = adapter.buildBody(
            LlmRequest(
                model = "m",
                messages = listOf(
                    LlmMessage.Assistant(
                        text = "Let me look.",
                        toolCalls = listOf(
                            LlmToolCall("call_a", "read_file", buildJsonObject { put("path", "a.kt") }),
                        ),
                    ),
                ),
            ),
        )

        val assistant = body["messages"]!!.jsonArray[0].jsonObject
        assertEquals("Let me look.", assistant["content"]!!.jsonPrimitive.content)
        assertEquals(1, assistant["tool_calls"]!!.jsonArray.size)
    }

    @Test
    fun `tool results are sent with their call id`() {
        val body = adapter.buildBody(
            LlmRequest(
                model = "m",
                messages = listOf(
                    LlmMessage.ToolResultMessage("call_a", "shell", "total 0"),
                ),
            ),
        )

        val message = body["messages"]!!.jsonArray[0].jsonObject
        assertEquals("tool", message["role"]!!.jsonPrimitive.content)
        assertEquals("call_a", message["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("total 0", message["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools are encoded in function form`() {
        val body = adapter.buildBody(
            LlmRequest(
                model = "m",
                messages = listOf(LlmMessage.User("list files")),
                tools = listOf(
                    ToolDefinition(
                        name = "read_file",
                        description = "Read a file",
                        parameters = toolSchema { string("path", "absolute path") },
                    ),
                ),
            ),
        )

        val tool = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)

        val function = tool["function"]!!.jsonObject
        assertEquals("read_file", function["name"]!!.jsonPrimitive.content)
        assertEquals("Read a file", function["description"]!!.jsonPrimitive.content)

        val parameters = function["parameters"]!!.jsonObject
        assertEquals("object", parameters["type"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("path"),
            parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `no tools key is sent when the tool list is empty`() {
        val body = adapter.buildBody(
            LlmRequest(model = "m", messages = listOf(LlmMessage.User("hi"))),
        )

        assertFalse(body.containsKey("tools"))
    }

    @Test
    fun `sampling params are omitted unless set`() {
        val plain = adapter.buildBody(
            LlmRequest(model = "m", messages = listOf(LlmMessage.User("hi"))),
        )
        assertFalse(plain.containsKey("temperature"))
        assertFalse(plain.containsKey("max_tokens"))

        val tuned = adapter.buildBody(
            LlmRequest(
                model = "m",
                messages = listOf(LlmMessage.User("hi")),
                temperature = 0.2,
                maxOutputTokens = 4096,
            ),
        )
        assertEquals("0.2", tuned["temperature"]!!.jsonPrimitive.content)
        assertEquals("4096", tuned["max_tokens"]!!.jsonPrimitive.content)
    }

    // ── http request ──────────────────────────────────────────────────────

    @Test
    fun `request targets chat completions with auth and provider headers`() {
        val request = adapter.buildHttpRequest(
            provider = provider,
            request = LlmRequest(model = "m", messages = listOf(LlmMessage.User("hi"))),
            credential = LlmCredential("openrouter", "sk-test"),
        )

        assertEquals("https://openrouter.ai/api/v1/chat/completions", request.url.toString())
        assertEquals("Bearer sk-test", request.header("Authorization"))
        assertEquals("text/event-stream", request.header("Accept"))
        assertEquals("Drosh", request.header("X-Title"))
    }

    @Test
    fun `a trailing slash on the base url does not double up`() {
        assertEquals(
            "https://openrouter.ai/api/v1/chat/completions",
            provider.copy(baseUrl = "https://openrouter.ai/api/v1/").chatCompletionsUrl,
        )
    }

    @Test
    fun `the models url is derived from the provider config`() {
        assertEquals("https://openrouter.ai/api/v1/models", provider.modelsUrl)
    }
}

class ProviderErrorClassifierTest {

    private fun decision(code: Int) = ProviderErrorClassifier.classify(code)

    @Test
    fun `auth and payment problems are not retried`() {
        // Resending with a bad key cannot help, and three attempts of backoff just
        // make the user wait longer to see the real message.
        assertEquals(ProviderErrorClassifier.Decision.NEEDS_CREDENTIALS, decision(401))
        assertEquals(ProviderErrorClassifier.Decision.NEEDS_CREDENTIALS, decision(403))
        assertEquals(ProviderErrorClassifier.Decision.NEEDS_CREDENTIALS, decision(402))
    }

    @Test
    fun `rate limits and server faults are retried`() {
        assertEquals(ProviderErrorClassifier.Decision.RETRY, decision(429))
        assertEquals(ProviderErrorClassifier.Decision.RETRY, decision(408))
        assertEquals(ProviderErrorClassifier.Decision.RETRY, decision(500))
        assertEquals(ProviderErrorClassifier.Decision.RETRY, decision(503))
        assertEquals(ProviderErrorClassifier.Decision.RETRY, decision(599))
    }

    @Test
    fun `a malformed request is fatal`() {
        // A bad tool schema fails identically every time.
        assertEquals(ProviderErrorClassifier.Decision.FATAL, decision(400))
        assertEquals(ProviderErrorClassifier.Decision.FATAL, decision(404))
        assertEquals(ProviderErrorClassifier.Decision.FATAL, decision(422))
    }

    @Test
    fun `a dropped connection is retryable`() {
        assertEquals(
            ProviderErrorClassifier.Decision.RETRY,
            ProviderErrorClassifier.classify(IOException("connection reset")),
        )
        assertEquals(
            ProviderErrorClassifier.Decision.RETRY,
            ProviderErrorClassifier.classify(IOException()),
        )
    }

    @Test
    fun `cancellation is never classified as retryable`() {
        // Treating a user pressing stop as a transient failure would immediately
        // restart the run they just ended.
        assertEquals(
            ProviderErrorClassifier.Decision.FATAL,
            ProviderErrorClassifier.classify(CancellationException("stop")),
        )
    }

    @Test
    fun `backoff doubles from the base delay`() {
        val base = AgentLimits.RETRY_BASE_DELAY_MS
        val max = AgentLimits.RETRY_MAX_DELAY_MS

        assertEquals(1_000L, ProviderErrorClassifier.backoffMs(1, base, max))
        assertEquals(2_000L, ProviderErrorClassifier.backoffMs(2, base, max))
        assertEquals(4_000L, ProviderErrorClassifier.backoffMs(3, base, max))
    }

    @Test
    fun `backoff is capped`() {
        val max = AgentLimits.RETRY_MAX_DELAY_MS

        assertEquals(max, ProviderErrorClassifier.backoffMs(20, AgentLimits.RETRY_BASE_DELAY_MS, max))
        assertEquals(max, ProviderErrorClassifier.backoffMs(1_000, AgentLimits.RETRY_BASE_DELAY_MS, max))
    }
}
