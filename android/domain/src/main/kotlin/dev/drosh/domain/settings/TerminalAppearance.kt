package dev.drosh.domain.settings

/**
 * How the terminal should look: the application's configuration, in one piece.
 *
 * ## Why this is one type
 *
 * The terminal has two owners for the same facts. The **application** decides
 * what the terminal should look like: the theme's colours, the cursor's shape
 * and blink, whether bold text is brighter, whether the font ligates, how big
 * the text is and which face it is drawn in. The **terminal** decides what it
 * has been told: the colours an `OSC 4/10/11/12` set, the cursor shape a
 * `DECSCUSR` asked for.
 *
 * The terminal's answers win — alacritty does the same, and it is the only
 * order that makes sense: a program that asks for its own colours has a
 * reason, and the app's setting is the answer to a question the program never
 * asked.
 *
 * So this type is the *application's* half, and it is what the app hands to
 * the engine. The engine keeps the terminal's half to itself.
 *
 * The engine's own defaults live in the Rust core (`TermConfig`), the way
 * alacritty's do: the configuration belongs to the application, the terminal
 * crate only knows what to fall back on.
 */
data class TerminalAppearance(
    /** The theme's colours. */
    val palette: ThemePalette = ThemePalette.DEFAULT,
    /** The cursor's shape, when the terminal has not asked for one. */
    val cursorStyle: CursorStyle = CursorStyle.Block,
    /**
     * How long the cursor takes to blink, in milliseconds; 0 means it does not.
     *
     * alacritty's own (`config/cursor.rs` — `blink_interval`).
     */
    val cursorBlinkMs: Int = DEFAULT_CURSOR_BLINK_MS,
    /** Whether bold text is drawn in the bright colours. */
    val boldBrightens: Boolean = false,
    /** Whether the font's ligatures are used. */
    val ligatures: Boolean = true,
    /** The size of the text, in sp. */
    val textSizeSp: Float = 14f,
    /** Which face the text is drawn in. */
    val fontPack: FontPack = FontPack.Geist,
) {
    companion object {
        /** alacritty's own default (`config/cursor.rs` — 750 ms). */
        const val DEFAULT_CURSOR_BLINK_MS = 750
    }
}
