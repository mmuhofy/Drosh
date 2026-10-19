package dev.drosh.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a theme picker depends on, tested where they are written.
 *
 * These are the two facts onboarding stands on. The built-in list has to be
 * usable with no rootfs and no network — the whole reason the theme page can
 * offer a choice during setup — and [DroshTheme.choices] has to keep the
 * built-ins in front and in order while still letting an installed file of the
 * same id stand in, because a picker whose list changes shape depending on what
 * is on disk is a picker the user cannot learn.
 *
 * Nothing about the *writing* is here: that is I/O behind `ApplyThemeUseCase`
 * and belongs to a device test. What is here is what a palette has to be before
 * anything is willing to write it — which, given a theme file that is read by a
 * shell as well as by the app, is worth more than it looks.
 */
class DroshThemeCatalogTest {

    private val hexColor = Regex("^#[0-9A-Fa-f]{6}$")

    @Test
    fun `the built-in list is never empty, so setup always has something to offer`() {
        val choices = DroshTheme.choices(emptyList())

        assertTrue("a picker with nothing in it is not a picker", choices.isNotEmpty())
        assertSame("Drosh's own leads: it is the way back", DroshTheme.DEFAULT, choices.first())
    }

    @Test
    fun `every built-in colour is a hex the terminal can parse`() {
        // `TerminalColors.parse` answers 0 for anything it cannot read, and a
        // 0 in the palette means a black cell rather than an error — so a typo
        // in a bundled palette is invisible until someone looks at the
        // terminal. Twenty-two per theme, which is the whole palette.
        val offenders = DroshTheme.choices(emptyList()).flatMap { theme ->
            theme.palette.allColours()
                .filterNot { hexColor.matches(it) }
                .map { "${theme.id}: $it" }
        }

        assertTrue("unparseable colours: $offenders", offenders.isEmpty())
    }

    @Test
    fun `every built-in id is unique, since the id is what the file is filed under`() {
        val ids = DroshTheme.choices(emptyList()).map { it.id }

        assertEquals("duplicate ids would make two cards one theme", ids.size, ids.toSet().size)
        assertTrue("ids become file names, so they cannot be blank", ids.none { it.isBlank() })
    }

    @Test
    fun `a theme id resolves to itself among the choices`() {
        val choices = DroshTheme.choices(emptyList())

        DroshTheme.STARTERS.forEach { starter ->
            assertSame(starter, DroshTheme.find(starter.id, choices))
        }
    }

    @Test
    fun `an unknown id resolves to nothing rather than to the default`() {
        // The default would be a silent wrong answer: the user picked a theme,
        // the app applied a different one, and nothing anywhere said so.
        assertNull(DroshTheme.find("no-such-theme", DroshTheme.choices(emptyList())))
    }

    @Test
    fun `an installed theme of a built-in id replaces it in place`() {
        val installed = DroshTheme(
            id = "nord",
            name = "Nord (edited)",
            palette = DroshTheme.DEFAULT.palette,
        )

        val choices = DroshTheme.choices(listOf(installed))
        val index = choices.indexOfFirst { it.id == "nord" }

        assertEquals("the edited file keeps the starter's slot, not its own", 2, index)
        assertEquals("Nord (edited)", choices[index].name)
        assertEquals("and it is not also listed at the end", 1, choices.count { it.id == "nord" })
    }

    @Test
    fun `an installed theme that is not built in is listed after them`() {
        val mine = DroshTheme(id = "mine", name = "Mine", palette = ThemePalette.DEFAULT)

        val choices = DroshTheme.choices(listOf(mine))

        assertSame(mine, choices.last())
        assertEquals(DroshTheme.STARTERS.size + 2, choices.size)
    }

    /** Every colour the palette carries, in the order the theme file writes them. */
    private fun ThemePalette.allColours(): List<String> = listOf(
        background, foreground, cursor,
        selectionBackground, selectionForeground, accent,
        black, red, green, yellow, blue, magenta, cyan, white,
        brightBlack, brightRed, brightGreen, brightYellow,
        brightBlue, brightMagenta, brightCyan, brightWhite,
    )

    @Test
    fun `the bundled palettes are distinct from each other`() {
        // Two starters that were mistyped into the same palette would sit side
        // by side in a two-column grid looking like a rendering bug.
        val palettes = DroshTheme.STARTERS.map { it.palette }

        val duplicates = palettes
            .groupingBy { it.background }
            .eachCount()
            .filterValues { it > 1 }

        assertEquals(emptyMap<String, Int>(), duplicates)
    }

    @Test
    fun `each starter names the app rather than a release that does not exist`() {
        // The store shows the author line. Crediting a bundled copy to the
        // palette author would be claiming a download that never happened.
        DroshTheme.STARTERS.forEach {
            assertEquals("${it.id} author", "Drosh", it.author)
        }
    }
}