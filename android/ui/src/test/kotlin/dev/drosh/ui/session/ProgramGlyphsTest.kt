package dev.drosh.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The registry is what turns `/proc`'s short, decorated program name into a
 * card's mark. These are the shapes it actually receives.
 */
class ProgramGlyphsTest {

    @Test
    fun a_known_program_gets_its_mark() {
        assertEquals("python", ProgramGlyphs.slugFor("python3"))
        assertEquals("neovim", ProgramGlyphs.slugFor("nvim"))
        assertEquals("claude", ProgramGlyphs.slugFor("claude"))
        assertEquals("nodedotjs", ProgramGlyphs.slugFor("node"))
    }

    @Test
    fun a_path_and_a_login_dash_are_not_part_of_the_name() {
        assertEquals("python", ProgramGlyphs.slugFor("/usr/bin/python3"))
        assertEquals("gnubash", ProgramGlyphs.slugFor("-bash"))
    }

    @Test
    fun a_version_suffix_is_not_a_different_program() {
        assertEquals("python", ProgramGlyphs.slugFor("python3.12"))
        assertEquals("neovim", ProgramGlyphs.slugFor("nvim-0.9"))
    }

    @Test
    fun an_unknown_program_has_no_mark() {
        assertNull(ProgramGlyphs.slugFor("some-unknown-tool"))
        assertNull(ProgramGlyphs.slugFor(""))
        assertNull(ProgramGlyphs.slugFor(null))
    }

    @Test
    fun the_program_wins_and_the_title_is_the_fallback() {
        // The program is the kernel's answer; the title is prose.
        assertEquals(
            "neovim",
            ProgramGlyphs.resolve(program = "nvim", title = "notes — python project"),
        )
        assertEquals("python", ProgramGlyphs.resolve(program = null, title = "python"))
        assertEquals("github", ProgramGlyphs.resolve(program = null, title = "gh pr list"))
    }

    @Test
    fun matching_is_by_whole_word() {
        // `vi` inside `virtual` is not a request for an editor's mark.
        assertNull(ProgramGlyphs.resolve(program = null, title = "virtual machine"))
        assertEquals("vim", ProgramGlyphs.resolve(program = null, title = "vim ~/.zshrc"))
    }
}
