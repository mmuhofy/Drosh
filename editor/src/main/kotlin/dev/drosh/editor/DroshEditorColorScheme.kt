/*
 * Copyright (C) 2026 Drosh contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package dev.drosh.editor

import dev.drosh.core.DroshPalette
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Drosh's editor palette, expressed as an sora-editor [EditorColorScheme].
 *
 * sora-editor ships five built-in schemes (Darcula, Eclipse, GitHub, NotepadXX,
 * VS2019). None of them is Drosh: they all assume a different background, and
 * the gutter, selection and current-line colours are tuned against their own.
 * Starting from [EditorColorScheme.applyDefault] and overwriting only the keys
 * that carry Drosh's identity gets the editor looking like part of the app
 * without hand-picking eighty constants.
 *
 * Overwritten here: the two backgrounds that the eye actually compares against
 * the terminal, the text and line-number colours, and the selection. Anything
 * else the scheme resolves keeps sora's own dark default, which is close enough
 * to Drosh's palette that leaving it alone reads as deliberate rather than
 * unfinished.
 *
 * The token constants used later (KEYWORD, COMMENT, LITERAL, …) are
 * [EditorColorScheme]'s own indices. They are left at their defaults in this
 * pass because syntax highlighting is not wired up yet; when it is, those are
 * the keys a TextMate theme maps onto.
 */
internal fun droshEditorColorScheme(): EditorColorScheme = EditorColorScheme().apply {
    applyDefault()

    // Palette values are Long (0xFF......) because :core holds plain ARGB ints
    // for the terminal, which has no Compose dependency. Narrowing here is the
    // single point where that crosses into Android's Int-colour APIs.
    setColor(EditorColorScheme.WHOLE_BACKGROUND, DroshPalette.BACKGROUND.toArgbInt())
    setColor(
        EditorColorScheme.LINE_NUMBER_BACKGROUND,
        DroshPalette.BACKGROUND.toArgbInt(),
    )
    setColor(EditorColorScheme.LINE_NUMBER, DroshPalette.TEXT_MUTED.toArgbInt())
    setColor(EditorColorScheme.LINE_NUMBER_CURRENT, DroshPalette.TEXT_SECONDARY.toArgbInt())

    // EditorColorScheme exposes foreground as a colour key too; the widget paints
    // ordinary text from it rather than from a separate setTextColor.
    setColor(EditorColorScheme.KEYWORD, DroshPalette.PRIMARY.toArgbInt())
    setColor(EditorColorScheme.LITERAL, DroshPalette.SUCCESS.toArgbInt())
    setColor(EditorColorScheme.COMMENT, DroshPalette.TEXT_MUTED.toArgbInt())
    setColor(EditorColorScheme.OPERATOR, DroshPalette.TEXT_SECONDARY.toArgbInt())
    setColor(EditorColorScheme.IDENTIFIER_NAME, DroshPalette.TEXT.toArgbInt())

    setColor(
        EditorColorScheme.SELECTED_TEXT_BACKGROUND,
        DroshPalette.SURFACE_HIGH.toArgbInt(),
    )
    setColor(EditorColorScheme.SELECTION_INSERT, DroshPalette.PRIMARY.toArgbInt())
}

/**
 * `:core` stores palette entries as `Long` in `0xAARRGGBB` form so the terminal
 * module can use them without a Compose dependency. Android's colour APIs all
 * take `Int`, and the top 32 bits are the alpha and red channels, so the
 * narrowing is a plain truncation rather than a shift.
 */
private fun Long.toArgbInt(): Int = toInt()
