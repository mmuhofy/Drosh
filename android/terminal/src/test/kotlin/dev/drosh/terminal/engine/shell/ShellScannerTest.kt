package dev.drosh.terminal.engine.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `OSC 133` scanner, on byte chunks we control.
 *
 * Every case here is something a real shell does: a mark split across two
 * reads, a mark with a status, a working directory with a host in it, a chunk
 * with no marks at all. The scanner is the only thing in the terminal that
 * reads the shell's own output, so its mistakes show up as a prompt that never
 * says "ready".
 */
class ShellScannerTest {

    private fun scanner() = dev.drosh.terminal.engine.shell.Scanner()

    @Test
    fun `a prompt mark sets the phase`() {
        val scanner = scanner()
        val marks = scanner.scan("\u001b]133;A\u0007".toByteArray())
        assertEquals(1, marks.size)
        assertEquals(Phase.PROMPT, scanner.phase)
    }

    @Test
    fun `a command's whole cycle is tracked`() {
        val scanner = scanner()
        scanner.scan("\u001b]133;A\u0007".toByteArray())
        assertEquals(Phase.PROMPT, scanner.phase)

        scanner.scan("\u001b]133;B\u0007".toByteArray())
        assertEquals(Phase.TYPING, scanner.phase)

        scanner.scan("\u001b]133;C\u0007".toByteArray())
        assertEquals(Phase.RUNNING, scanner.phase)

        val marks = scanner.scan("\u001b]133;D;0\u0007".toByteArray())
        assertEquals(Phase.FINISHED, scanner.phase)
        assertEquals(1, marks.size)
    }

    @Test
    fun `a mark split across two reads is still found`() {
        val scanner = scanner()
        // The first half ends mid-mark: no terminator.
        assertEquals(0, scanner.scan("\u001b]133;".toByteArray()).size)
        assertEquals(Phase.UNKNOWN, scanner.phase)

        // The second half completes it.
        val marks = scanner.scan("A\u0007".toByteArray())
        assertEquals(1, marks.size)
        assertEquals(Phase.PROMPT, scanner.phase)
    }

    @Test
    fun `a working directory loses its file prefix`() {
        val scanner = scanner()
        scanner.scan("\u001b]7;file://localhost/home/muhofy\u0007".toByteArray())
        assertEquals("/home/muhofy", scanner.currentDirectory())
    }

    @Test
    fun `an empty directory is null`() {
        val scanner = scanner()
        assertNull(scanner.currentDirectory())
    }

    @Test
    fun `a chunk with no marks reports nothing`() {
        val scanner = scanner()
        val marks = scanner.scan("hello world\r\n".toByteArray())
        assertEquals(0, marks.size)
        assertEquals(Phase.UNKNOWN, scanner.phase)
    }

    @Test
    fun `a mark terminated by escape-backslash is read`() {
        val scanner = scanner()
        // ST is ESC \, the other legal OSC terminator.
        val marks = scanner.scan("\u001b]133;A\u001b\\".toByteArray())
        assertEquals(1, marks.size)
        assertEquals(Phase.PROMPT, scanner.phase)
    }

    @Test
    fun `text around a mark is ignored`() {
        val scanner = scanner()
        val marks = scanner.scan("prompt$ \u001b]133;A\u0007 ls".toByteArray())
        assertEquals(1, marks.size)
        assertEquals(Phase.PROMPT, scanner.phase)
    }
}
