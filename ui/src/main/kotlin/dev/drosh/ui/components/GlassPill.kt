package dev.drosh.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted

/**
 * The glass pill, as a shared component.
 *
 * Lives here rather than staying private to `TerminalTopBar` because the agent
 * screens need the same affordance. Two copies of a press animation and a
 * backdrop-tint recipe drift apart within a release — the second one is written
 * slightly differently and nobody notices, because they are never on screen at
 * the same time.
 *
 * ## The blur is simulated
 *
 * Compose has no native backdrop blur, so "glass" here is a translucent surface
 * plus a top-edge highlight. Against a terminal that reads correctly; the
 * terminal-topbar version additionally slices a real screenshot of the terminal
 * beneath it, which these screens have no access to and do not need — they are
 * already a solid dark field.
 */
@Composable
fun GlassPill(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = GlassPillDefaults.Width,
    height: Dp = GlassPillDefaults.Height,
    /** Extra horizontal room when the pill carries a label. */
    labelled: Boolean = false,
    tint: Color? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    /**
     * Drawable resource, for the agent mark. Resolved here rather than by the
     * caller: `painterResource` is itself a composable call, and passing a Painter
     * in would push that requirement onto every call site.
     */
    drawableRes: Int? = null,
    label: String? = null,
) {
    var pressed by remember { mutableStateOf(false) }

    // Spring, not tween: the pill is a physical object under a finger, and a
    // linear ease-out reads as a tooltip rather than something pressed.
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "glassPillScale",
    )

    val shape: Shape = CircleShape
    val accent = tint
        ?: if (enabled) DroshText else DroshTextMuted

    Box(
        modifier = modifier
            .width(if (labelled) Dp.Unspecified else width)
            .defaultMinSize(minWidth = if (labelled) Dp.Unspecified else width, minHeight = height)
            .height(height)
            .scale(scale)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        DroshSurfaceHigh.copy(alpha = 0.86f),
                        DroshSurfaceHigh.copy(alpha = 0.62f),
                    ),
                ),
            )
            // A top-edge highlight is what reads as glass rather than as a grey
            // circle. Without it the pill is a flat chip.
            .border(width = 1.dp, color = Color.White.copy(alpha = 0.07f), shape = shape)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                        onClick()
                    },
                )
            }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        when {
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(GlassPillDefaults.IconSize),
            )

            drawableRes != null -> Icon(
                painter = painterResource(drawableRes),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(GlassPillDefaults.IconSize),
            )

            else -> Unit
        }

        if (label != null && labelled) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                fontSize = GlassPillDefaults.LabelSize,
                color = accent,
                maxLines = 1,
            )
            Spacer(Modifier.width(GlassPillDefaults.IconSize))
        }
    }
}

/** An icon-only pill sized to the 48dp touch minimum, for a bar's leading slot. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    enabled: Boolean = true,
) {
    GlassPill(
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier.size(GlassPillDefaults.TouchTarget),
        height = GlassPillDefaults.TouchTarget,
        width = GlassPillDefaults.TouchTarget,
        tint = tint,
        enabled = enabled,
        icon = icon,
    )
}

object GlassPillDefaults {
    /** Matches the terminal top bar's row so the two read as one control set. */
    val Height: Dp = 36.dp
    val Width: Dp = 36.dp
    val IconSize: Dp = 22.dp
    val LabelSize = 12.sp

    /** 48dp — the Material minimum; the pill is smaller than this on purpose. */
    val TouchTarget: Dp = 48.dp

    /**
     * Accent for a pill in an active or busy state.
     *
     * A function, not a val: the palette tokens are `@Composable` getters over
     * `LocalDroshColors`, so reading one from an object initialiser has no
     * composition to read it from. Same reason `GlassPill`'s own defaults are all
     * plain `Dp`.
     */
    val ActiveTint: Color
        @Composable @ReadOnlyComposable get() = DroshPrimary
}
