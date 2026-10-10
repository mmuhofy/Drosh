package dev.drosh.core.toml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Round-trips, defaults and refusals for [TomlDocument].
 *
 * The contract that matters: anything [TomlDocument.render] writes parses back
 * to the same document, because the settings file is only ever machine-written
 * — the human-edit path has to fail loudly instead (see the malformed tests),
 * since a silently misread setting is worse than a refused one.
 */
class TomlDocumentTest {

    @Test
    fun `round-trips every supported type`() {
        val document = TomlDocument().apply {
            putString("appearance", "theme_mode", "system")
            putInt("terminal", "scrollback_rows", 3000)
            putFloat("terminal", "default_font_size_sp", 14.5f)
            putBoolean("terminal", "keep_awake", true)
            putStringList("shell", "omz_plugins", listOf("git", "zsh-autosuggestions"))
        }

        val parsed = TomlDocument.parse(document.render())

        assertEquals("system", parsed.getString("appearance", "theme_mode", "dark"))
        assertEquals(3000, parsed.getInt("terminal", "scrollback_rows", 0))
        assertEquals(14.5f, parsed.getFloat("terminal", "default_font_size_sp", 0f))
        assertTrue(parsed.getBoolean("terminal", "keep_awake", false))
        assertEquals(
            listOf("git", "zsh-autosuggestions"),
            parsed.getStringList("shell", "omz_plugins", emptyList()),
        )
    }

    @Test
    fun `strings survive quotes backslashes and newlines`() {
        val banner = "  welcome \"user\" \\ path\n  second line\ttabbed"
        val document = TomlDocument().apply { putString("shell", "motd_text", banner) }

        assertEquals(banner, TomlDocument.parse(document.render()).getString("shell", "motd_text", ""))
    }

    @Test
    fun `comments and blank lines are skipped`() {
        val parsed = TomlDocument.parse(
            """
            # a comment

            [appearance]
            theme_mode = "dark" # trailing comment

            [shell]
            motd_text = "line with # inside"
            """.trimIndent(),
        )
        assertEquals("dark", parsed.getString("appearance", "theme_mode", ""))
        // The hash inside the quotes is data, not a comment.
        assertEquals("line with # inside", parsed.getString("shell", "motd_text", ""))
    }

    @Test
    fun `missing keys fall back to the default`() {
        val parsed = TomlDocument.parse("[terminal]\n")
        assertEquals("x", parsed.getString("terminal", "absent", "x"))
        assertEquals(7, parsed.getInt("terminal", "absent", 7))
        assertEquals(7.5f, parsed.getFloat("terminal", "absent", 7.5f))
        // Both directions: the default is what comes back, whatever it is.
        assertTrue(parsed.getBoolean("terminal", "absent", true))
        assertFalse(parsed.getBoolean("terminal", "absent", false))
        assertEquals(listOf("a"), parsed.getStringList("terminal", "absent", listOf("a")))
        assertFalse(parsed.contains("terminal", "absent"))
    }

    @Test
    fun `empty array renders as empty`() {
        val document = TomlDocument().apply { putStringList("rootfs", "extra_packages", emptyList()) }
        assertEquals(emptyList<String>(), TomlDocument.parse(document.render()).getStringList("rootfs", "extra_packages", listOf("x")))
    }

    @Test
    fun `a key outside any section is refused`() {
        try {
            TomlDocument.parse("theme_mode = \"dark\"\n")
            fail("expected a TomlParseException")
        } catch (e: TomlParseException) {
            assertTrue(e.message!!.contains("outside any section"))
        }
    }

    @Test
    fun `an unquoted string is refused`() {
        try {
            TomlDocument.parse("[appearance]\ntheme_mode = dark\n")
            fail("expected a TomlParseException")
        } catch (e: TomlParseException) {
            assertTrue(e.message!!.contains("unrecognised value"))
        }
    }

    @Test
    fun `a wrong-typed value falls back rather than throwing`() {
        val parsed = TomlDocument.parse("[terminal]\nscrollback_rows = \"lots\"\n")
        assertEquals(3000, parsed.getInt("terminal", "scrollback_rows", 3000))
        assertTrue(parsed.contains("terminal", "scrollback_rows"))
    }

    @Test
    fun `section and key order are preserved through a round-trip`() {
        val document = TomlDocument().apply {
            putString("appearance", "theme_mode", "dark")
            putString("terminal", "mode", "blocks")
            putString("appearance", "font_pack", "geist")
        }

        val parsed = TomlDocument.parse(document.render())
        assertEquals(listOf("appearance", "terminal"), parsed.sectionNames())
        assertEquals(listOf("theme_mode", "font_pack"), parsed.keysOf("appearance"))
    }

    @Test
    fun `an unknown escape is refused`() {
        try {
            TomlDocument.parse("[shell]\nmotd_text = \"a\\qb\"\n")
            fail("expected a TomlParseException")
        } catch (e: TomlParseException) {
            assertTrue(e.message!!.contains("unknown escape"))
        }
    }

    @Test
    fun `an unterminated array is refused`() {
        try {
            TomlDocument.parse("[shell]\nomz_plugins = [\"a\"\n")
            fail("expected a TomlParseException")
        } catch (e: TomlParseException) {
            assertTrue(e.message!!.contains("unterminated array"))
        }
    }
}
