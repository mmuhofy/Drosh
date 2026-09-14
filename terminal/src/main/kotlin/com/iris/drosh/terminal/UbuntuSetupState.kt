package com.iris.irisshell.terminal

sealed class UbuntuSetupState {
    data object Idle : UbuntuSetupState()
    data object Extracting : UbuntuSetupState()
    data object Configuring : UbuntuSetupState()
    data class InstallingPackages(
        val packageName: String = "",
        val message: String = "Installing packages..."
    ) : UbuntuSetupState()
    data class InstallingOhMyZsh(
        val message: String = "Installing Oh My Zsh..."
    ) : UbuntuSetupState()
    data object Optimizing : UbuntuSetupState()
    data object Ready : UbuntuSetupState()
    data class Failed(val error: String) : UbuntuSetupState()
}
