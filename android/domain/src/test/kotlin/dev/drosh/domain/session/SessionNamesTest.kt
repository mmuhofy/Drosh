package dev.drosh.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionNamesTest {

    @Test
    fun `the guest runtime is never a session name`() {
        // Every spelling the launcher has shipped under. The real asset is
        // `libchrootng.so`; `libchroot-ng.so` is what an older probe persisted.
        assertTrue(isRuntimeProcessName("libchrootng.so"))
        assertTrue(isRuntimeProcessName("libchroot-ng.so"))
        assertTrue(isRuntimeProcessName("chroot-ng"))
        assertTrue(isRuntimeProcessName("/data/data/lib/libchrootng.so"))
        assertTrue(isRuntimeProcessName("proot"))
    }

    @Test
    fun `the wrapper shell is not a program`() {
        assertTrue(isRuntimeProcessName("sh"))
        assertFalse(isRuntimeProcessName("zsh"))
        assertFalse(isRuntimeProcessName("bash"))
    }

    @Test
    fun `a real program is left alone`() {
        assertFalse(isRuntimeProcessName("python"))
        assertFalse(isRuntimeProcessName("vim"))
        assertFalse(isGeneratedSessionName("my project"))
    }

    @Test
    fun `generated names are not user names`() {
        assertTrue(isGeneratedSessionName(""))
        assertTrue(isGeneratedSessionName(DEFAULT_SESSION_NAME))
        assertTrue(isGeneratedSessionName("libchroot-ng.so"))
        assertFalse(isGeneratedSessionName("work"))
    }

    @Test
    fun `an unusable name is displayed as the default`() {
        assertEquals(DEFAULT_SESSION_NAME, displayableSessionName("libchrootng.so"))
        assertEquals(DEFAULT_SESSION_NAME, displayableSessionName(""))
        assertEquals("work", displayableSessionName("work"))
    }
}
