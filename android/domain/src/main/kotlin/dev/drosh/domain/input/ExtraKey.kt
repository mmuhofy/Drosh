package dev.drosh.domain.input

/**
 * Identity of an extra-key button rendered on the on-screen bar.
 *
 * The `Special` family maps 1:1 to Termux's `SpecialButton` (CTRL, ALT,
 * SHIFT, FN). `Text` covers literal glyphs that should be typed into the
 * input (or echoed to PTY in classic mode). `Navigation` covers cursor
 * motion keys that have no printed glyph.
 *
 * The terminal view / dispatcher resolves each kind differently — see
 * `terminal.input.InputDispatcher` for the actual mapping.
 */
sealed interface ExtraKey {

    /** Modifier toggles that read from [dev.drosh.terminal.session.ExtraKeyState]. */
    enum class Special : ExtraKey {
        CTRL, ALT,
    }

    /** Printable glyphs. UTF-16 single-codepoint; combined chars are not supported in v1. */
    data class Text(val glyph: String) : ExtraKey

    /**
     * A function key, F1 to F12.
     *
     * A function key is not a character: sending it as text types the letter
     * "F", which is what the row used to do — the button said "F1" and the
     * terminal received an "F".
     */
    data class FunctionKey(val number: Int) : ExtraKey

    /** Navigation keys — arrows, PgUp/PgDn, Home, End, Tab, Esc, Backspace. */
    enum class Navigation : ExtraKey {
        ESC,
        TAB,
        /** The erase key, DEL — not the "Home" it was wired to. */
        BACKSPACE,
        ARROW_LEFT,
        ARROW_RIGHT,
        ARROW_UP,
        ARROW_DOWN,
        HOME,
        END,
        PAGE_UP,
        PAGE_DOWN,
    }
}
