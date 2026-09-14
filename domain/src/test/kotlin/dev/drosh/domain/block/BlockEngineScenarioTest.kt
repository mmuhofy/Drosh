package dev.drosh.domain.block

import dev.drosh.domain.block.CommandBoundary
import dev.drosh.domain.block.CommandBoundaryDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end scenario tests for the block engine pipeline.
 *
 * These simulate the actual terminal transcript that BlockEngineWire
 * would see — prompt, echoed command, output, new prompt — and assert
 * that the diff + prompt detection produce the right slices.
 */
class BlockEngineScenarioTest {

    private val detector = CommandBoundaryDetector()

    @Test
    fun `typical ls transcript`() {
        // Transcript as it would appear after the user runs `ls`:
        //   previous prompt (zsh), echo of the typed command, ls output, new prompt.
        val previous = listOf(
            "",
            "muhofy@drosh:~/Drosh$",  // previous prompt (no trailing space)
        )
        val current = listOf(
            "",
            "muhofy@drosh:~/Drosh$",  // unchanged prompt
            "muhofy@drosh:~/Drosh$ ls",  // echoed command line
            "muhofy-projects",                // ls output
            "root@drosh:~$",             // new prompt (different shell/identity)
        )

        // Anchor on the previous prompt (which still exists in current at index 1).
        // Everything after index 1 is new.
        val newLines = computeNewLines(previous, current)
        // The new slice, after blank-line filtering, should be:
        //   [echoed command, ls output, new prompt]
        assertEquals(
            listOf(
                "muhofy@drosh:~/Drosh\$ ls",
                "muhofy-projects",
                "root@drosh:~\$",
            ),
            newLines,
        )

        // Prompt detection: last line `root@drosh:~$` is a prompt.
        val boundary = detector.detectPromptReady(current)
        assertTrue("expected PromptReady, got $boundary", boundary is CommandBoundary.PromptReady)
    }

    @Test
    fun `whoami transcript with prompt as last line`() {
        val current = listOf(
            "iris\$ whoami",
            "root",
            "root@drosh:~\$",
        )
        val boundary = detector.detectPromptReady(current)
        assertTrue(boundary is CommandBoundary.PromptReady)
    }

    @Test
    fun `transcript with only one line should not be prompt alone`() {
        val current = listOf("loading...")
        val boundary = detector.detectPromptReady(current)
        assertEquals(CommandBoundary.None, boundary)
    }

    @Test
    fun `two-line prompt - oh-my-zsh style`() {
        // Two-line prompt: `muhofy@drosh:~/path` then `❯ ` on the next line.
        val current = listOf(
            "git status output",
            "muhofy@drosh:~/Drosh",
            "❯ ",
        )
        val boundary = detector.detectPromptReady(current)
        assertTrue(boundary is CommandBoundary.PromptReady)
    }

    /**
     * Mirror of BlockEngineWire.computeNewLines — reproduced here so the
     * scenarios can be tested without instantiating the wire (which lives
     * in the :terminal module and depends on Android APIs).
     */
    private fun computeNewLines(previous: List<String>, current: List<String>): List<String> {
        val prevNonBlank = previous.withIndex().filter { it.value.isNotBlank() }
        if (prevNonBlank.isEmpty()) return emptyList()
        val currNonBlank = current.withIndex().filter { it.value.isNotBlank() }
        if (currNonBlank.isEmpty()) return emptyList()
        for ((_, line) in prevNonBlank.reversed()) {
            val matchIdx = currNonBlank.indexOfLast { it.value == line }
            if (matchIdx >= 0) {
                val currOriginalIdx = currNonBlank[matchIdx].index
                return current.drop(currOriginalIdx + 1).filter { it.isNotBlank() }
            }
        }
        return emptyList()
    }
}
