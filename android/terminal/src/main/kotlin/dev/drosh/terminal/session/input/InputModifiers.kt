package dev.drosh.terminal.session.input

/**
 * The modifiers that were held while a key was pressed.
 *
 * Ctrl and Alt only: on a phone those two come from the extra-keys row and
 * from nowhere else, and both are sticky — a button arms them and the next
 * key consumes them.
 *
 * The flags say *what* was held, not *how* it is encoded: the vendored
 * terminal puts the alt prefix in front of the code point, the engine's
 * encoder has its own rules. Each writer decides that for its own terminal.
 */
data class InputModifiers(
    val ctrl: Boolean = false,
    val alt: Boolean = false,
)
