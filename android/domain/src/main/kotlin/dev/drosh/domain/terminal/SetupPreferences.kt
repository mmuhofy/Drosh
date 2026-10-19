package dev.drosh.domain.terminal

import dev.drosh.domain.settings.DroshTheme

/**
 * Captures the user's onboarding choices and threads them through the bootstrap
 * pipeline.
 *
 * Created from the local state collected in SetupFlowScreen, then passed to
 * [TriggerBootstrapUseCase.start] so the bootstrap (PRoot + Ubuntu rootfs)
 * can honour:
 *   - the chosen shell (Zsh → install Oh My Zsh; Bash → skip it)
 *   - the username for the shell prompt
 *   - the package profile (Minimal / Developer / Custom)
 *
 * This type is in `:domain` so that `ui/` → `domain/` → `data/` → `terminal/`
 * can flow without the UI importing `:terminal` directly.
 *
 * **[themeId] is carried but not consumed by the bootstrap.** The rootfs does
 * not exist while these choices are being made, and `.drosh/themes/` lives
 * inside it, so a theme cannot be written during setup. What setup can do is
 * remember the choice here, and the layer that watches for bootstrap to finish
 * writes it into `settings.toml` once the directory is really there. Holding it
 * in this value is what makes the choice part of the recorded setup rather than
 * a field a screen happens to own: a retry re-runs with
 * [defaults] for everything the bootstrap itself needs, and the theme is
 * re-applied from the wizard that still holds the answer.
 */
data class SetupPreferences(
    val userName: String,
    val shellChoice: ShellChoice,
    val packageProfile: PackageProfile,
    val customPackages: Set<String>,
    /** Id of the palette picked in onboarding; see [DroshTheme]. */
    val themeId: String = DroshTheme.DEFAULT.id,
) {
    companion object {
        fun defaults(): SetupPreferences = SetupPreferences(
            userName = "user",
            shellChoice = ShellChoice.Zsh,
            packageProfile = PackageProfile.Standard,
            customPackages = emptySet(),
            themeId = DroshTheme.DEFAULT.id,
        )
    }
}