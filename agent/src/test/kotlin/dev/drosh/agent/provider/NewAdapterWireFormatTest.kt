package dev.drosh.agent.provider

import dev.drosh.domain.agent.FinishReason
import dev.drosh.domain.agent.LlmCredential
import dev.drosh.domain.agent.LlmMessage
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.LlmRequest
import dev.drosh.domain.agent.LlmToolCall
import dev.drosh.domain.agent.ProviderKind
import dev.drosh.domain.agent.ToolDefinition
import dev.drosh.domain.agent.toolSchema
import kotlinx.serialization.json.Json
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

/**
 * The three new wire formats, asserted against literal payloads.
 *
 * Same approach as `OpenAiCompatAdapterTest`: no server, no network. The request
 * bodies and the parsed frames are what a provider actually sees, so a change to
 * either shows up here rather than as a 400 from a live endpoint.
 *
 * Every JSON literal is kept on one line and free of `$`.
 */
class NewAdapterWireFormatTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val efforts = EffortMapper()

    private val anthropic = AnthropicAdapter(OkHttpClient(), json, efforts)
    private val gemini = GeminiAdapter(OkHttpClient(), json, efforts)
    private val responses = OpenAiResponsesAdapter(OkHttpClient(), json, efforts)

    private val anthropicProvider = LlmProvider(
        id = "anthropic",
        label = "Anthropic",
        kind = ProviderKind.ANTHROPIC,
        baseUrl = "https://api.anthropic.com/v1",
        npm = "@ai-sdk/anthropic",
        keyEnvName = "ANTHROPIC_API_KEY",
    )

    private val geminiProvider = LlmProvider(
        id = "google",
        label = "Google",
        kind = ProviderKind.GEMINI,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
        npm = "@ai-sdk/google",
        keyEnvName = "GOOGLE_API_KEY",
    )

    private val responsesProvider = LlmProvider(
        id = "openai",
        label = "OpenAI",
        kind = ProviderKind.OPENAI_RESPONSES,
        baseUrl = "https://api.openai.com/v1",
        npm = "@ai-sdk/openai",
        keyEnvName = "OPENAI_API_KEY",
    )

    private val credential = LlmCredential("test", "sk-test")
    private val simpleRequest = LlmRequest(
        model = "m",
        messages = listOf(LlmMessage.User("hi")),
        maxOutputTokens = 4096,
    )

    // ── endpoints and headers ──────────────────────────────────────────────

    @Test
    fun `anthropic posts to messages with x-api-key, not a bearer token`() {
        val request = anthropic.buildHttpRequest(anthropicProvider, simpleRequest, credential)

        assertEquals("https://api.anthropic.com/v1/messages", request.url.toString())
        assertEquals("sk-test", request.header("x-api-key"))
        assertEquals("2023-06-01", request.header("anthropic-version"))
        // A bearer header here reads as a wrong key, so its absence is the test.
        assertNull(request.header("Authorization"))
    }

    @Test
    fun `gemini puts the model in the path and asks for sse`() {
        val request = gemini.buildHttpRequest(
            geminiProvider,
            simpleRequest.copy(model = "gemini-3-flash"),
            credential,
        )

        // The colon survives rather than being percent-escaped, which is why the
        // path is built with encodedPath.
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "gemini-3-flash:streamGenerateContent?alt=sse",
            request.url.toString(),
        )
        assertEquals("sk-test", request.header("x-goog-api-key"))
    }

    @Test
    fun `responses posts to responses with a bearer token`() {
        val request = responses.buildHttpRequest(responsesProvider, simpleRequest, credential)

        assertEquals("https://api.openai.com/v1/responses", request.url.toString())
        assertEquals("Bearer sk-test", request.header("Authorization"))
    }

    // ── request bodies ─────────────────────────────────────────────────────

    @Test
    fun `anthropic sends max_tokens, which is required`() {
        val body = anthropic.buildBody(anthropicProvider, simpleRequest)

        assertEquals("4096", body["max_tokens"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic refuses to build a body without max_tokens`() {
        val error = runCatching {
            anthropic.buildBody(anthropicProvider, simpleRequest.copy(maxOutputTokens = null))
        }.exceptionOrNull()

        assertTrue(error is MissingMaxTokens)
        assertTrue(error!!.message!!.contains("max_tokens"))
    }

    @Test
    fun `anthropic sends the system prompt as blocks, not a message`() {
        val body = anthropic.buildBody(
            anthropicProvider,
            simpleRequest.copy(systemPrompt = "You are a shell agent."),
        )

        val system = body["system"]!!.jsonArray
        assertEquals("text", system[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("You are a shell agent.", system[0].jsonObject["text"]!!.jsonPrimitive.content)
        // Not a `system` role inside messages.
        assertFalse(
            body["messages"]!!.jsonArray.any {
                it.jsonObject["role"]!!.jsonPrimitive.content == "system"
            },
        )
    }

    @Test
    fun `anthropic encodes a tool call as a tool_use block with a nested object`() {
        val body = anthropic.buildBody(
            anthropicProvider,
            simpleRequest.copy(
                messages = listOf(
                    LlmMessage.Assistant(
                        text = "Looking.",
                        toolCalls = listOf(
                            LlmToolCall("call_a", "read_file", buildJsonObject { put("path", "a.kt") }),
                        ),
                    ),
                ),
            ),
        )

        val assistant = body["messages"]!!.jsonArray[0].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)

        val blocks = assistant["content"]!!.jsonArray
        assertEquals("text", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("tool_use", blocks[1].jsonObject["type"]!!.jsonPrimitive.content)
        // Nested, not the JSON string Chat Completions takes.
        assertEquals(
            "a.kt",
            blocks[1].jsonObject["input"]!!.jsonObject["path"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `anthropic merges consecutive tool results into one user message`() {
        // Two `user` messages in a row is a 400 from Anthropic about alternating
        // roles, which is the failure a 1:1 mapping of ToolResultMessage produces.
        val body = anthropic.buildBody(
            anthropicProvider,
            simpleRequest.copy(
                messages = listOf(
                    LlmMessage.Assistant(
                        text = "",
                        toolCalls = listOf(
                            LlmToolCall("call_a", "read_file", buildJsonObject { put("path", "a.kt") }),
                            LlmToolCall("call_b", "read_file", buildJsonObject { put("path", "b.kt") }),
                        ),
                    ),
                    LlmMessage.ToolResultMessage("call_a", "read_file", "aaa"),
                    LlmMessage.ToolResultMessage("call_b", "read_file", "bbb"),
                ),
            ),
        )

        val messages = body["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("assistant", messages[0].jsonObject["role"]!!.jsonPrimitive.content)

        val blocks = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(2, blocks.size)
        assertEquals("call_a", blocks[0].jsonObject["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("call_b", blocks[1].jsonObject["tool_use_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic omits an empty text block on a tool-only turn`() {
        val body = anthropic.buildBody(
            anthropicProvider,
            simpleRequest.copy(
                messages = listOf(
                    LlmMessage.Assistant(
                        text = "",
                        toolCalls = listOf(LlmToolCall("call_a", "shell", buildJsonObject {})),
                    ),
                ),
            ),
        )

        val blocks = body["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray
        assertEquals(1, blocks.size)
        assertEquals("tool_use", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini sends systemInstruction and declares tools under functionDeclarations`() {
        val body = gemini.buildBody(
            geminiProvider,
            LlmRequest(
                model = "gemini-3-flash",
                messages = listOf(LlmMessage.User("list files")),
                systemPrompt = "You are a shell agent.",
                tools = listOf(
                    ToolDefinition(
                        name = "read_file",
                        description = "Read a file",
                        parameters = toolSchema { string("path", "absolute path") },
                    ),
                ),
            ),
        )

        assertEquals(
            "You are a shell agent.",
            body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0]
                .jsonObject["text"]!!.jsonPrimitive.content,
        )

        val declarations = body["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals("read_file", declarations[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini nests effort under generationConfig thinkingConfig`() {
        val body = gemini.buildBody(
            geminiProvider,
            LlmRequest(
                model = "gemini-3-flash",
                messages = listOf(LlmMessage.User("hi")),
                maxOutputTokens = 8192,
                reasoningEffort = "high",
            ),
        )

        val generationConfig = body["generationConfig"]!!.jsonObject
        assertEquals("8192", generationConfig["maxOutputTokens"]!!.jsonPrimitive.content)

        val thinking = generationConfig["thinkingConfig"]!!.jsonObject
        assertEquals("high", thinking["thinkingLevel"]!!.jsonPrimitive.content)
        assertEquals("true", thinking["includeThoughts"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini sends a functionResponse naming the tool`() {
        val body = gemini.buildBody(
            geminiProvider,
            LlmRequest(
                model = "gemini-3-flash",
                messages = listOf(LlmMessage.ToolResultMessage("call_a", "shell", "total 0")),
            ),
        )

        val part = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray[0].jsonObject
        val fn = part["functionResponse"]!!.jsonObject
        assertEquals("shell", fn["name"]!!.jsonPrimitive.content)
        assertEquals("total 0", fn["response"]!!.jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `responses sends an item list, not messages`() {
        val body = responses.buildBody(
            responsesProvider,
            LlmRequest(
                model = "gpt-5",
                messages = listOf(LlmMessage.User("hi")),
                systemPrompt = "You are a shell agent.",
            ),
        )

        // No `messages` key exists in this protocol at all.
        assertFalse(body.containsKey("messages"))

        assertEquals("You are a shell agent.", body["instructions"]!!.jsonPrimitive.content)
        assertEquals("false", body["store"]!!.jsonPrimitive.content)

        val input = body["input"]!!.jsonArray
        assertEquals("user", input[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("hi", input[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `responses encodes a tool call and its result as separate items`() {
        val body = responses.buildBody(
            responsesProvider,
            LlmRequest(
                model = "gpt-5",
                messages = listOf(
                    LlmMessage.Assistant(
                        text = "",
                        toolCalls = listOf(LlmToolCall("call_a", "shell", buildJsonObject { put("command", "ls") })),
                    ),
                    LlmMessage.ToolResultMessage("call_a", "shell", "total 0"),
                ),
            ),
        )

        val input = body["input"]!!.jsonArray
        // One item per thing, and arguments as a JSON string.
        assertEquals("function_call", input[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("call_a", input[0].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals(
            """{"command":"ls"}""",
            input[0].jsonObject["arguments"]!!.jsonPrimitive.content,
        )

        assertEquals("function_call_output", input[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("call_a", input[1].jsonObject["call_id"]!!.jsonPrimitive.content)
        assertEquals("total 0", input[1].jsonObject["output"]!!.jsonPrimitive.content)
    }

    // ── stream parsing ─────────────────────────────────────────────────────

    @Test
    fun `anthropic parses a text delta and a thinking delta`() {
        val text = anthropic.parseFrame(
            """{"type":"content_block_delta","index":0,""" +
                """"delta":{"type":"text_delta","text":"Hello"}}""",
        )
        val thinking = anthropic.parseFrame(
            """{"type":"content_block_delta","index":0,""" +
                """"delta":{"type":"thinking_delta","thinking":"hmm"}}""",
        )

        assertEquals("Hello", (text as AnthropicAdapter.Frame.Text).delta)
        assertEquals("hmm", (thinking as AnthropicAdapter.Frame.Reasoning).delta)
    }

    @Test
    fun `anthropic reads usage off message_start`() {
        val frame = anthropic.parseFrame(
            """{"type":"message_start","message":{"usage":{"input_tokens":812,""" +
                """"cache_read_input_tokens":512,"output_tokens":3}}}""",
        )

        val usage = (frame as AnthropicAdapter.Frame.Usage).usage
        assertEquals(812, usage.input)
        assertEquals(512, usage.cacheRead)
    }

    @Test
    fun `anthropic maps each stop reason`() {
        fun reasonOf(raw: String): FinishReason {
            val payload = """{"type":"message_delta","delta":{"stop_reason":"$raw"}}"""
            return (anthropic.parseFrame(payload) as AnthropicAdapter.Frame.Stop).reason
        }

        assertEquals(FinishReason.STOP, reasonOf("end_turn"))
        assertEquals(FinishReason.STOP, reasonOf("stop_sequence"))
        assertEquals(FinishReason.MAX_TOKENS, reasonOf("max_tokens"))
        assertEquals(FinishReason.TOOL_CALLS, reasonOf("tool_use"))
        assertEquals(FinishReason.ERROR, reasonOf("refusal"))
        assertEquals(FinishReason.OTHER, reasonOf("something_new"))
    }

    @Test
    fun `anthropic recognises a tool_use block opening and closing`() {
        val start = anthropic.parseFrame(
            """{"type":"content_block_start","index":0,""" +
                """"content_block":{"type":"tool_use","id":"toolu_1","name":"shell"}}""",
        )
        val args = anthropic.parseFrame(
            """{"type":"content_block_delta","index":0,""" +
                """"delta":{"type":"input_json_delta","partial_json":"{\"a\":"}}""",
        )
        val done = anthropic.parseFrame("""{"type":"content_block_stop","index":0}""")

        assertEquals(0, (start as AnthropicAdapter.Frame.ToolStart).blockIndex)
        assertEquals("toolu_1", start.id)
        assertEquals("shell", start.name)
        assertEquals("""{"a":""", (args as AnthropicAdapter.Frame.ToolArgs).delta)
        assertEquals(0, (done as AnthropicAdapter.Frame.ToolDone).blockIndex)
    }

    @Test
    fun `gemini separates reasoning from the answer by the thought flag`() {
        val thought = gemini.parseFrame(
            """{"candidates":[{"content":{"role":"model","parts":""" +
                """[{"text":"planning","thought":true,"thoughtSignature":"sig_1"}]}}]}""",
        )
        val answer = gemini.parseFrame(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Done"}]}}]}""",
        )

        assertEquals("planning", (thought as GeminiAdapter.Frame.Reasoning).delta)
        assertEquals("Done", (answer as GeminiAdapter.Frame.Text).delta)
    }

    @Test
    fun `gemini carries the thought signature off a function call`() {
        val frame = gemini.parseFrame(
            """{"candidates":[{"content":{"parts":[{"thoughtSignature":"sig_2",""" +
                """"functionCall":{"name":"shell","args":{"command":"ls"}}}]}}]}""",
        )

        val call = frame as GeminiAdapter.Frame.ToolCall
        assertEquals("shell", call.name)
        assertEquals("ls", call.arguments["command"]!!.jsonPrimitive.content)
        // Required back on the next request; Gemini 3 rejects without it.
        assertEquals("sig_2", call.thoughtSignature)
    }

    @Test
    fun `gemini reads the finish reason and the usage off the same chunk`() {
        // Both arrive together on the terminal chunk, which is why the finish
        // frame carries the counts.
        val frame = gemini.parseFrame(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"Done"}]},""" +
                """"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":11,""" +
                """"candidatesTokenCount":7,"thoughtsTokenCount":40,"totalTokenCount":58}}""",
        )

        val finish = frame as GeminiAdapter.Frame.Finish
        assertEquals(FinishReason.STOP, finish.reason)
        assertEquals(11, finish.usage!!.input)
        // candidatesTokenCount is visible-only, so thoughts are added.
        assertEquals(47, finish.usage!!.output)
        assertEquals(40, finish.usage!!.reasoning)
    }

    @Test
    fun `gemini maps MAX_TOKENS and a safety block`() {
        val truncated = gemini.parseFrame("""{"candidates":[{"finishReason":"MAX_TOKENS"}]}""")
        val blocked = gemini.parseFrame("""{"candidates":[{"finishReason":"SAFETY"}]}""")

        assertEquals(FinishReason.MAX_TOKENS, (truncated as GeminiAdapter.Frame.Finish).reason)
        assertEquals(FinishReason.ERROR, (blocked as GeminiAdapter.Frame.Finish).reason)
    }

    @Test
    fun `responses parses text, reasoning and argument deltas`() {
        val text = responses.parseFrame(
            """{"type":"response.output_text.delta","delta":"Hello"}""",
        )
        val reasoning = responses.parseFrame(
            """{"type":"response.reasoning_summary_text.delta","delta":"hmm"}""",
        )
        val args = responses.parseFrame(
            """{"type":"response.function_call_arguments.delta","item_id":"item_1",""" +
                """"delta":"{\"a\":"}""",
        )

        assertEquals("Hello", (text as OpenAiResponsesAdapter.Frame.Text).delta)
        assertEquals("hmm", (reasoning as OpenAiResponsesAdapter.Frame.Reasoning).delta)
        assertEquals("item_1", (args as OpenAiResponsesAdapter.Frame.ToolArgs).itemId)
    }

    @Test
    fun `responses opens a tool call from output_item_added`() {
        val frame = responses.parseFrame(
            """{"type":"response.output_item.added","item":{"type":"function_call",""" +
                """"id":"fc_1","call_id":"call_a","name":"shell"}}""",
        )

        val start = frame as OpenAiResponsesAdapter.Frame.ToolStart
        // `item_id` is the stream key, `call_id` is the model-visible id, and
        // they are different values.
        assertEquals("fc_1", start.itemId)
        assertEquals("call_a", start.callId)
        assertEquals("shell", start.name)
    }

    @Test
    fun `responses reads the nested usage breakdown`() {
        val frame = responses.parseFrame(
            """{"type":"response.completed","response":{"id":"resp_1","usage":""" +
                """{"input_tokens":812,"output_tokens":96,"input_tokens_details":""" +
                """{"cached_tokens":512},"output_tokens_details":{"reasoning_tokens":40}}}}""",
        )

        val finish = frame as OpenAiResponsesAdapter.Frame.Finish
        assertEquals(FinishReason.STOP, finish.reason)
        assertEquals(812, finish.usage!!.input)
        assertEquals(512, finish.usage!!.cacheRead)
        assertEquals(40, finish.usage!!.reasoning)
    }

    @Test
    fun `responses reads a truncation from incomplete_details`() {
        val frame = responses.parseFrame(
            """{"type":"response.incomplete","response":{"id":"resp_1",""" +
                """"incomplete_details":{"reason":"max_output_tokens"}}}""",
        )

        assertEquals(
            FinishReason.MAX_TOKENS,
            (frame as OpenAiResponsesAdapter.Frame.Finish).reason,
        )
    }

    @Test
    fun `responses reads a failure off response_failed and off error`() {
        val failed = responses.parseFrame(
            """{"type":"response.failed","response":{"error":{"code":"rate_limit_exceeded",""" +
                """"message":"Slow down"}}}""",
        )
        val error = responses.parseFrame("""{"type":"error","message":"boom"}""")

        // Both spellings carry the same nested shape and both end the turn.
        assertEquals("Slow down", (failed as OpenAiResponsesAdapter.Frame.Failure).message)
        assertEquals("boom", (error as OpenAiResponsesAdapter.Frame.Failure).message)
    }
}
