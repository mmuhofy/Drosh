package dev.drosh.ui.agent.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.ui.R
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshOnPrimary
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.LocalFontSet
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.components.GlassIconButton

/**
 * Shared pieces of the agent UI.
 *
 * Every interactive element here is at least [TOUCH_TARGET] tall, even when its
 * icon is 20dp. The icon is the affordance; the box is the target, and a 20dp one
 * is below the 48dp Material minimum and unreliable on a phone.
 */

/** 48dp — the Material minimum, for icon-only controls. */
val TOUCH_TARGET: Dp = 48.dp

/** 44dp — for labelled buttons, where the label makes the target obvious. */
val BUTTON_HEIGHT: Dp = 44.dp

/**
 * Animation length for the agent UI, in the 150–300ms band Material asks for.
 *
 * Public because it is a shared token, not a local: the tool rows and the selection
 * menu both animate, and a second copy of the number is a second value to keep in
 * step. One duration means a press, an expand and a menu all feel like the same app.
 */
const val MOTION_MS = 180

/**
 * The agent mark — a square head with a prompt on its face.
 *
 * A vector rather than an emoji, so it takes the theme's tint and is not
 * whatever the device's font renders at that codepoint.
 */
@Composable
fun DroshAgentMark(
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Icon(
        painter = painterResource(R.drawable.ic_agent_head),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(size),
    )
}

/** A small all-caps group label above a list section. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.9.sp,
        color = DroshTextMuted,
        modifier = modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * A filled button.
 *
 * 44dp rather than the 48dp touch minimum: 44 is still a comfortable tap target
 * and a full-width button at 48dp reads as a slab on a phone. One per screen, so
 * the primary action stays unambiguous.
 */
@Composable
fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    Box(
        modifier = modifier
            .heightIn(min = BUTTON_HEIGHT)
            .clip(RoundedCornerShape(11.dp))
            .background(if (enabled) {
                if (destructive) DroshError else DroshPrimary
            } else {
                DroshSurfaceHigh
            })
            .glassEdge(RoundedCornerShape(11.dp), strength = if (enabled) 0.6f else 0.8f)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) {
                if (destructive) Color(0xFF1A1A1A) else DroshOnPrimary
            } else {
                DroshTextMuted
            },
        )
    }
}

/** A flat button, for the secondary action beside a filled one. */
@Composable
fun FlatButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = DroshTextSecondary,
) {
    Box(
        modifier = modifier
            .heightIn(min = BUTTON_HEIGHT)
            .clip(RoundedCornerShape(11.dp))
            .background(DroshSurfaceHigh)
            .glassEdge(RoundedCornerShape(11.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = tint,
        )
    }
}

/**
 * An icon button in a top bar.
 *
 * A glass pill at the 48dp touch minimum rather than a bare icon: these sit over
 * a scrolling transcript, and a flat icon on a flat background gives no sign of
 * being pressable. Same component as the terminal top bar, so the two control
 * sets read as one.
 */
@Composable
fun IconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = DroshTextSecondary,
    enabled: Boolean = true,
) {
    GlassIconButton(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        tint = tint,
        enabled = enabled,
    )
}

/**
 * A collapsible row: one line when collapsed, everything when open.
 *
 * Animated rather than toggled, because the transition is what tells the user
 * their tap registered and which direction the content went.
 */
@Composable
fun CollapsibleRow(
    summary: @Composable RowScope.() -> Unit,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassRow(RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TOUCH_TARGET)
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Invoked directly in the Row rather than inside a nested Box: the
            // summary is declared against RowScope so callers can use weight() on
            // their own children, and a nested composable would not carry the
            // receiver through.
            summary()
            Icon(
                imageVector = DroshIcons.ChevronDown,
                contentDescription = null,
                tint = DroshTextMuted,
                modifier = Modifier.size(16.dp),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(MOTION_MS)) + expandVertically(tween(MOTION_MS)),
            exit = fadeOut(tween(MOTION_MS)) + shrinkVertically(tween(MOTION_MS)),
        ) {
            content()
        }
    }
}

/** Horizontal rule matching the terminal's block divider. */
@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(DroshOutline.copy(alpha = 0.5f)),
    )
}

/** A small status pill. Colour is always paired with text. */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

/** Monospaced output block, scrolling internally when long. */
@Composable
fun MonoBlock(
    text: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 240.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .glassRow(RoundedCornerShape(8.dp), raised = true)
            .padding(10.dp),
    ) {
        Text(
            text = text,
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            fontFamily = LocalFontSet.current.mono,
            color = DroshTextSecondary,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
}
