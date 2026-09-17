package dev.drosh.ui.setup.onboarding.scenes

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshOnPrimary
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshText
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.setup.onboarding.components.WormPageIndicator
import kotlinx.coroutines.delay

@Composable
fun WelcomeScene(
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    hasAnimated: Boolean,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .windowInsetsPadding(WindowInsets.statusBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            WormPageIndicator(
                pageCount = 3,
                currentPage = 0,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(modifier = Modifier.height(32.dp))

            TerminalIconAnimated(
                size = 80.dp,
                tint = DroshPrimary,
                animateIn = !hasAnimated,
            )

            Spacer(modifier = Modifier.height(32.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = buildAnnotatedString {
                        append("Welcome to ")
                        withStyle(SpanStyle(color = DroshPrimary)) {
                            append("Drosh")
                        }
                    },
                    style = TextStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Default,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 22.sp,
                        letterSpacing = 0.5.sp,
                    ),
                    color = DroshText,
                )

                Spacer(modifier = Modifier.height(8.dp))

                TypewriterText(
                    fullText = "Your phone is a Unix machine. Finally.",
                    charDelayMs = 28L,
                    onComplete = { },
                    textStyle = TextStyle(
                        fontFamily = androidx.compose.ui.text.font.FontStyle.Italic,
                        fontWeight = FontWeight.Normal,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        letterSpacing = 0.2.sp,
                        textAlign = TextAlign.Center,
                    ),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            PillButton(
                text = "Next",
                onClick = onNext,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
            )
        }
    }
}

@Composable
private fun TerminalIconAnimated(
    size: Dp,
    tint: androidx.compose.ui.graphics.Color,
    animateIn: Boolean,
) {
    val scale = remember { Animatable(0.9f) }
    val alpha = remember { Animatable(0f) }

    if (!animateIn) {
        LaunchedEffect(Unit) {
            alpha.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 300),
            )
            scale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 300),
            )
        }
    }

    val scaleFloat = scale.value
    val alphaFloat = alpha.value

    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer(alpha = alphaFloat)
            .graphicsLayer {
                scaleX = scaleFloat
                scaleY = scaleFloat
            },
        contentAlignment = Alignment.Center,
    ) {
        TerminalIconCanvas(
            size = size,
            tint = tint,
        )
    }
}

@Composable
private fun TerminalIconCanvas(
    size: Dp,
    tint: androidx.compose.ui.graphics.Color,
) {
    val density = LocalDensity.current
    val sizePx = with(density) { size.toPx() }

    Canvas(
        modifier = Modifier.size(size),
    ) {
        val center = Offset(sizePx / 2f, sizePx / 2f)
        val radius = sizePx * 0.30f
        val strokeWidth = sizePx * 0.09f

        for (i in 0..2) {
            val r = radius - i * (strokeWidth + 2)
            val path = Path().apply {
                moveTo(center.x - r, center.y - r)
                arcTo(
                    rect = androidx.compose.ui.geometry.Rect(
                        left = center.x - r,
                        top = center.y - r,
                        right = center.x + r,
                        bottom = center.y + r,
                    ),
                    startAngleDegrees = 135f,
                    sweepAngleDegrees = 270f,
                    forceMoveTo = true,
                )
            }
            drawPath(
                path = path,
                color = if (i == 0) tint else tint.copy(alpha = 0.3f),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }

        val cursorR = radius - 2 * (strokeWidth + 2)
        val cursorAngle = Math.toRadians(270.0).toFloat()
        val cursorX = center.x + (cursorR * kotlin.math.cos(cursorAngle)).toFloat()
        val cursorY = center.y + (cursorR * kotlin.math.sin(cursorAngle)).toFloat()

        drawRect(
            color = tint,
            topLeft = Offset(cursorX - strokeWidth * 0.3f, cursorY - strokeWidth * 0.3f),
            size = Size(strokeWidth * 0.6f, strokeWidth * 0.6f),
        )
    }
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = DroshPrimary,
            contentColor = DroshOnPrimary,
            disabledContainerColor = DroshPrimary.copy(alpha = 0.3f),
            disabledContentColor = DroshOnPrimary.copy(alpha = 0.5f),
        ),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            ),
        )
    }
}

@Composable
fun PillButtonWithIcon(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = DroshPrimary,
            contentColor = DroshOnPrimary,
            disabledContainerColor = DroshPrimary.copy(alpha = 0.3f),
            disabledContentColor = DroshOnPrimary.copy(alpha = 0.5f),
        ),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            ),
        )
        if (icon != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = DroshOnPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
