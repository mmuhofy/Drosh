package dev.drosh.terminal.engine.font

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The atlas packer.
 *
 * A packer has two ways to be wrong and both are silent: two glyphs can be
 * given overlapping rectangles (one mask is drawn over another, and a letter
 * appears with a piece of a different letter in it), or a rectangle can be
 * handed back outside the texture (the upload writes past the atlas and the
 * driver reads garbage). Neither shows up as a crash, so the tests below are
 * the only thing that says they cannot happen.
 */
class SkylineTest {

    @Test
    fun `the first reservation is at the origin`() {
        val skyline = Skyline(100, 100)
        val rect = skyline.reserve(10, 10)
        assertNotNull(rect)
        assertEquals(0, rect!!.x)
        assertEquals(0, rect.y)
        assertEquals(10, rect.width)
        assertEquals(10, rect.height)
        assertEquals(10, skyline.usedBottom)
    }

    @Test
    fun `a glyph that does not fit is refused`() {
        val skyline = Skyline(100, 100)
        assertNull(skyline.reserve(101, 1))
        assertNull(skyline.reserve(1, 101))
    }

    @Test
    fun `a full atlas refuses the next glyph`() {
        val skyline = Skyline(100, 100)
        assertEquals(0, skyline.reserve(100, 100)!!.y)
        assertNull(skyline.reserve(1, 1))
    }

    @Test
    fun `a glyph fills the notch beside a deeper one`() {
        // The whole point of a skyline over a shelf: the second glyph does not
        // have to wait for a new row, it goes beside the first.
        val skyline = Skyline(100, 100)
        val first = skyline.reserve(60, 10)!!
        val second = skyline.reserve(40, 10)!!
        assertEquals(0, first.y)
        assertEquals(60, second.x)
        assertEquals(0, second.y)
    }

    @Test
    fun `a wide glyph goes on top of a narrow one`() {
        val skyline = Skyline(100, 100)
        val first = skyline.reserve(60, 10)!!
        val second = skyline.reserve(60, 10)!!
        // Same span, so the second sits on the first's top edge.
        assertEquals(0, first.y)
        assertEquals(10, second.y)
    }

    @Test
    fun `reservations never overlap and stay inside the texture`() {
        val width = 256
        val height = 256
        val skyline = Skyline(width, height)
        val placed = ArrayList<Skyline.Rect>()

        // Deterministic, so a failure is reproducible: the same sizes in the
        // same order every run. A pinch walks through sizes like this.
        var seed = 123456789L
        var count = 0
        while (count < 500) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val w = 1 + ((seed ushr 33) % 40).toInt()
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val h = 1 + ((seed ushr 33) % 40).toInt()
            val rect = skyline.reserve(w, h) ?: break
            placed.add(rect)
            count++
        }

        assertTrue("the atlas should have taken more than a handful", placed.size > 50)
        for (rect in placed) {
            assertTrue("x inside: $rect", rect.x >= 0 && rect.x + rect.width <= width)
            assertTrue("y inside: $rect", rect.y >= 0 && rect.y + rect.height <= height)
        }
        // Pairwise disjoint: the number of pairs is a few thousand, which is
        // nothing next to the cost of getting this wrong on a device.
        for (i in placed.indices) {
            for (j in i + 1 until placed.size) {
                val a = placed[i]
                val b = placed[j]
                val overlaps = a.x < b.x + b.width &&
                    b.x < a.x + a.width &&
                    a.y < b.y + b.height &&
                    b.y < a.y + a.height
                assertTrue("overlap: $a and $b", !overlaps)
            }
        }
    }

    @Test
    fun `a reset empties the atlas`() {
        val skyline = Skyline(100, 100)
        skyline.reserve(100, 100)
        assertEquals(100, skyline.usedBottom)
        skyline.reset()
        assertEquals(0, skyline.usedBottom)
        val rect = skyline.reserve(100, 100)
        assertNotNull(rect)
        assertEquals(0, rect!!.x)
        assertEquals(0, rect.y)
    }

    @Test
    fun `an empty reservation is free and takes nothing`() {
        // A space has an advance and no mask; the atlas must not be asked to
        // find room for a rectangle of nothing.
        val skyline = Skyline(100, 100)
        val rect = skyline.reserve(0, 0)
        assertNotNull(rect)
        assertEquals(0, rect!!.width)
        assertEquals(0, rect.height)
        assertEquals(0, skyline.usedBottom)
    }
}
