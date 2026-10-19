package dev.drosh.domain.agent

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a session may reach, and from where.
 *
 * The rule that matters is [ToolPermissions.schema]: the permission surface is
 * derived from the tools that exist rather than named here, so a tool added
 * next month is switchable without an edit to the domain. The tests below hold
 * that to a fake registry so the property is stated once and stays stated.
 */
class AgentSessionScopeTest {

    /** A tool with the three fields the schema reads, and nothing else. */
    private class FakeTool(
        override val name: String,
        override val description: String = "$name description",
        override val requiresApproval: Boolean = false,
    ) : Tool {
        override val parameters: JsonObject = JsonObject(emptyMap())
        override fun summarize(input: JsonObject): String = name
        override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult =
            ToolResult.Error("not run in a test")
    }

    private val tools = listOf(
        FakeTool("shell"),
        FakeTool("write_file", requiresApproval = true),
        FakeTool("read_file"),
    )

    @Test
    fun `a fresh session may call every tool it has`() {
        val scope = AgentSessionScope.DEFAULT
        assertTrue(scope.isGranted("shell"))
        assertTrue(scope.isGranted("write_file"))
    }

    @Test
    fun `a fresh session starts at the guest home`() {
        assertEquals("/home", AgentSessionScope.DEFAULT.workingDirectory)
    }

    @Test
    fun `the schema is derived from the tools, not named here`() {
        val scope = AgentSessionScope("/home/user/app")
        val keys = ToolPermissions.schema(tools, scope).map { it.key }
        assertEquals(listOf("read_file", "shell", "write_file"), keys)
    }

    @Test
    fun `a tool nobody has heard of is still switchable`() {
        // The whole point: the next tool added to the registry needs no edit to
        // this file, and it arrives granted, like everything else.
        val scope = AgentSessionScope("/home")
        val later = tools + FakeTool("toggle_wifi")
        val schema = ToolPermissions.schema(later, scope)
        val wifi = schema.single { it.key == "toggle_wifi" }
        assertTrue(wifi.granted)
        assertFalse(wifi.requiresApproval)
    }

    @Test
    fun `the schema is name-ordered so a sheet does not reshuffle`() {
        val scope = AgentSessionScope("/home")
        val keys = ToolPermissions.schema(tools.reversed(), scope).map { it.key }
        assertEquals(listOf("read_file", "shell", "write_file"), keys)
    }

    @Test
    fun `revoking a tool is visible through the schema`() {
        val scope = AgentSessionScope("/home").revoke("shell")
        val shell = ToolPermissions.schema(tools, scope).single { it.key == "shell" }
        assertFalse(shell.granted)
        assertTrue(scope.isGranted("read_file"))
    }

    @Test
    fun `granting it back puts the switch where it started`() {
        val scope = AgentSessionScope("/home").revoke("shell").grant("shell")
        assertTrue(scope.isGranted("shell"))
    }

    @Test
    fun `toggling twice is the scope it started as`() {
        val start = AgentSessionScope("/home/app")
        val toggled = start.toggle("write_file").toggle("write_file")
        assertEquals(start, toggled)
    }

    @Test
    fun `revoking one tool leaves the others alone`() {
        val scope = AgentSessionScope("/home", revoked = setOf("read_file", "grep"))
        assertFalse(scope.isGranted("read_file"))
        assertFalse(scope.isGranted("grep"))
        assertTrue(scope.isGranted("shell"))
    }

    @Test
    fun `a schema entry carries the tool's own approval requirement`() {
        val scope = AgentSessionScope("/home")
        val write = ToolPermissions.schema(tools, scope).single { it.key == "write_file" }
        assertTrue(write.requiresApproval)
        val read = ToolPermissions.schema(tools, scope).single { it.key == "read_file" }
        assertFalse(read.requiresApproval)
    }

    @Test
    fun `moving the session moves only the directory`() {
        val scope = AgentSessionScope("/home", revoked = setOf("shell"))
        val moved = scope.moveTo("/home/user/project")
        assertEquals("/home/user/project", moved.workingDirectory)
        assertEquals(setOf("shell"), moved.revoked)
    }

    @Test
    fun `a revocation for a tool that no longer exists is dropped`() {
        // A tool taken out of the registry must not leave a permanent hole in the
        // scope, or a later tool that happens to take the same name inherits it.
        val scope = AgentSessionScope("/home", revoked = setOf("shell", "gone_forever"))
        val pruned = ToolPermissions.prune(scope, tools)
        assertEquals(setOf("shell"), pruned.revoked)
    }

    @Test
    fun `pruning a scope with nothing revoked returns it unchanged`() {
        val scope = AgentSessionScope("/home/app")
        assertEquals(scope, ToolPermissions.prune(scope, tools))
    }

    @Test
    fun `an empty registry gives an empty schema, not a crash`() {
        val schema = ToolPermissions.schema(emptyList(), AgentSessionScope("/home"))
        assertTrue(schema.isEmpty())
    }
}
