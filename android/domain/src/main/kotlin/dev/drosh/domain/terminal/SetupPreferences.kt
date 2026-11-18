package dev.drosh.domain.terminal

/**
 * The one setup value that reaches the shell-script pipeline: the name used in
 * the bash prompt.
 *
 * Onboarding no longer collects choices. The shell is bash, there is no package
 * profile, and the theme is applied later from Settings — so [defaults] is the
 * only shape the bootstrap needs. This type stays in `:domain` so `ui/` can
 * hand a value to the use case without importing `:linux`.
 */
data class SetupPreferences(
    val userName: String = "user",
) {
    companion object {
        fun defaults(): SetupPreferences = SetupPreferences()
    }
}
