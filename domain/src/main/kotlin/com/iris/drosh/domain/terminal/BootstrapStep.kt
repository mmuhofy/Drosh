package com.iris.irisshell.domain.terminal

/**
 * Discrete steps the bootstrap pipeline walks through.
 *
 * The order in this enum is the visual order on the stepper UI. The numbers are
 * stable so persisted / shared progress survives enum reshuffles.
 */
enum class BootstrapStep(val ordinalHint: Int) {
    Idle(0),
    Extracting(1),
    Configuring(2),
    InstallingPackages(3),
    InstallingOhMyZsh(4),
    Optimizing(5),
    Ready(6),
    Failed(-1);

    val isTerminal: Boolean
        get() = this == Ready || this == Failed
}
