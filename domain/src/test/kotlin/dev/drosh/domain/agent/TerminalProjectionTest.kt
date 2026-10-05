package dev.drosh.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-only terminal view.
 *
 * What matters is that it never *invents* anything. A terminal that shows a
 * command the agent did not run, or hides a failure behind a plausible-looking
 * success, is worse than no terminal at all — the whole reason to look is to
 * check that what it claims is what it did.
 */
class TerminalProjectionTest {

    private fun shell(
        command: String,
        output: String? = "exit 0\nfile1\nfile2",
        state: ToolCallState = ToolCallState.Succeeded,
    ) = ChatMessage.ToolCall(
        id = "m${command.hashCode()}",
        callId = "call_${command.hashCode()}",
        name = "shell",
        summary = command,
        state = state,
        finalOutput = output,
    )

    @Test
    fun `a command and its output are projected in order`() {
        val lines = TerminalProjection.project(listOf(shell("ls -la")))

        assertEquals(
            listOf(
                TerminalLine.Command("ls -la"),
                TerminalLine.Output("file1"),
                TerminalLine.Output("file2"),
                TerminalLine.Status(code = 0, failed = false),
            ),
            lines,
        )
    }

    @Test
    fun `the exit prefix is not also shown as output`() {
        // Shown twice — once as prose, once as a status — the reader would see the
        // same fact in two different vocabularies.
        val lines = TerminalProjection.project(listOf(shell("ls")))

        assertEquals(0, lines.count { it is TerminalLine.Output && it.text.startsWith("exit") })
        assertEquals(1, lines.count { it is TerminalLine.Status })
    }

    @Test
    fun `several commands are separated`() {
        val lines = TerminalProjection.project(
            listOf(
                shell("first", "exit 0\na"),
                shell("second", "exit 0\nb"),
            ),
        )

        assertEquals(2, lines.count { it is TerminalLine.Command })
        assertEquals(1, lines.count { it is TerminalLine.Divider })
    }

    @Test
    fun `a single command has no leading separator`() {
        // A divider above the first command is a blank line the user has to read
        // past for no reason.
        val lines = TerminalProjection.project(listOf(shell("only")))

        assertTrue(lines.first() is TerminalLine.Command)
    }

    @Test
    fun `a failure is marked as failed`() {
        val lines = TerminalProjection.project(
            listOf(shell("boom", "ERROR: exit code 127", ToolCallState.Failed)),
        )

        val status = lines.filterIsInstance<TerminalLine.Status>().single()
        assertTrue(status.failed)
    }

    @Test
    fun `a rejection reads as failed, not as output`() {
        val lines = TerminalProjection.project(
            listOf(shell("rm -rf /", "CANCELLED: user declined", ToolCallState.Cancelled)),
        )

        assertTrue(lines.filterIsInstance<TerminalLine.Status>().single().failed)
        assertEquals(0, lines.count { it is TerminalLine.Output && it.text.contains("CANCELLED") })
    }

    @Test
    fun `only shell calls are projected`() {
        // A write_file diff is not terminal output, and rendering it here would
        // put prose where a user expects command output.
        val lines = TerminalProjection.project(
            listOf(
                shell("ls"),
                ChatMessage.ToolCall(
                    id = "m2", callId = "c2", name = "write_file", summary = "App.ktx",
                    state = ToolCallState.Succeeded, finalOutput = "wrote 42 bytes",
                ),
            ),
        )

        assertEquals(1, lines.count { it is TerminalLine.Command })
        assertTrue(lines.none { it is TerminalLine.Output && it.text.contains("App.ktx") })
    }

    @Test
    fun `a still running command is left out`() {
        // Its output belongs to the tool row, which shows it live. Duplicating it
        // here would show a command with no result as if it had finished.
        val running = ChatMessage.ToolCall(
            id = "m1", callId = "c1", name = "shell", summary = "npm run build",
            state = ToolCallState.Running, output = listOf("vite v5.4.8"),
        )

        assertTrue(TerminalProjection.project(listOf(running)).isEmpty())
    }

    @Test
    fun `an approved tool waiting on a user is left out`() {
        val waiting = ChatMessage.ToolCall(
            id = "m1", callId = "c1", name = "shell", summary = "x",
            state = ToolCallState.AwaitingApproval,
        )

        assertTrue(TerminalProjection.project(listOf(waiting)).isEmpty())
    }

    @Test
    fun `live output stands in when there is no final result`() {
        // A killed run leaves rows with streamed output and no finalOutput. Those
        // are exactly the ones worth reading.
        val killed = ChatMessage.ToolCall(
            id = "m1", callId = "c1", name = "shell", summary = "npm run build",
            state = ToolCallState.Failed, output = listOf("vite v5.4.8", "building..."),
        )

        val lines = TerminalProjection.project(listOf(killed))
        assertEquals(
            listOf(
                TerminalLine.Command("npm run build"),
                TerminalLine.Output("vite v5.4.8"),
                TerminalLine.Output("building..."),
                TerminalLine.Status(code = -1, failed = true),
            ),
            lines,
        )
    }

    @Test
    fun `the truncation marker survives rather than looking like complete output`() {
        val clipped = ChatMessage.ToolCall(
            id = "m1", callId = "c1", name = "shell", summary = "cat big.log",
            state = ToolCallState.Succeeded,
            finalOutput = "exit 0\nline1\n[380 earlier lines omitted from the live view]",
        )

        val lines = TerminalProjection.project(listOf(clipped))
        assertTrue(
            "the marker must stay visible",
            lines.any { it is TerminalLine.Output && it.text.contains("omitted") },
        )
    }

    @Test
    fun `a command with no recorded summary is still shown`() {
        val anonymous = ChatMessage.ToolCall(
            id = "m1", callId = "c1", name = "shell", summary = "",
            state = ToolCallState.Succeeded, finalOutput = "exit 0",
        )

        assertTrue(TerminalProjection.project(listOf(anonymous)).any {
            it is TerminalLine.Command && it.text == "(no command recorded)"
        })
    }

    @Test
    fun `an empty output is not a blank row`() {
        val lines = TerminalProjection.project(listOf(shell("true", "exit 0, no output")))

        assertEquals(0, lines.count { it is TerminalLine.Output && it.text.isBlank() })
    }

    @Test
    fun `isEmpty reflects whether there is anything to show`() {
        assertTrue(TerminalProjection.isEmpty(emptyList()))
        assertTrue(TerminalProjection.isEmpty(listOf(ChatMessage.User("u1", "hi"))))
        assertEquals(false, TerminalProjection.isEmpty(listOf(shell("ls"))))
    }
}
