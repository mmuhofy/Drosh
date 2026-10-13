package dev.drosh.domain.settings

import kotlinx.coroutines.flow.StateFlow

/**
 * Puts a theme into force, and is where a picker gets its list from.
 *
 * **The chain this exists to close.** A theme is only ever really applied when
 * three things agree: the palette sits in the settings file under
 * `[terminal] theme_id` and `[palette]`, the terminal draws those colours when
 * its session opens, and the theme file behind the id exists so the store can
 * name it. The store screen writes the palette by hand because it is right
 * there next to the file it is applying from. Onboarding is not in that
 * position: the theme is chosen before the rootfs exists, so the file cannot be
 * written yet and the palette cannot land in the rootfs copy of the settings
 * until the bootstrap has finished. This use case is the step that runs then —
 * one call, both halves of the chain, in the order that cannot leave a dangling
 * id behind.
 *
 * Applying is not "remember it for the next launch". The write is a settings
 * write like any other, through [SettingsStore], so every surface that already
 * reads the file picks the palette up — the terminal session that opens next
 * included, with no second path to keep in step.
 */
interface ApplyThemeUseCase {

    /**
     * What a picker shows: the built-in palettes, then whatever else is
     * installed. Available before any rootfs exists, which is the whole point —
     * onboarding asks this question first.
     */
    val choices: StateFlow<List<DroshTheme>>

    /**
     * Makes [themeId] the theme.
     *
     * Resolves the id against [choices], writes the theme file when the theme is
     * not already installed, and writes the palette and the id into the settings
     * file. The theme file goes first: a settings file that names a theme whose
     * file is not on disk is the one state this app cannot describe, and it is
     * the state a user would meet as a theme that applies and then cannot be
     * changed back from.
     *
     * An unknown [themeId] is a failure, not a silent no-op — the caller picked
     * it from a list, so not finding it means the list moved under the pick.
     */
    suspend fun apply(themeId: String): Result<DroshTheme>

    /**
     * Whether the settings file already carries [themeId] and its palette.
     *
     * The check that lets setup apply on every route to a finished bootstrap
     * without writing the file each time — bootstrap reaching ready, and the
     * user pressing Continue, are both answers to the same question.
     */
    fun isApplied(themeId: String): Boolean
}