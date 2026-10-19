package dev.drosh.design.system

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import dev.drosh.design.system.R

/**
 * The fonts for one pack, as Compose families plus the raw resource the
 * terminal needs.
 *
 * [monoResId] exists because the terminal does not draw through Compose — the
 * vendored Termux renderer takes an `android.graphics.Typeface`. Resolving
 * that typeface needs the resource id, which is a design-system id and so
 * cannot be known from the terminal module.
 */
data class DroshFontSet(
    val sans: FontFamily,
    val mono: FontFamily,
    val monoResId: Int,
)

/**
 * Geist Sans, bundled at `res/font/geist_*.ttf`.
 *
 * Inspired by: https://github.com/vercel/geist-font — release v1.7.2.
 * SIL Open Font License 1.1, Copyright 2016 Vercel, Inc.
 */
private val GeistSans: FontFamily = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.geist_medium, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.geist_semibold, FontWeight.SemiBold, FontStyle.Normal),
)

/**
 * Geist Mono, bundled at `res/font/geist_mono_*.ttf`.
 *
 * From https://github.com/vercel/geist-font — release v1.7.2.
 * SIL Open Font License 1.1, Copyright 2016 Vercel, Inc.
 */
private val GeistMono: FontFamily = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.geist_mono_semibold, FontWeight.SemiBold, FontStyle.Normal),
)

/**
 * Inter, bundled at `res/font/inter_*.ttf`.
 *
 * From https://github.com/rsms/inter — release v4.1.
 * SIL Open Font License 1.1, Copyright (c) 2016 The Inter Project Authors.
 */
private val InterSans: FontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.inter_medium, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.inter_semibold, FontWeight.SemiBold, FontStyle.Normal),
)

/**
 * JetBrains Mono, bundled at `res/font/jetbrains_mono_*.ttf`.
 *
 * From https://github.com/JetBrains/JetBrainsMono — release v2.304.
 * SIL Open Font License 1.1, Copyright (c) 2020 JetBrains Mono contributors.
 */
private val JetBrainsMono: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold, FontStyle.Normal),
)

/**
 * The font sets Drosh ships.
 *
 * Two packs cover the range: Geist is tighter and reads as the default
 * product voice, Inter is the wider and more neutral one people reach for when
 * a document or code is the point. Both ship Regular, Medium and SemiBold
 * because the Material type scale asks for Medium and SemiBold at ten
 * separate tokens, and synthesizing them from a single weight is what made
 * titles look soft before.
 *
 * Keyed by the name of `dev.drosh.domain.settings.FontPack` rather than by
 * the enum itself, because `:design-system` must not depend on `:domain` —
 * per AGENT.md it is independent of every other layer. The enum and these
 * names have to stay in step; `byName` falls back to [Geist] if one of them
 * is renamed and the other is not.
 */
object DroshFonts {

    /** The default pack. */
    val Geist = DroshFontSet(
        sans = GeistSans,
        mono = GeistMono,
        monoResId = R.font.geist_mono_regular,
    )

    val Inter = DroshFontSet(
        sans = InterSans,
        mono = JetBrainsMono,
        monoResId = R.font.jetbrains_mono_regular,
    )

    private val byName = mapOf(
        "Geist" to Geist,
        "Inter" to Inter,
    )

    /**
     * Resolves a pack by name, falling back to [Geist].
     *
     * The fallback rather than an error on purpose: the stored string is
     * whatever an older build wrote, and an unrecognised value must not leave
     * the app unable to start.
     */
    fun byName(name: String?): DroshFontSet = byName[name] ?: Geist
}

/**
 * The active font set, read anywhere in the composition.
 *
 * Re-exported everywhere so any consumer writes
 *
 * ```kotlin
 * fontFamily = LocalFontSet.current.sans
 * ```
 *
 * Defaults to Geist so a preview, a test, or any screen rendered outside
 * [dev.drosh.ui.theme.DroshTheme] still draws in a real bundled font rather
 * than the platform default.
 */
val LocalFontSet = staticCompositionLocalOf { DroshFonts.Geist }