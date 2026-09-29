package dev.drosh.ui.splash

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshBorderSubtle
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.ui.icons.DroshMark
import dev.drosh.ui.setup.onboarding.components.TypewriterText
import kotlinx.coroutines.delay

/**
 * What the app shows while it works out where to go.
 *
 * This replaced an empty background, so a cold start on a slow device was a
 * black rectangle with nothing to say that anything was happening.
 *
 * The lines cycle rather than ending after one. The point is to show the app is
 * alive and say what it is, and on a fast launch there is not time to read a
 * sentence once, let alone type it. [onFinished] waits out a minimum hold, so
 * this is never a flash.
 */
private val LINES = listOf(
    "Your phone is a Unix machine.",
    "A real shell, running on the device.",
    "Terminal, editor and agent, together.",
)

private const val MINIMUM_HOLD_MS = 1100L
private const val AFTER_TYPING_MS = 1500L
private const val TYPED_CHAR_MS = 40L

@Composable
fun SplashScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var lineIndex by remember { mutableIntStateOf(0) }
    var typed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(MINIMUM_HOLD_MS)
        onFinished()
    }

    // Rotate through the lines.
    LaunchedEffect(typed) {
        if (!typed) return@LaunchedEffect
        delay(AFTER_TYPING_MS)
        typed = false
        lineIndex = (lineIndex + 1) % LINES.size
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(horizontal = 44.dp),
        ) {
            BreathingMark()

            Text(
                text = "Drosh",
                color = DroshText,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )

            // Fixed height so the block below does not dance while characters
            // arrive, and the text sits left so it grows from one origin.
            Box(
                modifier = Modifier
                    .height(20.dp)
                    .fillMaxWidth(),
                contentAlignment = Alignment.CenterStart,
            ) {
                // Changing the text is enough to restart the typewriter: it keys
                // its own effect on fullText.
                TypewriterText(
                    fullText = LINES[lineIndex],
                    charDelayMs = TYPED_CHAR_MS,
                    onComplete = { typed = true },
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        color = DroshTextMuted,
                        fontSize = 12.sp,
                    ),
                )
            }

            ProgressHairline()
        }
    }
}

/**
 * The mark pulses inside a soft glow, and a hairline beneath it fills and
 * empties. Together they mean something is happening even during the first
 * second, before a single character is typed.
 */
@Composable
private fun BreathingMark() {
    val transition = rememberInfiniteTransition(label = "splash")

    val pulse by transition.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "markScale",
    )

    val glow by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(1700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "markGlow",
    )

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(DroshPrimary.copy(alpha = glow * 0.16f)),
        )
        DroshMark(
            modifier = Modifier
                .size(72.dp)
                .scale(pulse),
            color = DroshPrimary,
        )
    }
}

@Composable
private fun ProgressHairline() {
    val transition = rememberInfiniteTransition(label = "hairline")
    val fill by transition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.86f,
        animationSpec = infiniteRepeatable(
            animation = tween(1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "hairlineFill",
    )

    Spacer(modifier = Modifier.height(4.dp))

    Box(
        modifier = Modifier
            .width(132.dp)
            .height(2.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(DroshBorderSubtle.copy(alpha = 0.5f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fill)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(DroshPrimary.copy(alpha = 0.8f)),
        )
    }
}
