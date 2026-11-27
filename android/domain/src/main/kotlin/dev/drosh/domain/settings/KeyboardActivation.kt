package dev.drosh.domain.settings

/**
 * How a touch asks for the keyboard.
 *
 * ## Why it is a setting
 *
 * A terminal takes every gesture it can: a tap focuses, a drag scrolls, a
 * pinch zooms, and a program that tracks the mouse takes all three for itself.
 * Which of those also raises the keyboard is the one gesture with no natural
 * answer — a tap is what a person does when they want to type, but it is also
 * what a person does when they want to read.
 *
 * ## Why these four
 *
 * Each names a gesture that is *already free* in the terminal — none of them
 * costs a scroll, a pinch or a selection:
 *
 * - [Tap] — one tap anywhere. The default, and what every other terminal does.
 * - [TwoFingerTap] — two fingers down and up without moving. A pinch needs
 *   movement before the scale detector claims it, so a still two-finger tap
 *   never turns into a zoom, and a one-finger tap never turns into typing.
 * - [BottomEdgeTap] — a tap in the bottom fifth of the pane, where the prompt
 *   is. Reading anywhere above it does not raise the keyboard.
 * - [TopEdgeTap] — a tap in the top fifth of the pane, the edge a thumb reaches
 *   without covering the output. Reading below it does not raise the keyboard.
 * - [Never] — only the keyboard button does. For someone whose terminal is
 *   mostly read, or who types on a hardware keyboard, this is the quiet one.
 */
enum class KeyboardActivation {
    Tap,
    TwoFingerTap,
    BottomEdgeTap,
    TopEdgeTap,
    Never;

    val tomlName: String get() = name.lowercase()

    companion object {
        /** Unknown answers the default rather than failing the file. */
        fun fromName(value: String?): KeyboardActivation = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true) || it.tomlName == value
        } ?: Tap
    }
}
