package dev.drosh.terminal.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shell integration's startup command.
 *
 * The command is appended to the rc script rather than handed to the shell as
 * `-c`, because `-c` is a session that runs the command and exits. What is
 * tested here is the shape of that append: nothing for no command, and the
 * command intact — on its own line, at the file's own indent — for one.
 */
class DroshShellIntegrationTest {

    @Test
    fun `no command adds nothing to the rc script`() {
        assertEquals("", DroshShellIntegration.startupSnippet(""))
        assertEquals("", DroshShellIntegration.startupSnippet("   "))
    }

    @Test
    fun `a command is appended on its own line`() {
        val snippet = DroshShellIntegration.startupSnippet("cd ~/projects && ls")

        assertTrue(snippet.startsWith("\n"))
        assertTrue(snippet.contains("\ncd ~/projects && ls\n"))
    }

    @Test
    fun `a command is not trimmed away by the script writer`() {
        // writeScript runs `trimIndent()` over the whole body, and the command
        // is the one line in it that has no indentation: a snippet that started
        // with spaces would make that line the origin and shift the shell
        // integration out from under itself.
        val snippet = DroshShellIntegration.startupSnippet("export FOO=1")
        val line = snippet.lines().first { it.contains("export FOO=1") }
        assertEquals("export FOO=1", line)
    }
}
