package dev.drosh.domain.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Naming a conversation.
 *
 * The rule that matters is the one in [ChatTitle.MIN_WORDS]: a first message of
 * "hi" must keep the behaviour it had, because a model asked to title that will
 * answer with either the word itself or a refusal, and a chat list of either is
 * worse than a chat list of honest first words.
 */
class ChatTitleTest {

    @Test
    fun `a message with enough in it is worth naming`() {
        assertTrue(ChatTitle.isWorthNaming("why does the build fail on this branch"))
        assertTrue(ChatTitle.isWorthNaming("fix the failing gradle sync please"))
    }

    @Test
    fun `a message too short to describe is left alone`() {
        assertFalse(ChatTitle.isWorthNaming("hi"))
        assertFalse(ChatTitle.isWorthNaming("thanks!"))
        assertFalse(ChatTitle.isWorthNaming("   "))
        assertFalse(ChatTitle.isWorthNaming(""))
    }

    @Test
    fun `a single long word is still too short`() {
        // Forty characters of `ok` is still just `ok`.
        assertFalse(ChatTitle.isWorthNaming("ok ".repeat(40)))
    }

    @Test
    fun `a plain answer becomes a title`() {
        assertEquals("Fix the gradle sync", ChatTitle.sanitize("Fix the gradle sync"))
    }

    @Test
    fun `the shapes models actually answer with are unwrapped`() {
        assertEquals("Fix the gradle sync", ChatTitle.sanitize("\"Fix the gradle sync\""))
        assertEquals("Fix the gradle sync", ChatTitle.sanitize("Title: Fix the gradle sync"))
        assertEquals("Fix the gradle sync", ChatTitle.sanitize("Fix the gradle sync."))
    }

    @Test
    fun `an explanation after the title is dropped with the rest of the line`() {
        assertEquals(
            "Fix the gradle sync",
            ChatTitle.sanitize("Fix the gradle sync\n\nThis names the request in five words."),
        )
    }

    @Test
    fun `too long is not a title`() {
        assertNull(ChatTitle.sanitize("a".repeat(ChatTitle.MAX_CHARS + 1)))
    }

    @Test
    fun `too many words is not a title`() {
        assertNull(ChatTitle.sanitize("one two three four five six seven"))
    }

    @Test
    fun `a refusal is not a title`() {
        assertNull(ChatTitle.sanitize("I cannot help with that request"))
        assertNull(ChatTitle.sanitize("No title available"))
        assertNull(ChatTitle.sanitize("As an AI I have no opinion"))
    }

    @Test
    fun `nothing is not a title`() {
        assertNull(ChatTitle.sanitize(""))
        assertNull(ChatTitle.sanitize("   \n  \n"))
        assertNull(ChatTitle.sanitize("."))
    }

    @Test
    fun `the naming prompt is clipped, never summarised`() {
        val pasted = "x".repeat(ChatTitle.MAX_SOURCE_CHARS * 2)

        assertEquals(ChatTitle.MAX_SOURCE_CHARS, ChatTitle.sourceFor(pasted).length)
    }

    @Test
    fun `the fallback name is the first words of the message`() {
        val derived = ChatTitle.derivedFrom("  why does gradle sync fail  ")

        assertEquals("why does gradle sync fail", derived)
    }

    @Test
    fun `the system prompt states the constraints instead of demonstrating them`() {
        // A model told "five words" writes five words far more reliably than one
        // shown five examples, so the numbers have to be in the instruction.
        assertTrue(ChatTitle.SYSTEM_PROMPT.contains("5 words"))
        assertTrue(ChatTitle.SYSTEM_PROMPT.contains("60 characters"))
    }
}