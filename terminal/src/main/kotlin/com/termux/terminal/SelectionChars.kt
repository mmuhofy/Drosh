package com.termux.terminal

/**
 * Whether a code point separates words for selection purposes.
 *
 * Selection used to expand across anything that was not a space. On a TUI
 * screen that makes a run of box-drawing characters one enormous word: tapping
 * near a border selected eleven glyphs of frame instead of the label inside it.
 * A frame is drawing, not text.
 *
 * Plain comparisons on cp.code rather than char ranges or an escape-laden when,
 * deliberately. The ranges themselves are the point; how they are spelled is
 * not worth a character that no diff can distinguish from a space.
 *
 *   0x2500..0x257F  box drawing
 *   0x2580..0x259F  block elements
 *   0x25A0..0x25FF  geometric shapes
 *   0xE0B0..0xE0BF  powerline separators
 *
 * Punctuation is deliberately *not* a separator, so `ls -la` stays two words
 * and `http://a.b/c` stays one. Making punctuation a boundary reads tidier but
 * breaks selecting the paths and URLs you actually want in a shell.
 */
internal fun isSelectionSeparator(cp: Char): Boolean {
    if (cp == ' ' || cp == '\t' || cp.code == 0x00A0) return true
    val c = cp.code
    return c in 0x2500..0x257F ||
        c in 0x2580..0x259F ||
        c in 0x25A0..0x25FF ||
        c in 0xE0B0..0xE0BF
}