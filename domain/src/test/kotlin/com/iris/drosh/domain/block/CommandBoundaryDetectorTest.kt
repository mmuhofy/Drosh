package com.iris.irisshell.domain.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandBoundaryDetectorTest {

    private val detector = CommandBoundaryDetector()

    @Test
    fun `full user@host prompt is detected`() {
        val lines = listOf(
            "total 48",
            "drwxr-xr-x 5 root root 3456 Jul 28 12:09 .",
            "muhofy@drosh:~/Drosh$ ",
        )
        val result = detector.detectPromptReady(lines)
        assertEquals(CommandBoundary.PromptReady::class, result::class)
        assertEquals(2, (result as CommandBoundary.PromptReady).atLine)
    }

    @Test
    fun `root prompt with hash terminator is detected`() {
        val lines = listOf("root@drosh:/data# ")
        val result = detector.detectPromptReady(lines)
        assertTrue(result is CommandBoundary.PromptReady)
    }

    @Test
    fun `spaceship-style prompt with arrow is detected`() {
        val lines = listOf(" ~/Drosh ❯ ")
        val result = detector.detectPromptReady(lines)
        assertTrue(result is CommandBoundary.PromptReady)
    }

    @Test
    fun `oh-my-zsh arrow prompt with status is detected`() {
        val lines = listOf("➜  ~/Drosh git:(main) ✗")
        val result = detector.detectPromptReady(lines)
        assertTrue(result is CommandBoundary.PromptReady)
    }

    @Test
    fun `two-line prompt is detected`() {
        val lines = listOf(
            "total 48",
            "drwxr-xr-x 5 root root 3456 Jul 28 12:09 .",
            "muhofy@drosh:~/Drosh",
            "$ ",
        )
        val result = detector.detectPromptReady(lines)
        assertTrue(result is CommandBoundary.PromptReady)
        assertEquals(3, (result as CommandBoundary.PromptReady).atLine)
    }

    @Test
    fun `bare dollar marker is detected`() {
        val lines = listOf(
            "Permission denied",
            "$ ",
        )
        assertTrue(detector.detectPromptReady(lines) is CommandBoundary.PromptReady)
    }

    @Test
    fun `regular output line is not detected as prompt`() {
        val lines = listOf(
            "Hello world",
            "Permission denied",
            "/usr/local/bin",
        )
        assertEquals(CommandBoundary.None, detector.detectPromptReady(lines))
    }

    @Test
    fun `line with dollar in middle is not prompt`() {
        val lines = listOf("Total: \$5")
        assertEquals(CommandBoundary.None, detector.detectPromptReady(lines))
    }

    @Test
    fun `empty input returns none`() {
        assertEquals(CommandBoundary.None, detector.detectPromptReady(emptyList()))
    }

    @Test
    fun `most recent prompt is returned when multiple exist`() {
        val lines = listOf(
            "first command output",
            "muhofy@drosh:~$ ",
            "second command output",
            "muhofy@drosh:~/Drosh$ ",
        )
        val result = detector.detectPromptReady(lines)
        assertEquals(3, (result as CommandBoundary.PromptReady).atLine)
    }
}
