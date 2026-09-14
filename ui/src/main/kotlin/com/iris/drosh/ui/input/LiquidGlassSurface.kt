package com.iris.irisshell.ui.input

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier,
    ) {
        val radius = 26.dp.toPx()

        // HTML:
        // background: rgba(30,32,36,.38)
        drawRoundRect(
            color = Color(0xFF1E2024).copy(alpha = 0.38f),
            cornerRadius = CornerRadius(
                radius,
                radius,
            ),
        )

        // glass:before
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.07f),
                    Color.Transparent,
                    Color.Transparent,
                    Color.White.copy(alpha = 0.035f),
                ),
            ),
            cornerRadius = CornerRadius(
                radius - 1.dp.toPx(),
                radius - 1.dp.toPx(),
            ),
        )

        // inset top highlight
        drawRoundRect(
            color = Color.White.copy(alpha = 0.16f),
            style = Stroke(
                width = 1.dp.toPx(),
            ),
            cornerRadius = CornerRadius(
                radius,
                radius,
            ),
        )

        // subtle bottom density
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    Color.Black.copy(alpha = 0.06f),
                ),
            ),
            cornerRadius = CornerRadius(
                radius,
                radius,
            ),
        )
    }
}