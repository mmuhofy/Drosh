package dev.drosh.ui.setup.onboarding

/**
 * The three scenes that make up Drosh's onboarding wizard.
 *
 * Flow:
 *   Welcome → About → PickShell
 *
 * After PickShell, the wizard finishes and routes to the BootstrapStepperScreen
 * (which runs the actual PRoot + Ubuntu rootfs setup).
 *
 * Transitions between scenes use a light slide + fade (250ms). The page
 * indicator (3 dots) animates with a "worm" stretch between positions.
 */
enum class OnboardingSceneKind {
    Welcome,
    About,
    PickShell;

    fun next(): OnboardingSceneKind? =
        entries.getOrNull(ordinal + 1)
}
