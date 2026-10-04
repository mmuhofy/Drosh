package dev.drosh.agent.stream

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallBufferTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun buffer() = ToolCallBuffer(json)

    @Test
    fun `fragments of one call reassemble into whole arguments`() {
        val buffer = buffer()

        buffer.append(0, id = "call_a", name = "shell", argsDelta = "")
        buffer.append(0, id = null, name = null, argsDelta = "{\"comm")
        buffer.append(0, id = null, name = null, argsDelta = "and\":\"ls -la\"}")

        val calls = buffer.finishAll()
        assertEquals(1, calls.size)
        assertEquals("call_a", calls.single().id)
        assertEquals("shell", calls.single().name)
        assertEquals("ls -la", calls.single().arguments["command"].jsonPrimitive.content)
    }

    @Test
    fun `arguments split mid escape still parse`() {
        // Providers chunk JSON at arbitrary byte boundaries, including inside an
        // escape sequence. Concatenating as text is what makes this safe.
        val buffer = buffer()

        buffer.append(0, "call_a", "write_file", "{\"path\":\"a\\\"")
        buffer.append(0, null, null, "b.tsx\"}")

        val args = buffer.finishAll().single().arguments
        assertEquals("a\"b.tsx", args["path"].jsonPrimitive.content)
    }

    @Test
    fun `two calls interleaved on separate indexes stay separate`() {
        val buffer = buffer()

        buffer.append(0, "call_a", "shell", "{\"command\":\"a")
        buffer.append(1, "call_b", "read_file", "{\"path\":\"b")
        buffer.append(0, null, null, "1\"}")
        buffer.append(1, null, null, ".txt\"}")

        val calls = buffer.finishAll()
        assertEquals(2, calls.size)

        val byId = calls.associateBy { it.id }
        assertEquals("shell", byId.getValue("call_a").name)
        assertEquals("read_file", byId.getValue("call_b").name)
        assertEquals("a1", byId.getValue("call_a").arguments["command"].jsonPrimitive.content)
        assertEquals("b.txt", byId.getValue("call_b").arguments["path"].jsonPrimitive.content)
    }

    @Test
    fun `a call with no arguments yields an empty object`() {
        val buffer = buffer()

        buffer.append(0, "call_a", "list_sessions", null)

        val args = buffer.finishAll().single().arguments
        assertTrue(args.isEmpty())
    }

    @Test
    fun `malformed argument json becomes an empty object instead of throwing`() {
        // The tool then reports which argument it wanted, which the model can act
        // on. Throwing would kill the run over a provider's typo.
        val buffer = buffer()

        buffer.append(0, "call_a", "shell", "{\"command\": not json at all")

        val args = buffer.finishAll().single().arguments
        assertTrue(args.isEmpty())
    }

    @Test
    fun `a fragment with neither id nor name is dropped`() {
        val buffer = buffer()

        assertNull(buffer.append(0, id = null, name = null, argsDelta = "{}"))
        assertEquals(0, buffer.pendingCount)
        assertTrue(buffer.finishAll().isEmpty())
    }

    @Test
    fun `a later fragment carrying an id rescues an earlier name-only fragment`() {
        val buffer = buffer()

        buffer.append(0, id = null, name = "shell", argsDelta = "{\"comm")
        buffer.append(0, id = "call_a", name = null, argsDelta = "and\":\"ls\"}")

        val call = buffer.finishAll().single()
        assertEquals("call_a", call.id)
        assertEquals("shell", call.name)
    }

    @Test
    fun `a call that never receives an id still gets a stable handle`() {
        // A tool result is correlated by call id, so a missing id must still
        // produce something the result can be attached to.
        val buffer = buffer()

        buffer.append(0, null, "shell", "{\"command\":\"ls\"}")

        val call = buffer.finishAll().single()
        assertNotNull(call.id)
        assertTrue(call.id.isNotBlank())
    }

    @Test
    fun `isNew is true only for the first fragment of an index`() {
        val buffer = buffer()

        assertTrue(buffer.append(0, "call_a", "shell", "")!!.isNew)
        assertEquals(false, buffer.append(0, null, null, "{}")!!.isNew)
    }

    @Test
    fun `finishAll clears pending state`() {
        val buffer = buffer()
        buffer.append(0, "call_a", "shell", "{}")

        assertEquals(1, buffer.finishAll().size)
        assertEquals(0, buffer.pendingCount)
        assertTrue(buffer.finishAll().isEmpty())
    }
}

class ReasoningTextTest {

    private fun obj(json: String) = Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject

    @Test
    fun `reads the reasoning_content spelling`() {
        assertEquals("thinking", obj("""{"reasoning_content":"thinking"}""").reasoningText())
    }

    @Test
    fun `reads the reasoning spelling used by several compatible gateways`() {
        assertEquals("thinking", obj("""{"reasoning":"thinking"}""").reasoningText())
    }

    @Test
    fun `prefers reasoning_content when both are present`() {
        val both = obj("""{"reasoning_content":"first","reasoning":"second"}""")
        assertEquals("first", both.reasoningText())
    }

    @Test
    fun `returns null when the field is absent or empty`() {
        assertNull(obj("""{"content":"hi"}""").reasoningText())
        assertNull(obj("""{"reasoning":""}""").reasoningText())
        assertNull(obj("""{"reasoning":123}""").reasoningText())
    }
}
