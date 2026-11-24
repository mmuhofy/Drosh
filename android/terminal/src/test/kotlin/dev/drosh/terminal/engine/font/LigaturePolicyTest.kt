package dev.drosh.terminal.engine.font

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
    fun `only the operator alphabet is a trigger`() {
        assertTrue(LigaturePolicy.isTrigger('='.code))
        assertTrue(LigaturePolicy.isTrigger('~'.code))
        assertFalse(LigaturePolicy.isTrigger('a'.code))
        assertFalse(LigaturePolicy.isTrigger(' '.code))
        assertFalse(LigaturePolicy.isTrigger(0x2500))
    }
}
