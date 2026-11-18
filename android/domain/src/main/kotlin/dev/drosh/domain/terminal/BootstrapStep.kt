package dev.drosh.domain.terminal

/**
 * Discrete steps the bootstrap pipeline walks through.
 *
 * The order in this enum is the visual order on the stepper UI. The numbers are
 * stable so persisted / shared progress survives enum reshuffles. Package
 * installation and Oh My Zsh are gone from setup, so those steps went with them.
 */
enum class BootstrapStep(val ordinalHint: Int) {
    Idle(0),
    Extracting(1),
    Configuring(2),
    Optimizing(3),
    Ready(4),
    Failed(-1);

    val isTerminal: Boolean
        get() = this == Ready || this == Failed
}
