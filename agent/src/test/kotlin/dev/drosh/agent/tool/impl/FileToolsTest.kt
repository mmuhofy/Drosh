package dev.drosh.agent.tool.impl

import dev.drosh.agent.tool.GuestPaths
import dev.drosh.domain.agent.ApprovalDecision
import dev.drosh.domain.agent.ApprovalRequest
import dev.drosh.domain.agent.ToolContext
import dev.drosh.domain.agent.ToolResult
import dev.drosh.domain.agent.ToolUpdate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import java.io.File

/**
 * The two file tools, tested against a real directory.
 *
 * They do no network and no PRoot, so a temp directory exercises the actual
 * traversal, glob and overwrite logic — the parts where a unit test with a mocked
 * filesystem would be testing the mock.
 */
class FileToolsTest {

    private lateinit var root: File

    /**
     * A GuestPaths whose guest root and working directory are the same temp
     * directory, so a path like `a.kt` resolves to a real file here.
     */
    private fun guestPaths(): GuestPaths = GuestPaths(root, root.path)

    private fun context(
        onApproval: (ApprovalRequest) -> ApprovalDecision = { ApprovalDecision.Approve },
    ) = ToolContext(
        chatId = "chat",
        workingDirectory = root.path,
        step = 1,
        emit = { _: ToolUpdate -> Unit },
        awaitApproval = { request -> onApproval(request) },
    )

    private fun write(path: String, content: String): File =
        File(root, path).apply { parentFile?.mkdirs(); writeText(content) }

    private fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "drosh-tools-${System.nanoTime()}")
        root.mkdirs()
    }

    private fun tearDown() {
        root.deleteRecursively()
    }

    // ── grep ──────────────────────────────────────────────────────────────

    @Test
    fun `finds a match with its file and line number`() = runTest {
        setUp()
        try {
            write("a.kt", "fun alpha() {\n    val x = 1\n}\n")
            write("b.kt", "fun beta() = 2\n")

            val result = GrepTool(guestPaths())
                .execute(args("pattern" to "fun"), context())

            val output = (result as ToolResult.Success).output
            assertTrue(output.contains("a.kt:1"))
            assertTrue(output.contains("b.kt:1"))
            assertTrue("should not match the val line: $output", !output.contains(":2:"))
        } finally {
            tearDown()
        }
    }

    @Test
    fun `a glob limits which files are searched`() = runTest {
        setUp()
        try {
            write("a.kt", "target\n")
            write("b.txt", "target\n")

            val output = (GrepTool(guestPaths())
                .execute(args("pattern" to "target", "glob" to "*.kt"), context())
                as ToolResult.Success).output

            assertTrue(output.contains("a.kt"))
            assertTrue("txt should have been skipped: $output", !output.contains("b.txt"))
        } finally {
            tearDown()
        }
    }

    @Test
    fun `results stop at the cap and say so`() = runTest {
        setUp()
        try {
            write("many.txt", (1..50).joinToString("\n") { "hit $it" })

            val output = (GrepTool(guestPaths())
                .execute(args("pattern" to "hit", "max_results" to "5"), context())
                as ToolResult.Success).output

            assertEquals(5, output.lines().count { it.contains("hit") })
            assertTrue("should say it stopped early: $output", output.contains("stopped at the result limit"))
        } finally {
            tearDown()
        }
    }

    @Test
    fun `no matches says so and lists what was searched`() = runTest {
        setUp()
        try {
            write("a.kt", "fun alpha() {}\n")

            val output = (GrepTool(guestPaths())
                .execute(args("pattern" to "absent"), context())
                as ToolResult.Success).output

            assertTrue(output.startsWith("No matches"))
        } finally {
            tearDown()
        }
    }

    @Test
    fun `an invalid regex is refused with an explanation`() = runTest {
        setUp()
        try {
            write("a.kt", "x\n")

            val result = GrepTool(guestPaths())
                .execute(args("pattern" to "([unclosed"), context())

            val message = (result as ToolResult.Error).message
            assertTrue(message.contains("not a valid regular expression"))
        } finally {
            tearDown()
        }
    }

    @Test
    fun `an empty pattern is refused before any traversal`() = runTest {
        setUp()
        try {
            val result = GrepTool(guestPaths()).execute(args("pattern" to ""), context())

            assertEquals("pattern must not be empty", (result as ToolResult.Error).message)
        } finally {
            tearDown()
        }
    }

    @Test
    fun `escaping the working directory is refused`() = runTest {
        setUp()
        try {
            val result = GrepTool(guestPaths())
                .execute(args("pattern" to "x", "path" to "../../etc"), context())

            assertTrue(result is ToolResult.Error)
        } finally {
            tearDown()
        }
    }

    // ── move_file ─────────────────────────────────────────────────────────

    @Test
    fun `an approved move renames the file`() = runTest {
        setUp()
        try {
            val source = write("old.txt", "content")

            val result = MoveFileTool(guestPaths())
                .execute(args("from" to "old.txt", "to" to "new.txt"), context())

            assertTrue(result is ToolResult.Success)
            assertTrue("the file should have moved", !source.exists())
            assertTrue(File(root, "new.txt").readText() == "content")
        } finally {
            tearDown()
        }
    }

    @Test
    fun `a rejected move leaves the file alone`() = runTest {
        setUp()
        try {
            val source = write("old.txt", "content")

            val result = MoveFileTool(guestPaths()).execute(
                args("from" to "old.txt", "to" to "new.txt"),
                context { ApprovalDecision.Reject("not now") },
            )

            assertTrue("should be reported as cancelled: $result", result is ToolResult.Cancelled)
            assertTrue("the file must not have moved", source.exists())
            assertTrue(!File(root, "new.txt").exists())
        } finally {
            tearDown()
        }
    }

    @Test
    fun `an existing destination needs an explicit overwrite`() = runTest {
        setUp()
        try {
            write("old.txt", "new content")
            write("new.txt", "existing")

            val refused = MoveFileTool(guestPaths())
                .execute(args("from" to "old.txt", "to" to "new.txt"), context())

            assertTrue(refused is ToolResult.Error)
            assertTrue(
                "should say how to proceed",
                (refused as ToolResult.Error).message.contains("overwrite=true"),
            )
            assertTrue("the destination must be untouched", File(root, "new.txt").readText() == "existing")

            val forced = MoveFileTool(guestPaths()).execute(
                args("from" to "old.txt", "to" to "new.txt", "overwrite" to "true"),
                context(),
            )
            assertTrue(forced is ToolResult.Success)
            assertTrue(File(root, "new.txt").readText() == "new content")
        } finally {
            tearDown()
        }
    }

    @Test
    fun `moving onto itself is a no-op`() = runTest {
        setUp()
        try {
            write("same.txt", "content")

            val result = MoveFileTool(guestPaths())
                .execute(args("from" to "same.txt", "to" to "same.txt"), context())

            assertTrue((result as ToolResult.Success).output.contains("already"))
        } finally {
            tearDown()
        }
    }

    @Test
    fun `a move that escapes the working directory is refused`() = runTest {
        setUp()
        try {
            write("a.txt", "x")

            val result = MoveFileTool(guestPaths())
                .execute(args("from" to "a.txt", "to" to "../escaped.txt"), context())

            assertTrue(result is ToolResult.Error)
            assertTrue(!File(root.parentFile, "escaped.txt").exists())
        } finally {
            tearDown()
        }
    }

    @Test
    fun `a missing source is refused`() = runTest {
        setUp()
        try {
            val result = MoveFileTool(guestPaths())
                .execute(args("from" to "absent.txt", "to" to "new.txt"), context())

            assertTrue(result is ToolResult.Error)
        } finally {
            tearDown()
        }
    }
}

/**
 * Build a tool argument object.
 *
 * The builder is spelled out explicitly because `buildJsonObject` has overloads
 * that leave `put` resolving to the JsonElement-typed one, and a String argument
 * then fails to match.
 */
private fun args(vararg pairs: Pair<String, String>): JsonObject {
    val builder = JsonObjectBuilder()
    pairs.forEach { (key, value) -> builder.put(key, value) }
    return builder.build()
}
