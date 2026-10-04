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

package dev.drosh.terminal

import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TextStyle
import kotlin.math.max
import kotlin.math.min

/**
 * Derives an ambient colour from what is on the terminal screen.
 *
 * Apple describes Liquid Glass as taking its colour from the surrounding
 * content. For a keyboard, that surrounding content is not reachable as
 * pixels — an IME cannot read another app's window — but in a terminal it is
 * available as *structured data*. Every cell carries its style bits, including a
 * foreground palette index or a truecolour value. So the same idea Apple has
 * for images can be had here for free, from `mStyle`.
 *
 * Deliberately conservative, because the alternative is a keyboard that
 * flickers arbitrary colours:
 *
 *  - Only the last few rows, weighted towards the newest, since that is what
 *    the user is looking at.
 *  - Cells using the default foreground contribute nothing. A plain prompt or
 *    uncoloured output must not tint anything.
 *  - The result is desaturated and clamped to a narrow band. A garish terminal
 *    theme must not produce a garish keyboard.
 *  - The alternate screen buffer is skipped entirely: a TUI paints the whole
 *    screen with its own palette, which would be noise rather than a signal.
 */
class AmbientTintCalculator {

    private companion object {
        /** Rows at the bottom of the screen to consider. */
        const val ROWS_TO_SAMPLE = 6

        /** Below this many coloured cells the screen is treated as neutral. */
        const val MIN_COLOURED_CELLS = 4

        /** Newest rows count for more. */
        const val ROW_WEIGHT_STEP = 1.6f

        /** Saturation ceiling. A terminal theme should not tint the keyboard. */
        const val MAX_SATURATION = 0.30f

        /** Channel range the result is confined to, so it stays subtle. */
        const val MIN_CHANNEL = 92
        const val MAX_CHANNEL = 196

        /** Cells sampled per row; the middle of the line is representative. */
        const val CELLS_PER_ROW = 48
    }

    /**
     * @return an ARGB colour to tint towards, or null when the screen is
     *   neutral and the caller should leave the surface alone.
     */
    fun compute(screen: TerminalBuffer, colors: TerminalColors, alternateBuffer: Boolean): Int? {
        // A TUI owns the whole screen. Its palette says nothing about the shell
        // the user is working in.
        if (alternateBuffer) return null

        val rows = min(ROWS_TO_SAMPLE, screen.mScreenRows)
        if (rows <= 0) return null

        var redSum = 0f
        var greenSum = 0f
        var blueSum = 0f
        var weightSum = 0f
        var coloured = 0

        for (offset in 0 until rows) {
            val row = screen.mScreenRows - 1 - offset
            val line = screen.mLines[row] ?: continue
            // Newest row counts most: it is what the user just produced.
            val rowWeight = ROW_WEIGHT_STEP.pow(offset)

            val limit = min(min(CELLS_PER_ROW, line.spaceUsed), screen.mColumns)
            for (x in 0 until limit) {
                if (line.mText[x] == ' ') continue
                val style = line.mStyle[x]
                val fgIndex = TextStyle.decodeForeColor(style)
                // The default foreground means "whatever the theme's text
                // colour is", which is not a signal about anything.
                if (fgIndex == TextStyle.COLOR_INDEX_FOREGROUND) continue

                val argb = colors.resolveColor(fgIndex) ?: continue
                val a = (argb ushr 24) and 0xFF
                if (a < 0x40) continue

                redSum += ((argb shr 16) and 0xFF) * rowWeight
                greenSum += ((argb shr 8) and 0xFF) * rowWeight
                blueSum += (argb and 0xFF) * rowWeight
                weightSum += rowWeight
                coloured++
            }
        }

        if (coloured < MIN_COLOURED_CELLS || weightSum <= 0f) return null

        var red = redSum / weightSum
        var green = greenSum / weightSum
        var blue = blueSum / weightSum

        // Pull toward the luminance: keeps the hue, drops the shout.
        val grey = 0.299f * red + 0.587f * green + 0.114f * blue
        val saturation = (maxOf(red, green, blue) - minOf(red, green, blue)) / 255f
        val pull = (1f - min(1f, saturation / MAX_SATURATION)).coerceIn(0f, 1f)
        red = grey + (red - grey) * pull
        green = grey + (green - grey) * pull
        blue = grey + (blue - grey) * pull

        red = red.coerceIn(MIN_CHANNEL.toFloat(), MAX_CHANNEL.toFloat())
        green = green.coerceIn(MIN_CHANNEL.toFloat(), MAX_CHANNEL.toFloat())
        blue = blue.coerceIn(MIN_CHANNEL.toFloat(), MAX_CHANNEL.toFloat())

        return (0xFF shl 24) or
            (red.toInt() shl 16) or
            (green.toInt() shl 8) or
            blue.toInt()
    }

    private fun Float.pow(exponent: Int): Float {
        var result = 1f
        repeat(exponent) { result *= this }
        return result
    }
}
