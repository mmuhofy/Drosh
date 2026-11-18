package dev.drosh.domain.block

/**
 * One styled run of terminal output.
 *
 * Colors are packed ARGB (`0xAARRGGBB`), the same shape the renderer uses for a
 * frame's cells. `null` means "the terminal's default", not black — a program
 * that never sent SGR must inherit the theme rather than paint its own.
 *
 * Kept deliberately small: this is the wire contract between the terminal's
 * ANSI parser and the block UI, not a general rich-text model.
 */
data class StyledSpan(
    val text: String,
    val fg: Int?,
    val bg: Int?,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
)

/** One logical line of command output, as a row of styled runs. */
data class StyledLine(val spans: List<StyledSpan>)
