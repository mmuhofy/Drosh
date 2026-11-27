package dev.drosh.ui.block

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPredictionTest {

    private val history = listOf(
        "git status",
        "git stash",
        "ls -la",
        "git status",
        "gradlew assembleDebug",
    )

    @Test
    fun `a prefix offers the most recent matches first`() {
        assertEquals(
            listOf("git status", "git stash"),
            predictCommands("git st", history),
        )
    }

    @Test
    fun `matching ignores case`() {
        assertEquals(listOf("Git Status"), predictCommands("git st", listOf("Git Status")))
    }

    @Test
    fun `the exact line is never offered back`() {
        val suggestions = predictCommands("ls -la", listOf("ls -la"))
        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun `a single character is not a prediction`() {
        assertTrue(predictCommands("g", history).isEmpty())
        assertTrue(predictCommands(" ", history).isEmpty())
    }

    @Test
    fun `leading spaces are ignored`() {
        assertEquals(listOf("git status"), predictCommands("   git s", listOf("git status")))
    }

    @Test
    fun `duplicates are collapsed`() {
        val suggestions = predictCommands("git", history, limit = 5)
        assertEquals(listOf("git status", "git stash"), suggestions)
    }

    @Test
    fun `the limit is respected`() {
        val many = (1..20).map { "echo $it" }
        assertEquals(2, predictCommands("echo", many, limit = 2).size)
    }
}
