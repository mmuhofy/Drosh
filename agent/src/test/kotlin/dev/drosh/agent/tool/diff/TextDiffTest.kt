package dev.drosh.agent.tool.diff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDiffTest {

    private fun lines(text: String) = text.split("\n")

    // ── the empty and trivial cases ───────────────────────────────────────

    @Test
    fun `identical text produces no hunks`() {
        val result = TextDiff.diff("a\nb\nc", "a\nb\nc")

        assertTrue(result.isEmpty)
        assertEquals(0, result.added)
        assertEquals(0, result.removed)
    }

    @Test
    fun `a trailing newline is not a change`() {
        // "a\nb\n" and "a\nb" are the same text. Naive splitting would report a
        // blank line appearing or disappearing on every single write.
        assertTrue(TextDiff.diff("a\nb\n", "a\nb").isEmpty)
        assertTrue(TextDiff.diff("a\nb", "a\nb\n").isEmpty)
    }

    @Test
    fun `two empty files are identical`() {
        assertTrue(TextDiff.diff("", "").isEmpty)
    }

    @Test
    fun `creating a file from nothing is all additions`() {
        val result = TextDiff.diff("", "one\ntwo")

        assertEquals(2, result.added)
        assertEquals(0, result.removed)
        assertEquals(1, result.hunks.size)
    }

    @Test
    fun `emptying a file is all removals`() {
        val result = TextDiff.diff("one\ntwo", "")

        assertEquals(0, result.added)
        assertEquals(2, result.removed)
    }

    // ── the edit script ───────────────────────────────────────────────────

    @Test
    fun `a single changed line is one removal and one addition`() {
        val result = TextDiff.diff("a\nb\nc", "a\nB\nc")

        assertEquals(1, result.added)
        assertEquals(1, result.removed)
        assertEquals(1, result.hunks.size)
    }

    @Test
    fun `an inserted line keeps its neighbours as context`() {
        val result = TextDiff.diff("a\nc", "a\nb\nc")

        val kinds = result.hunks.single().lines.map { it.kind }
        assertEquals(
            listOf(
                TextDiff.Kind.CONTEXT,
                TextDiff.Kind.ADDED,
                TextDiff.Kind.CONTEXT,
            ),
            kinds,
        )
    }

    @Test
    fun `an interior change reports only the surrounding context`() {
        val old = (1..20).joinToString("\n")
        val new = old.replace("10", "ten")

        val hunk = TextDiff.diff(old, new).hunks.single()

        // 3 lines of context either side plus the changed pair.
        assertEquals(3 + 2 + 3, hunk.lines.size)
        assertTrue(hunk.lines.any { it.kind == TextDiff.Kind.REMOVED && it.text == "10" })
        assertTrue(hunk.lines.any { it.kind == TextDiff.Kind.ADDED && it.text == "ten" })
    }

    @Test
    fun `two distant changes become two hunks`() {
        val old = (1..40).joinToString("\n")
        val new = old.replace("5", "FIVE").replace("35", "THIRTYFIVE")

        val result = TextDiff.diff(old, new)

        assertEquals(2, result.hunks.size)
        assertEquals(2, result.added)
        assertEquals(2, result.removed)
    }

    @Test
    fun `two nearby changes are merged into one hunk`() {
        val old = (1..40).joinToString("\n")
        val new = old.replace("10", "TEN").replace("12", "TWELVE")

        assertEquals(1, TextDiff.diff(old, new).hunks.size)
    }

    @Test
    fun `context can be widened`() {
        val old = (1..20).joinToString("\n")
        val new = old.replace("10", "ten")

        assertEquals(3 + 2 + 3, TextDiff.diff(old, new, context = 3).hunks.single().lines.size)
        assertEquals(8 + 2 + 8, TextDiff.diff(old, new, context = 8).hunks.single().lines.size)
    }

    // ── unified output ────────────────────────────────────────────────────

    @Test
    fun `unified output has file headers and a hunk header`() {
        val text = TextDiff.unified("a\nb", "a\nc", oldLabel = "src/App.kt", newLabel = "src/App.kt")

        val output = lines(text)
        assertEquals("--- a/src/App.kt", output[0])
        assertEquals("+++ b/src/App.kt", output[1])
        assertTrue("expected a @@ header, got: ${output[2]}", output[2].startsWith("@@ -1,2 +1,2 @@"))
        assertEquals(" a", output[3])
        assertEquals("-b", output[4])
        assertEquals("+c", output[5])
    }

    @Test
    fun `unified output is empty when nothing changed`() {
        assertEquals("", TextDiff.unified("a\nb", "a\nb"))
    }

    @Test
    fun `headers are omitted when no labels are given`() {
        val text = TextDiff.unified("a", "b")

        assertFalse(lines(text).any { it.startsWith("---") || it.startsWith("+++") })
        assertTrue(lines(text).first().startsWith("@@"))
    }

    @Test
    fun `an empty range is anchored at the line before it`() {
        // The unified-diff convention: inserting before the first line of a file
        // reports the range as starting at 0, not 1.
        val result = TextDiff.diff("b", "a\nb")

        assertEquals(0, result.hunks.single().oldStart)
        assertEquals(0, result.hunks.single().oldCount)
    }

    @Test
    fun `summary reads as added and removed counts`() {
        val result = TextDiff.diff("a\nb\nc", "a\nx\ny\nc")

        assertEquals("+2 -1", TextDiff.summary(result))
    }

    // ── the fallback and the trimming ─────────────────────────────────────

    @Test
    fun `identical prefix and suffix are trimmed before reconciling`() {
        // 200 identical lines, one changed in the middle: the answer must be a
        // single 3+2+3 hunk, not a whole-file replacement.
        val old = (1..200).joinToString("\n")
        val new = old.replace("100", "ONEHUNDRED")

        val result = TextDiff.diff(old, new)

        assertEquals(1, result.hunks.size)
        assertEquals(3 + 2 + 3, result.hunks.single().lines.size)
    }

    @Test
    fun `reordered lines show as removal then addition`() {
        val result = TextDiff.diff("a\nb\nc", "c\nb\na")

        // The middle line still matches, so it survives as context.
        assertTrue(result.added >= 1)
        assertTrue(result.removed >= 1)
        assertTrue(result.hunks.single().lines.any { it.text == "b" && it.kind == TextDiff.Kind.CONTEXT })
    }

    @Test
    fun `a very large change falls back to a block replace instead of allocating`() {
        // 3000 x 3000 is 9M cells, past the cap. The result must still be a valid
        // diff rather than an OutOfMemoryError on a phone.
        val old = (1..3_000).joinToString("\n") { "old line $it" }
        val new = (1..3_000).joinToString("\n") { "new line $it" }

        val result = TextDiff.diff(old, new)

        assertEquals(3_000, result.added)
        assertEquals(3_000, result.removed)
        assertFalse(result.isEmpty)
    }

    @Test
    fun `duplicate lines do not confuse the match`() {
        // "value" appears many times; the LCS must still anchor somewhere sensible
        // rather than reporting the whole block changed.
        val old = (1..10).joinToString("\n") { if (it % 2 == 0) "value" else "other $it" }
        val new = old.replace("other 5", "FIVE")

        val result = TextDiff.diff(old, new)

        assertEquals(1, result.added)
        assertEquals(1, result.removed)
        assertTrue(
            "unchanged lines should survive as context",
            result.hunks.single().lines.count { it.kind == TextDiff.Kind.CONTEXT } > 3,
        )
    }

    @Test
    fun `an empty new line is a real line, not a missing one`() {
        val result = TextDiff.diff("a\n\nb", "a\nb")

        assertEquals(1, result.removed)
        assertTrue(result.hunks.single().lines.any { it.kind == TextDiff.Kind.REMOVED })
    }
}