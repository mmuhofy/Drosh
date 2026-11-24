package dev.drosh.terminal.engine.font

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LigaturePolicyTest {

    private fun shape(text: String): Boolean =
        LigaturePolicy.canShape(text.codePoints().toArray())

    @Test
    fun `operator pairs shape`() {
        assertTrue(shape("!="))
        assertTrue(shape("->"))
        assertTrue(shape("=>"))
        assertTrue(shape("::"))
        assertTrue(shape("&&"))
        assertTrue(shape("..."))
    }

    @Test
    fun `plain words stay per cell`() {
        assertFalse(shape("ab"))
        assertFalse(shape("name"))
        assertFalse(shape("a-b"))
    }

    @Test
    fun `one cell cannot ligate`() {
        assertFalse(shape("="))
        assertFalse(shape(""))
    }

    @Test
    fun `a run longer than the mask budget falls back`() {
        val long = "=".repeat(LigaturePolicy.MAX_CELLS + 1)
        assertFalse(shape(long))
    }

    @Test
    fun `a run is scanned from the cell it starts at`() {
        // The row's first cells belong to another style, so a scan that started
        // at the row's beginning instead of at the column it was asked about
        // would stop at once and lose the run — or, worse, shape the row's
        // opening text and draw it again where the user is typing.
        val bold = booleanArrayOf(true, true, true, true, true, false, false)
        val from = 5
        val length = LigaturePolicy.runLength(from, bold.size) { at -> !bold[at] }
        assertEquals(2, length)
    }

    @Test
    fun `a run stops at the end of the row`() {
        val length = LigaturePolicy.runLength(0, 3) { true }
        assertEquals(3, length)
    }

    @Test
    fun `only the operator alphabet is a trigger`() {
        assertTrue(LigaturePolicy.isTrigger('='.code))
        assertTrue(LigaturePolicy.isTrigger('~'.code))
        assertFalse(LigaturePolicy.isTrigger('a'.code))
        assertFalse(LigaturePolicy.isTrigger(' '.code))
        assertFalse(LigaturePolicy.isTrigger(0x2500))
    }
}
