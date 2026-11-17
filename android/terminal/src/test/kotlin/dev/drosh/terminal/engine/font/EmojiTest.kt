package dev.drosh.terminal.engine.font

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The emoji table, without a font.
 *
 * Two things can be wrong with a table like this and both are silent: a range
 * that is not sorted breaks the binary search (so an emoji is drawn with the
 * text face and comes out as a box), and a range that is too wide drags a
 * text symbol into the colour pass. The cases below are the ones a prompt
 * actually shows.
 */
class EmojiTest {

    @Test
    fun `the arrow a prompt draws is an emoji`() {
        // A binary search over an unsorted table finds some entries and misses
        // others, which is the worst kind of wrong: it looks like a font that
        // lacks a glyph. The first range and the last are both checked.
        assertTrue(Emoji.isEmoji(0x2194)) // left right arrow
        assertTrue(Emoji.isEmoji(0x27A1)) // black rightwards arrow
        assertTrue(Emoji.isEmoji(0x1F33F)) // herb
        assertTrue(Emoji.isEmoji(0x1FFFD)) // the last range's far end
    }

    @Test
    fun `the emoji blocks are recognised`() {
        assertTrue(Emoji.isEmoji(0x1F600)) // grinning face
        assertTrue(Emoji.isEmoji(0x1F680)) // rocket
        assertTrue(Emoji.isEmoji(0x2764)) // heart
        assertTrue(Emoji.isEmoji(0x2B50)) // star
        assertTrue(Emoji.isEmoji(0x1F9D1)) // person
    }

    @Test
    fun `plain text is not`() {
        assertFalse(Emoji.isEmoji('A'.code))
        assertFalse(Emoji.isEmoji(' '.code))
        assertFalse(Emoji.isEmoji(0x2502)) // box drawing
        assertFalse(Emoji.isEmoji(0x2588)) // full block
        assertFalse(Emoji.isEmoji(0xE0B0)) // powerline
        assertFalse(Emoji.isEmoji(0x0301)) // combining acute
    }

    @Test
    fun `the edges of a range are inside it`() {
        assertTrue(Emoji.isEmoji(0x1F000))
        assertTrue(Emoji.isEmoji(0x1FAFF))
        assertFalse(Emoji.isEmoji(0x1FB00))
        assertTrue(Emoji.isEmoji(0x2600))
        assertTrue(Emoji.isEmoji(0x27BF))
        assertFalse(Emoji.isEmoji(0x27C0))
    }
}