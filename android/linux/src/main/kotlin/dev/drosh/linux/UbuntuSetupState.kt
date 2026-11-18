package dev.drosh.linux

/** Raw states of the shell-script bootstrap pipeline: extract, prepare, finish. */
sealed class UbuntuSetupState {
    data object Idle : UbuntuSetupState()
    data object Extracting : UbuntuSetupState()
    data object Configuring : UbuntuSetupState()
    data object Optimizing : UbuntuSetupState()
    data object Ready : UbuntuSetupState()
    data class Failed(val error: String, val failedStep: String = "Unknown") : UbuntuSetupState()
}
