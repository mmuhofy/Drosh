package com.iris.irisshell.ui.setup

import com.iris.irisshell.domain.terminal.BootstrapStep

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
    BootstrapStep.Configuring -> "Configuring rootfs"
    BootstrapStep.InstallingPackages -> "Installing packages"
    BootstrapStep.InstallingOhMyZsh -> "Installing Oh My Zsh"
    BootstrapStep.Optimizing -> "Optimizing"
    BootstrapStep.Ready -> "Ready"
    BootstrapStep.Failed -> "Failed"
}
