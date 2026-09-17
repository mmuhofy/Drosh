package dev.drosh.ui.setup.onboarding.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshTextMuted

@Composable
fun WormPageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
    activeColor: Color = DroshPrimary,
    inactiveColor: Color = DroshTextMuted,
    dotSize: Dp = 7.dp,
    activeDotWidth: Dp = 22.dp,
    wormHeight: Dp = 7.dp,
) {
    val spacing = 8.dp
    val wormOffset by animateFloatAsState(
        targetValue = currentPage.toFloat(),
        animationSpec = tween(durationMillis = 320),
    )

    val wormProgress = wormOffset - wormOffset.toInt()
    val fromPage = wormOffset.toInt().coerceIn(0, pageCount - 1)
    val toPage = (fromPage + 1).coerceIn(0, pageCount - 1)

    val dotPositions = List(pageCount) { index ->
        index.toFloat() * (activeDotWidth.value + spacing.value)
    }

    val wormStartPos = dotPositions.getOrElse(fromPage) { 0f }
    val wormEndPos = dotPositions.getOrElse(toPage) { 0f }

    val wormX = wormStartPos + (wormEndPos - wormStartPos) * wormProgress

    Box(
        modifier = modifier
            .height(wormHeight)
            .width(
                (dotPositions.lastIndex * (activeDotWidth.value + spacing.value) + activeDotWidth.value).dp
            ),
    ) {
        Row(
            modifier = Modifier
                .height(wormHeight)
                .align(Alignment.CenterStart),
        ) {
            repeat(pageCount) { index ->
                val isCurrent = index == currentPage
                val isActivePage = index == fromPage || index == toPage

                Box(
                    modifier = Modifier
                        .size(if (isCurrent || isActivePage) activeDotWidth else dotSize)
                        .clip(CircleShape)
                        .background(
                            color = if (isCurrent) activeColor else inactiveColor,
                            shape = CircleShape,
                        )
                        .graphicsLayer {
                            alpha = if (isCurrent) 1f else if (isActivePage && index != currentPage) 0.4f else 0.3f
                        },
                )
                if (index < pageCount - 1) {
                    Spacer(modifier = Modifier.width(spacing))
                }
            }
        }

        Box(
            modifier = Modifier
                .width(activeDotWidth)
                .height(wormHeight)
                .clip(RoundedCornerShape(50))
                .background(activeColor)
                .align(Alignment.TopStart)
                .offset {
                    androidx.compose.ui.unit.IntOffset(
                        x = wormX.toInt(),
                        y = 0,
                    )
                },
        )
    }
}
