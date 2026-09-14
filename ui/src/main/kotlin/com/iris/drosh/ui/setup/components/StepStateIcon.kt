package com.iris.irisshell.ui.setup.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.iris.irisshell.domain.terminal.StepState
import com.iris.irisshell.ui.setup.theme.SetupPalette

/**
 * Visual marker for a bootstrap step's current state.
 *
 * - Pending:  hollow circle in [SetupPalette.TextDisabled], dashed border
 * - Active:   filled circle with a blue Gaussian halo that pulses (1100ms)
 * - Done:     blue check mark drawn as a Canvas path
 * - Failed:   red X drawn as a Canvas path
 *
 * Drawn entirely with Canvas primitives — keeps APK small (no icons-extended dep).
 */
@Composable
fun StepStateIcon(
    state: StepState,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
) {
    val infinite = rememberInfiniteTransition(label = "step-icon")
    val pulse by infinite.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-alpha",
    )

    Box(modifier = modifier.size(size + 8.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size + 8.dp)) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val ringRadius = (size.toPx() / 2f) + 5f

            when (state) {
                StepState.Pending -> {
                    drawCircle(
                        color = SetupPalette.TextDisabled,
                        radius = size.toPx() / 2.6f,
                        center = center,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)),
                        ),
                        alpha = 0.5f,
                    )
                }
                StepState.Active -> {
                    drawCircle(
                        color = SetupPalette.PulseHalo,
                        radius = ringRadius * pulse,
                        center = center,
                        alpha = 0.2f * pulse,
                    )
                    drawCircle(
                        color = SetupPalette.Primary,
                        radius = size.toPx() / 2.2f,
                        center = center,
                    )
                }
                StepState.Done -> {
                    drawCircle(
                        color = SetupPalette.Primary.copy(alpha = 0.15f),
                        radius = size.toPx() / 1.9f,
                        center = center,
                    )
                    val cx = center.x
                    val cy = center.y
                    val r = size.toPx() / 4.2f
                    val path = Path().apply {
                        moveTo(cx - r, cy)
                        lineTo(cx - r / 2.2f, cy + r / 1.6f)
                        lineTo(cx + r, cy - r / 1.6f)
                    }
                    drawPath(
                        path = path,
                        color = SetupPalette.Primary,
                        style = Stroke(
                            width = 2.5.dp.toPx(),
                            cap = StrokeCap.Round,
                        ),
                    )
                }
                StepState.Failed -> {
                    drawCircle(
                        color = SetupPalette.Error.copy(alpha = 0.15f),
                        radius = size.toPx() / 1.9f,
                        center = center,
                    )
                    val cx = center.x
                    val cy = center.y
                    val r = size.toPx() / 3f
                    val path = Path().apply {
                        moveTo(cx - r, cy - r)
                        lineTo(cx + r, cy + r)
                        moveTo(cx + r, cy - r)
                        lineTo(cx - r, cy + r)
                    }
                    drawPath(
                        path = path,
                        color = SetupPalette.Error,
                        style = Stroke(
                            width = 2.5.dp.toPx(),
                            cap = StrokeCap.Round,
                        ),
                    )
                }
            }
        }
    }
}
