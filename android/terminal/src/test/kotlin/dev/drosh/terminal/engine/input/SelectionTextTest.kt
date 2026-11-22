package dev.drosh.terminal.engine.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a copy actually puts on the clipboard.
 *
 * The terminal's formatter supplies plain text, but the clipboard's contract
 * is stricter: no escape sequences, no fixed-width padding, and every
 * meaningful space and Unicode character kept exactly as it was. These are
 * the cases a user notices — a colour prompt, a copied `git log`, a Turkish
 * word with combining marks, and a column of output whose lines are all
 * shorter than the screen.
 */
class SelectionTextTest {

    @Test
    fun `ordinary text is unchanged`() {
        assertEquals(
            "ERROR: Compilation failed",
            SelectionText.normalize("ERROR: Compilation failed"),
        )
    }

    @Test
    fun `spaces between words are meaningful and stay`() {
        assertEquals(
            "git   status   --short",
            SelectionText.normalize("git   status   --short"),
        )
    }

    @Test
    fun `leading indentation is part of the selection`() {
        assertEquals(
            "    if (ready) {\n        run()",
            SelectionText.normalize("    if (ready) {\n        run()"),
        )
    }

    @Test
    fun `cell padding at the end of a row is removed`() {
        assertEquals(
            "drwxr-xr-x  drosh\n-rw-r--r--  README",
            SelectionText.normalize("drwxr-xr-x  drosh        \n-rw-r--r--  README    "),
        )
    }

    @Test
    fun `blank lines inside the selection stay, trailing ones do not`() {
        assertEquals(
            "first\n\nthird",
            SelectionText.normalize("first\n\nthird\n\n\n"),
        )
    }

    @Test
    fun `a selection of only padding is empty`() {
        assertEquals("", SelectionText.normalize("   \n\t\n  "))
    }

    @Test
    fun `a colour sequence never reaches the clipboard`() {
        assertEquals(
            "ERROR: Compilation failed",
            SelectionText.normalize("\u001B[31mERROR:\u001B[0m Compilation failed"),
        )
    }

    @Test
    fun `an OSC hyperlink and its terminator are removed`() {
        assertEquals(
            "visit example.com now",
            SelectionText.normalize(
                "visit \u001B]8;;https://example.com\u0007example.com\u001B]8;;\u0007 now",
            ),
        )
    }

    @Test
    fun `a charset escape does not leave its selector behind`() {
        assertEquals("ok", SelectionText.normalize("\u001B(Bok"))
    }

    @Test
    fun `a lone trailing escape is dropped`() {
        assertEquals("prompt", SelectionText.normalize("prompt\u001B"))
    }

    @Test
    fun `Unicode and emoji are preserved exactly`() {
        val text = "Türkçe: çğıöşü — 日本語 — 👩‍💻 — e\u0301"
        assertEquals(text, SelectionText.normalize(text))
    }

    @Test
    fun `wide characters are not widened or split`() {
        assertEquals("日本語", SelectionText.normalize("日本語"))
    }

    @Test
    fun `soft-wrapped lines supplied as one line are not re-split`() {
        assertEquals(
            "a long command that was wrapped by the terminal",
            SelectionText.normalize("a long command that was wrapped by the terminal"),
        )
    }

    @Test
    fun `carriage returns do not leave a blank line`() {
        assertEquals("one\ntwo", SelectionText.normalize("one\r\ntwo\r\n"))
    }
}
