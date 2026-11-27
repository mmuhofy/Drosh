package dev.drosh.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionNamesTest {

    private val fallback = "session"

    @Test
    fun `the program title wins`() {
        val name = sessionDisplayName(
            sessionId = "s",
            localNames = mapOf("s" to "my rename"),
            titles = mapOf("s" to "~/project"),
            fallbackName = fallback,
            storedName = "stored",
        )
        assertEquals("~/project", name)
    }

    @Test
    fun `a rename beats the stored name`() {
        val name = sessionDisplayName(
            sessionId = "s",
            localNames = mapOf("s" to "new"),
            titles = emptyMap(),
            fallbackName = fallback,
            storedName = "old",
        )
        assertEquals("new", name)
    }

    @Test
    fun `a stored default never becomes a title`() {
        val name = sessionDisplayName(
            sessionId = "s",
            localNames = emptyMap(),
            titles = emptyMap(),
            fallbackName = fallback,
            storedName = "Default",
        )
        assertEquals(fallback, name)
    }

    @Test
    fun `a stored runtime binary is not a name`() {
        val name = sessionDisplayName(
            sessionId = "s",
            localNames = emptyMap(),
            titles = emptyMap(),
            fallbackName = fallback,
            storedName = "libchroot-ng.so",
        )
        assertEquals(fallback, name)
    }

    @Test
    fun `the subtitle repeats nothing`() {
        assertNull(sessionSubtitle("s", emptyMap(), "Default", "shell"))
        assertNull(sessionSubtitle("s", emptyMap(), "same", "same"))
        assertEquals(
            "work",
            sessionSubtitle("s", emptyMap(), "work", "python"),
        )
    }
}
