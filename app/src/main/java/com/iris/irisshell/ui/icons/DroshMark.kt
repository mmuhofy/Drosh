package com.iris.irisshell.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Iris Shell monogram — a small vector mark that ships inside the topbar.
 *
 * Built as Canvas primitives so we never depend on emoji or raster assets.
 * The mark is a square with a centered dot ("iris" → "I") with a thin blue
 * accent stroke that matches MEMORYBANK.md §133 IrisPrimary = #3B82F6.
 *
 * The user rule forbids emoji as structural icons; this is a pure-vector
 * replacement for any raster logo.
 */
@Composable
fun IrisShellMark(
    modifier: Modifier = Modifier.size(20.dp),
    color: Color = Color(0xFF3B82F6), // IrisPrimary
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 2.dp.toPx()
        // Rounded square outline.
        val cornerRadius = 6.dp.toPx()
        drawRoundRect(
            color = color,
            size = size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius, cornerRadius),
            style = Stroke(width = strokeWidth),
        )
        // Inner accent dot represents the iris pupil; sits one inset from the
        // square outline so it reads at any density.
        val inset = size.minDimension * 0.35f
        drawCircle(
            color = color,
            radius = (size.minDimension - inset * 2) / 2f,
            center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f),
        )
    }
}

/**
 * Compose Icon-equivalent: tiny chevron used by the topbar fast-action buttons.
 */
@Composable
fun ChevronRight(
    modifier: Modifier = Modifier.size(16.dp),
    color: Color = Color(0xFFA0A0A0), // IrisTextSecondary
) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.5.dp.toPx()
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.35f, h * 0.25f)
            relativeLineTo(w * 0.30f, 0f)
            relativeLineTo(-w * 0.15f, h * 0.25f)
            relativeLineTo(w * 0.15f, 0f)
            relativeLineTo(-w * 0.15f, -h * 0.25f)
            relativeLineTo(-w * 0.30f, 0f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth),
        )
    }
}
