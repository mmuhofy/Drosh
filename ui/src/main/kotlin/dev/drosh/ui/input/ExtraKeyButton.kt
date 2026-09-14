package dev.drosh.ui.input

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.domain.input.ExtraKey
import dev.drosh.ui.DroshIcons

@Composable
fun ExtraKeyButton(
    key: ExtraKey,
    stuckActive: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember {
        MutableInteractionSource()
    }

    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()

    val active = stuckActive || pressed

    val background = when {
        stuckActive ->
            DroshPrimary.copy(alpha = 0.10f)

        pressed ->
            Color.White.copy(alpha = 0.13f)

        hovered ->
            Color.White.copy(alpha = 0.075f)

        else ->
            Color.Transparent
    }

    val glyphColor = when {
        stuckActive ->
            DroshPrimary

        active || hovered ->
            Color.White

        else ->
            DroshTextMuted
    }

    Box(
        modifier = modifier
            .size(
                width = 48.dp,
                height = 38.dp,
            )
            .hoverable(interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onTap,
                onLongClick = onLongPress,
            )
            .clip(RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier.matchParentSize(),
        ) {
            drawRoundRect(
                color = background,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                    20.dp.toPx(),
                    20.dp.toPx(),
                ),
            )
        }

        val arrow = when (key) {
            ExtraKey.Navigation.ARROW_UP ->
                DroshIcons.ArrowBigUp

            ExtraKey.Navigation.ARROW_DOWN ->
                DroshIcons.ArrowBigDown

            ExtraKey.Navigation.ARROW_LEFT ->
                DroshIcons.ArrowBigLeft

            ExtraKey.Navigation.ARROW_RIGHT ->
                DroshIcons.ArrowBigRight

            else -> null
        }

        if (arrow != null) {
            androidx.compose.foundation.Image(
                painter = rememberVectorPainter(image = arrow),
                contentDescription = key.displayLabel(),
                modifier = Modifier.size(17.dp),
                colorFilter = ColorFilter.tint(glyphColor),
            )
        } else {
            Text(
                text = key.displayGlyph(),
                color = glyphColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun ExtraKey.displayGlyph(): String =
    when (this) {
        is ExtraKey.Special -> name
        is ExtraKey.Text -> glyph

        is ExtraKey.Navigation -> when (this) {
            ExtraKey.Navigation.ESC -> "ESC"
            ExtraKey.Navigation.TAB -> "TAB"
            ExtraKey.Navigation.ARROW_LEFT,
            ExtraKey.Navigation.ARROW_RIGHT,
            ExtraKey.Navigation.ARROW_UP,
            ExtraKey.Navigation.ARROW_DOWN -> ""
            ExtraKey.Navigation.HOME -> "HOME"
            ExtraKey.Navigation.END -> "END"
            ExtraKey.Navigation.PAGE_UP -> "PgUp"
            ExtraKey.Navigation.PAGE_DOWN -> "PgDn"
        }
    }

private fun ExtraKey.displayLabel(): String =
    when (this) {
        is ExtraKey.Navigation -> when (this) {
            ExtraKey.Navigation.ARROW_UP -> "Up arrow"
            ExtraKey.Navigation.ARROW_DOWN -> "Down arrow"
            ExtraKey.Navigation.ARROW_LEFT -> "Left arrow"
            ExtraKey.Navigation.ARROW_RIGHT -> "Right arrow"
            else -> displayGlyph()
        }

        else -> displayGlyph()
    }