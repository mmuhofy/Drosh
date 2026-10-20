package dev.drosh.ui.setup.onboarding.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.drosh.theme.LocalDroshColors

/**
 * Draws a subtree in an accent, for as long as the subtree lasts.
 *
 * **What this is, precisely.** It overrides [LocalDroshColors] with a copy whose
 * `primary` is the given colour, so every token that reads it — `DroshPrimary`,
 * `DroshBuild`, the pill buttons, a selected card border and label — takes the
 * new accent. It does **not** rebuild `MaterialTheme.colorScheme`, which was
 * composed once at the app root from the palette active at the time; a component
 * reading `MaterialTheme.colorScheme.primary` directly would keep the old
 * accent. The setup screens read the theme tokens rather than the
 * Material ones, so this covers every one of them.
 *
 * The scope is deliberately the onboarding flow. An accent that changed under
 * the whole app mid-setup would repaint settings rows the user has not opened
 * yet, and the onboarding chrome is the only thing a palette is being previewed
 * against here anyway.
 *
 * This is the live half of a theme choice. The palette itself reaches the
 * terminal through `settings.toml` and the settings store — see
 * [dev.drosh.domain.settings.ApplyThemeUseCase] — because a palette is a
 * preference about output, not about the chrome. The accent is a preference
 * about the chrome, so showing it live is the honest preview of it.
 *
 * The colour animates rather than snapping: a palette is mostly one colour, and
 * a hard cut between two of them reads as a flicker rather than as a choice.
 */
@Composable
fun AccentPreview(
    accent: Color,
    content: @Composable () -> Unit,
) {
    val current = LocalDroshColors.current

    val animatedAccent by animateColorAsState(
        targetValue = accent,
        animationSpec = tween(ACCENT_ANIM_MS),
        label = "accentPreview",
    )

    CompositionLocalProvider(
        LocalDroshColors provides current.copy(
            primary = animatedAccent,
            // Recomputed rather than carried over: the shipped token is a
            // near-black chosen for a near-black-on-blue default, and a pale
            // accent under a near-black label is text nobody can read.
            onPrimary = readableOn(animatedAccent),
            build = animatedAccent,
        ),
        content = content,
    )
}

/** "#RRGGBB" as a colour. Black when it is not one: a broken file, not a crash. */
internal fun previewColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Black)

/**
 * Black or white, whichever reads on [background].
 *
 * Luminance rather than a sampled channel sum: a saturated blue and a saturated
 * yellow can have the same channel sum and opposite readable colours.
 */
private fun readableOn(background: Color): Color =
    if (background.luminance() > LIGHT_ACCENT_LUMINANCE) DARK_ON_ACCENT else LIGHT_ON_ACCENT

private val DARK_ON_ACCENT = Color(0xFF0E0E0E)
private val LIGHT_ON_ACCENT = Color(0xFFFFFFFF)
private const val LIGHT_ACCENT_LUMINANCE = 0.55f
private const val ACCENT_ANIM_MS = 260