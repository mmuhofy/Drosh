package com.iris.irisshell.domain.terminal

/**
 * Captures the user's onboarding choices and threads them through the bootstrap
 * pipeline.
 *
 * Created from the local state collected in [OnboardingScreen], then passed to
 * [TriggerBootstrapUseCase.start] so the bootstrap (PRoot + Ubuntu rootfs)
 * can honour:
 *   - the chosen shell (Zsh → install Oh My Zsh; Bash → skip it)
 *   - the username for the shell prompt
 *   - the package profile (Minimal / Developer / Custom)
 *
 * This type is in `:domain` so that `ui/` → `domain/` → `data/` → `terminal/`
 * can flow without the UI importing `:terminal` directly.
 */
data class SetupPreferences(
    val userName: String,
    val shellChoice: ShellChoice,
    val packageProfile: PackageProfile,
    val customPackages: Set<String>,
) {
    companion object {
        fun defaults(): SetupPreferences = SetupPreferences(
            userName = "user",
            shellChoice = ShellChoice.Zsh,
            packageProfile = PackageProfile.Developer,
            customPackages = emptySet(),
        )
    }
}
