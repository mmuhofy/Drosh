package com.termux.terminal

/**
 * Whether a code point separates words for selection purposes.
 *
 * Selection used to expand across anything that was not a space. On a TUI
 * screen that makes a run of box-drawing characters one enormous word: tapping
 * near a border selected eleven glyphs of frame instead of the label inside it.
 * A frame is drawing, not text.
 *
 * The ranges cover what full-screen programs actually draw with. Written as
 * escapes deliberately — spelled as literals these are invisible in a diff,
 * and one of them is indistinguishable from an ordinary space.
 *
 * Punctuation is deliberately *not* a separator, so `ls -la` stays two words
 * and `http://a.b/c` stays one. Making punctuation a boundary reads tidier but
 * breaks selecting the paths and URLs you actually want in a shell.
 */
internal fun isSelectionSeparator(cp: Char): Boolean = when (cp) {
    ' ', '\t', '\u000B', '\u000C', '\u00A0' -> true
    in '\u2500'..'\u257F' -> true   // box drawing
    in '\u2580'..'\u259F' -> true   // block elements
    in '\u25A0'..'\u25FF' -> true   // geometric shapes
    in '\uE0B0'..'\uE0BF' -> true   // powerline separators
    else -> false
}