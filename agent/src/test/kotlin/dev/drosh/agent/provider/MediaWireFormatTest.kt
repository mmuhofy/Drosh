package dev.drosh.agent.provider

import dev.drosh.domain.agent.AttachmentLimits
import dev.drosh.domain.agent.LlmMessage
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.LlmRequest
import dev.drosh.domain.agent.MessageAttachment
import dev.drosh.domain.agent.ProviderKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The multimodal turn, asserted against the body each protocol builds.
 *
 * Same approach as the other wire-format tests: no server, no network. A user
 * turn carrying a picture is the one shape where all four protocols disagree,
 * and a wrong guess is not a test failure at the adapter — it is a 400 from a
 * live endpoint several seconds after someone pressed send.
 */
class MediaWireFormatTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val efforts = EffortMapper()

    private val compat = OpenAiCompatAdapter(OkHttpClient(), json, efforts)
    private val responses = OpenAiResponsesAdapter(OkHttpClient(), json, efforts)
    private val anthropic = AnthropicAdapter(OkHttpClient(), json, efforts)
    private val gemini = GeminiAdapter(OkHttpClient(), json, efforts)

    private val compatProvider = LlmProvider(
        id = "openrouter",
        label = "OpenRouter",
        kind = ProviderKind.OPENAI_COMPAT,
        baseUrl = "https://openrouter.ai/api/v1",
    )

    private val responsesProvider = LlmProvider(
        id = "openai",
        label = "OpenAI",
        kind = ProviderKind.OPENAI_RESPONSES,
        baseUrl = "https://api.openai.com/v1",
    )

    private val anthropicProvider = LlmProvider(
        id = "anthropic",
        label = "Anthropic",
        kind = ProviderKind.ANTHROPIC,
        baseUrl = "https://api.anthropic.com/v1",
    )

    private val geminiProvider = LlmProvider(
        id = "google",
        label = "Google",
        kind = ProviderKind.GEMINI,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta",
    )

    private val png = MessageAttachment.fromBase64(
        id = "a1",
        mediaType = "image/png",
        base64 = "AAAA",
        fileName = "shot.png",
        byteSize = 3,
    )

    private fun request(text: String, attachments: List<MessageAttachment>) = LlmRequest(
        model = "m",
        messages = listOf(LlmMessage.User(text, attachments)),
        maxOutputTokens = 4096,
    )

    // ── Chat Completions ───────────────────────────────────────────────────

    @Test
    fun `a text-only turn stays a string, because that is what it always was`() {
        val body = compat.buildBody(compatProvider, request("hi", emptyList()))
        val message = body["messages"]!!.jsonArray.last().jsonObject

        assertEquals("hi", message["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an image becomes an image_url part carrying the whole data url`() {
        val body = compat.buildBody(compatProvider, request("what is this", listOf(png)))
        val message = body["messages"]!!.jsonArray.last().jsonObject

        val content = message["content"]!!.jsonArray
        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("what is this", content[0].jsonObject["text"]!!.jsonPrimitive.content)

        val image = content[1].jsonObject
        assertEquals("image_url", image["type"]!!.jsonPrimitive.content)
        val url = image["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertEquals("data:image/png;base64,AAAA", url)
    }

    @Test
    fun `a picture on its own carries no empty text part`() {
        val body = compat.buildBody(compatProvider, request("", listOf(png)))
        val content = body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray

        assertEquals(1, content.size)
        assertEquals("image_url", content[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    // ── Responses ─────────────────────────────────────────────────────────

    @Test
    fun `the responses api uses input_image under a key of its own naming`() {
        val body = responses.buildBody(responsesProvider, request("what is this", listOf(png)))
        val item = body["input"]!!.jsonArray.last().jsonObject

        val content = item["content"]!!.jsonArray
        assertEquals("input_text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("input_image", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        // Flat, not nested under `image_url` like Chat Completions.
        assertEquals("data:image/png;base64,AAAA", content[1].jsonObject["image_url"]!!.jsonPrimitive.content)
    }

    // ── Anthropic ─────────────────────────────────────────────────────────

    @Test
    fun `anthropic takes three fields where the other one takes a data url`() {
        val body = anthropic.buildBody(anthropicProvider, request("what is this", listOf(png)))
        val message = body["messages"]!!.jsonArray.last().jsonObject

        val image = message["content"]!!.jsonArray[1].jsonObject
        assertEquals("image", image["type"]!!.jsonPrimitive.content)

        val source = image["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", source["media_type"]!!.jsonPrimitive.content)
        // The prefix is removed: Anthropic takes the payload, not the URL.
        assertEquals("AAAA", source["data"]!!.jsonPrimitive.content)
    }

    // ── Gemini ────────────────────────────────────────────────────────────

    @Test
    fun `gemini takes inline data with the mime type beside it`() {
        val body = gemini.buildBody(geminiProvider, request("what is this", listOf(png)))
        val contents = body["contents"]!!.jsonArray.last().jsonObject

        val parts = contents["parts"]!!.jsonArray
        assertEquals("what is this", parts[0].jsonObject["text"]!!.jsonPrimitive.content)

        val inline = parts[1].jsonObject["inlineData"]!!.jsonObject
        assertEquals("image/png", inline["mimeType"]!!.jsonPrimitive.content)
        assertEquals("AAAA", inline["data"]!!.jsonPrimitive.content)
    }

    // ── which protocol carries what ────────────────────────────────────────

    @Test
    fun `only gemini has a documented inline path for video`() {
        val clip = MessageAttachment.fromBase64(
            id = "v1",
            mediaType = "video/mp4",
            base64 = "AAAA",
            fileName = "clip.mp4",
        )

        assertTrue(MediaEncoding.accepts(ProviderKind.GEMINI, clip))
        assertFalse(MediaEncoding.accepts(ProviderKind.OPENAI_COMPAT, clip))
        assertFalse(MediaEncoding.accepts(ProviderKind.OPENAI_RESPONSES, clip))
        assertFalse(MediaEncoding.accepts(ProviderKind.ANTHROPIC, clip))
    }

    @Test
    fun `every protocol carries an image`() {
        val kinds = ProviderKind.entries
        kinds.forEach { assertTrue(MediaEncoding.accepts(it, png)) }
    }

    @Test
    fun `firstUnsupported names the file that cannot be sent`() {
        val clip = MessageAttachment.fromBase64(
            id = "v1",
            mediaType = "video/mp4",
            base64 = "AAAA",
            fileName = "clip.mp4",
        )

        val refused = MediaEncoding.firstUnsupported(
            ProviderKind.ANTHROPIC,
            listOf(png, clip),
        )

        assertEquals("clip.mp4", refused?.fileName)
        assertTrue(MediaEncoding.acceptsAll(ProviderKind.GEMINI, listOf(png, clip)))
    }

    @Test
    fun `an empty attachment list is never a problem`() {
        assertTrue(MediaEncoding.acceptsAll(ProviderKind.ANTHROPIC, emptyList()))
        assertEquals(null, MediaEncoding.firstUnsupported(ProviderKind.ANTHROPIC, emptyList()))
    }

    @Test
    fun `the data url the adapters read is the one the app builds`() {
        // One form, three spellings: if the app and the adapters disagreed about
        // the prefix, every image would reach a provider as an empty payload.
        assertEquals("data:image/png;base64,AAAA", AttachmentLimits.dataUrlFor("image/png", "AAAA"))
        assertEquals("AAAA", png.base64Payload)
    }
}