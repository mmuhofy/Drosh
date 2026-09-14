package dev.drosh.ui.setup.onboarding.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshTextMuted

/**
 * "Drosh" — a stylized shell/terminal icon drawn with Compose Canvas.
 *
 * Three concentric arcs represent the shell's curved command line, with a
 * blinking cursor dot at the end. A subtle vertical float animation (4dp over
 * 3s ease-in-out repeat) gives life without distraction.
 *
 * Inspired by: github.com/termux/termux-app — app/src/main/res/drawable/ic_launcher_foreground.xml
 * Adapted for Drosh — dev.drosh
 */
@Composable
fun DroshLogo(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    tint: Color = DroshPrimary,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "drosh-float")
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "drosh-float-y",
    )

    val cursorAlphaTransition = rememberInfiniteTransition(label = "drosh-cursor")
    val cursorAlpha by cursorAlphaTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "drosh-cursor-alpha",
    )

    Box(
        modifier = modifier
            .size(size)
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val sizePx = size.toPx()
            val center = Offset(sizePx / 2f, sizePx / 2f)
            val radius = sizePx * 0.32f
            val strokeWidth = sizePx * 0.08f

            val floatOffset = Offset(0f, (offsetY - 0.5f) * 8f)

            for (i in 0..2) {
                val r = radius - i * (strokeWidth + 2)
                val path = Path().apply {
                    moveTo(center.x - r + floatOffset.y, center.y - r + floatOffset.y)
                     arcTo(
                        rect = androidx.compose.ui.geometry.Rect(
                            left = center.x - r - floatOffset.x,
                            top = center.y - r + floatOffset.y,
                            right = center.x + r - floatOffset.x,
                            bottom = center.y + r + floatOffset.y,
                        ),
                        startAngleDegrees = 135f,
                        sweepAngleDegrees = 270f,
                        forceMoveTo = true,
                    )
                }
                drawPath(
                    path = path,
                    color = if (i == 0) tint else DroshTextMuted.copy(alpha = 0.3f),
                    style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }

            val cursorR = radius - 2 * (strokeWidth + 2)
            val cursorAngle = Math.toRadians(270.0).toFloat()
            val cursorX = center.x + (cursorR * kotlin.math.cos(cursorAngle)).toFloat() + floatOffset.x
            val cursorY = center.y + (cursorR * kotlin.math.sin(cursorAngle)).toFloat() + floatOffset.y

            drawRect(
                color = tint.copy(alpha = cursorAlpha),
                topLeft = Offset(cursorX - strokeWidth * 0.3f, cursorY - strokeWidth * 0.3f),
                size = Size(strokeWidth * 0.6f, strokeWidth * 0.6f),
            )
        }
    }
}
