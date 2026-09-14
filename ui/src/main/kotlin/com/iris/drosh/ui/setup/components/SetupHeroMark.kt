package com.iris.irisshell.ui.setup.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.iris.irisshell.ui.setup.theme.SetupPalette

/**
 * Hero mark used at the top of `BootstrapStepperScreen` and
 * `OnboardingScreen`.
 *
 * Renders a 64dp rounded square with an inner "P" stylized as a 3-axis
 * terminal — pure Compose Canvas, no icons-extended dep.
 */
@Composable
fun SetupHeroMark(sizeDp: Dp = 64.dp) {
    Canvas(
        modifier = Modifier.size(sizeDp),
    ) {
        val w = this.size.width
        val h = this.size.height

        val cornerRadius = w * 0.18f
        val glyphTop = h * 0.22f
        val glyphBottom = h * 0.78f
        val leading = w * 0.32f
        val barWidth = w * 0.05f
        val crossY = h * 0.50f
        val crossX = w * 0.62f
        val crossLength = w * 0.18f

        drawRoundRect(
            color = SetupPalette.Surface,
            topLeft = Offset.Zero,
            size = Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                cornerRadius,
                cornerRadius,
            ),
        )

        drawRoundRect(
            color = SetupPalette.Primary,
            topLeft = Offset(barWidth * 0.25f, glyphTop),
            size = Size(barWidth, glyphBottom - glyphTop),
        )

        val upper = Path().apply {
            moveTo(leading, glyphTop)
            cubicTo(
                crossX - crossLength, glyphTop,
                crossX, glyphTop + (crossY - glyphTop) * 0.45f,
                crossX, crossY,
            )
        }
        val lower = Path().apply {
            moveTo(leading + barWidth * 4, glyphBottom)
            cubicTo(
                crossX - crossLength, glyphBottom,
                crossX, glyphBottom - (glyphBottom - crossY) * 0.45f,
                crossX, crossY,
            )
        }
        val upperLower = Path().apply {
            addPath(upper)
            addPath(lower)
        }
        drawPath(
            path = upperLower,
            color = SetupPalette.Primary,
            style = Stroke(
                width = barWidth * 0.9f,
                cap = StrokeCap.Round,
            ),
        )

        val promptY = h * 0.86f
        val promptLeft = w * 0.18f
        val promptRight = w * 0.78f
        val promptPath = Path().apply {
            moveTo(promptLeft, promptY)
            lineTo(promptRight, promptY)
        }
        drawPath(
            path = promptPath,
            color = SetupPalette.TextMuted,
            style = Stroke(
                width = barWidth * 0.4f,
                cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
            ),
        )

        // Subtle glow halo behind the rounded square.
        drawRoundRect(
            color = Color.White.copy(alpha = 0.03f),
            topLeft = Offset.Zero,
            size = Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                cornerRadius,
                cornerRadius,
            ),
            blendMode = BlendMode.Plus,
        )
    }
}
