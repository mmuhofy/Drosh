package com.iris.irisshell.design.system

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.iris.irisshell.design.system.R

/**
 * Outfit (Regular) font family bundled with the design system.
 *
 * Inspired by: https://github.com/RohitKushvaha01/ReTerminal — the TTF lives
 * at `core/main/src/main/res/font/outfit_regular.ttf` (SIL Open Font
 * License, Copyright 2021 The Outfit Project Authors). The same file is now
 * vendored at `:design-system/src/main/res/font/outfit_regular.ttf` so
 * both `:app` and `:ui` modules can draw typography without each carrying
 * their own copy.
 *
 * Re-exported everywhere so any consumer just writes
 *
 * ```kotlin
 * fontFamily = OutfitFontFamily
 * ```
 *
 * Adding SemiBold/Bold weights: drop the additional TTFs into the same
 * `res/font/<name>.ttf` resource and register them below in the same
 * `FontFamily(...)` call.
 */
val OutfitFontFamily: FontFamily =
    FontFamily(Font(R.font.outfit_regular, FontWeight.Normal, FontStyle.Normal))
