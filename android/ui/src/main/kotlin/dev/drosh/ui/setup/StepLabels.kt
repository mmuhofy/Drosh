package dev.drosh.ui.setup

import dev.drosh.domain.terminal.BootstrapStep

/**
 * Public, stable human-readable label for each [BootstrapStep].
 *
 * Lives in the `setup` package so any screen or component in `ui/setup` can
 * call it without an import — `:ui/setup/components/` does not need to be
 * the only home.
 */
fun BootstrapStep.label(): String = when (this) {
    BootstrapStep.Idle -> "Preparing"
    BootstrapStep.Extracting -> "Extracting rootfs"
    BootstrapStep.Configuring -> "Preparing bash"
    BootstrapStep.Optimizing -> "Finalizing rootfs"
    BootstrapStep.Ready -> "Ready"
    BootstrapStep.Failed -> "Failed"
}
