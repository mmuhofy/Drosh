package dev.drosh.data.session

import dev.drosh.domain.session.SessionGroupLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGridLayoutCodecTest {

    @Test
    fun `order round-trips in the kept order`() {
        val ids = listOf("c", "a", "b")
        assertEquals(ids, SessionGridLayoutCodec.decodeOrder(SessionGridLayoutCodec.encodeOrder(ids)))
    }

    @Test
    fun `the order drops blanks and duplicates`() {
        val encoded = SessionGridLayoutCodec.encodeOrder(listOf("a", "", "a", "b"))
        assertEquals(listOf("a", "b"), SessionGridLayoutCodec.decodeOrder(encoded))
    }

    @Test
    fun `groups round-trip`() {
        val groups = listOf(
            SessionGroupLayout(listOf("a", "b"), "build"),
            SessionGroupLayout(listOf("c", "d", "e")),
        )
        assertEquals(
            groups,
            SessionGridLayoutCodec.decodeGroups(SessionGridLayoutCodec.encodeGroups(groups)),
        )
    }

    @Test
    fun `a group of one is not a group`() {
        val encoded = SessionGridLayoutCodec.encodeGroups(
            listOf(
                SessionGroupLayout(listOf("a")),
                SessionGroupLayout(listOf("b", "c")),
            ),
        )
        assertEquals(listOf(SessionGroupLayout(listOf("b", "c"))), SessionGridLayoutCodec.decodeGroups(encoded))
    }

    @Test
    fun `a name containing a separator is flattened`() {
        val encoded = SessionGridLayoutCodec.encodeGroups(
            listOf(SessionGroupLayout(listOf("a", "b"), "x\u001dy")),
        )
        assertEquals(listOf("a", "b"), SessionGridLayoutCodec.decodeGroups(encoded).single().memberIds)
    }

    @Test
    fun `an empty store decodes to nothing`() {
        assertTrue(SessionGridLayoutCodec.decodeOrder("").isEmpty())
        assertTrue(SessionGridLayoutCodec.decodeGroups("").isEmpty())
    }
}
