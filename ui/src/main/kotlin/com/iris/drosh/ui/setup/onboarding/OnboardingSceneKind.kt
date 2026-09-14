package com.iris.irisshell.ui.setup.onboarding

/**
 * The five scenes that make up Drosh's onboarding wizard.
 *
 * Flow:
 *   Welcome → DeviceCheck → Preferences → ShellSetup (conditional — Zsh only) → Security
 *
 * ShellSetup is only shown when the user selects Zsh in Preferences. If Bash
 * is selected, the wizard skips to Security after Preferences.
 *
 * Security is the optional PIN setup screen; user can skip.
 *
 * Transitions are full-screen fade (220ms). The terminal backdrop from the
 * old 3-scene flow is no longer used — the new scenes use clean Surface
 * cards with SetupButton anchors.
 */
enum class OnboardingSceneKind {
    Welcome,
    DeviceCheck,
    Preferences,
    ShellSetup,
    Security;

    fun next(): OnboardingSceneKind? =
        entries.getOrNull(ordinal + 1)
}
