package dev.drosh.ui.terminal

/**
 * What draws a terminal, as far as the chrome above it is concerned.
 *
 * The two implementations of [TerminalAppearanceSource] answer the same
 * questions with different things, and this says which is which. The chrome
 * never has to guess: a Termux-backed terminal is sampled from its pixels,
 * and an engine-backed terminal is asked for the colours of its cells — and
 * the two can never be mixed up, because each source only ever asks the
 * terminal it belongs to.
 */
enum class TerminalAppearanceKind {

    /**
     * The vendored Termux renderer.
     *
     * It draws into a plain `View`, so its pixels can be read back: the view
     * is asked to draw itself into a bitmap and what comes out is the
     * terminal's own output, glyphs and all.
     */
    TERMUX,

    /**
     * The Rust engine.
     *
     * It draws through OpenGL, so its pixels live in a surface of its own and
     * cannot be read back. What it can report is the grid — and the colours
     * of the cells in it.
     */
    ENGINE,
}
