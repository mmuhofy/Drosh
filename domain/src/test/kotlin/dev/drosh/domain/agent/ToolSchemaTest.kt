package dev.drosh.domain.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaTest {

    @Test
    fun `required is emitted as a json array not a string`() {
        // The previous schema builder wrote `"required": "a,b"`. That is a JSON
        // string, not an array, so strict providers drop it and the model starts
        // inventing arguments for optional fields.
        val schema = toolSchema {
            string("path", "file path")
            integer("lines", "how many")
            string("encoding", "text encoding", required = false)
        }

        val required = schema["required"]!!.jsonArray
        assertEquals(listOf("path", "lines"), required.map { it.jsonPrimitive.content })
    }

    @Test
    fun `omits required entirely when nothing is required`() {
        val schema = toolSchema {
            string("query", "search text", required = false)
        }

        assertFalse(schema.containsKey("required"))
    }

    @Test
    fun `declares object type and properties`() {
        val schema = toolSchema {
            string("path", "file path")
            bool("force", "overwrite")
        }

        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
        val props = schema["properties"]!!.jsonObject
        assertEquals(setOf("path", "force"), props.keys)
        assertEquals("string", props["path"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("boolean", props["force"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `enum becomes a json array`() {
        val schema = toolSchema {
            string("unit", "unit", enum = listOf("s", "ms"))
        }

        val enum = schema["properties"]!!.jsonObject["unit"]!!.jsonObject["enum"]!!.jsonArray
        assertEquals(listOf("s", "ms"), enum.map { it.jsonPrimitive.content })
    }

    @Test
    fun `no arguments schema is a valid empty object schema`() {
        assertEquals("object", NO_ARGUMENTS["type"]!!.jsonPrimitive.content)
        assertTrue(NO_ARGUMENTS["properties"]!!.jsonObject.isEmpty())
    }
}

class ToolArgumentTest {

    @Test
    fun `reads typed arguments with fallbacks`() {
        val input = buildJsonObject {
            put("path", "src/App.ktx")
            put("lines", 40)
            put("force", true)
        }

        assertEquals("src/App.ktx", input.stringArg("path"))
        assertEquals(7, input.intArg("lines", 7))
        assertTrue(input.boolArg("force", false))
    }

    @Test
    fun `falls back when an argument is missing or wrongly typed`() {
        val input = buildJsonObject { put("lines", "not-a-number") }

        assertEquals("", input.stringArg("path"))
        assertEquals(7, input.intArg("lines", 7))
        assertEquals(9, input.intArg("absent", 9))
        assertFalse(input.boolArg("force", false))
        assertNull(input.stringArgOrNull("path"))
    }
}

class ToolOutputTrimmerTest {

    @Test
    fun `short output passes through untouched`() {
        val output = "hello\nworld"

        val result = ToolOutputTrimmer.trim(output)

        assertEquals(output, result.text)
        assertFalse(result.truncated)
    }

    @Test
    fun `long output is clipped and flagged`() {
        val output = (1..5_000).joinToString("\n") { "line $it" }

        val result = ToolOutputTrimmer.trim(output, maxChars = 500, maxLines = 100)

        assertTrue(result.truncated)
        assertTrue(result.text.length <= 500)
        assertTrue(result.text.contains("output truncated"))
    }

    @Test
    fun `keeps the tail so a trailing error survives`() {
        val output = buildString {
            append("running command\n")
            repeat(400) { append("noise line $it\n") }
            append("ERROR: exit code 1\n")
        }

        val result = ToolOutputTrimmer.trim(output, maxChars = 600, maxLines = 60)

        assertTrue(result.truncated)
        assertTrue(
            "expected the trailing error to be preserved, got: ${result.text.takeLast(120)}",
            result.text.contains("ERROR: exit code 1"),
        )
    }

    @Test
    fun `keeps the head so the invocation survives`() {
        val output = buildString {
            append("\$ npm run build\n")
            repeat(400) { append("noise $it\n") }
        }

        val result = ToolOutputTrimmer.trim(output, maxChars = 600, maxLines = 60)

        assertTrue(result.text.contains("npm run build"))
    }

    @Test
    fun `a single enormous line does not produce output larger than the input`() {
        val output = "x".repeat(50_000)

        val result = ToolOutputTrimmer.trim(output, maxChars = 1_000)

        assertTrue(result.truncated)
        assertTrue(result.text.length <= 1_000)
    }

    @Test
    fun `many short lines are clipped by the line limit alone`() {
        val output = (1..10_000).joinToString("\n") { "l$it" }

        val result = ToolOutputTrimmer.trim(output, maxChars = 1_000_000, maxLines = 50)

        assertTrue(result.truncated)
    }
}

class TokenUsageTest {

    @Test
    fun `total sums the non overlapping buckets`() {
        val usage = TokenUsage(input = 100, output = 40, cacheRead = 10, cacheWrite = 5)

        assertEquals(155, requireNotNull(usage.total))
    }

    @Test
    fun `reasoning is excluded because it is a subset of output`() {
        val usage = TokenUsage(input = 100, output = 40, reasoning = 30)

        assertEquals(140, requireNotNull(usage.total))
    }

    @Test
    fun `total is null when the provider reported nothing`() {
        assertNull(TokenUsage().total)
        assertNull(TokenUsage(input = 0, output = 0).total)
    }
}
