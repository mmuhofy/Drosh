package dev.drosh.ui.agent.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.rememberHazeState
import dev.drosh.design.system.DroshOnPrimary
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.ui.DroshIcons

/**
 * The agent screens' control surfaces.
 *
 * ## Why this is not `GlassPill`
 *
 * `GlassPill` is shared with the terminal top bar, and it draws a 1dp
 * `Color.White.copy(alpha = 0.07f)` outline and scales to 0.88 on press. Both are
 * wrong here and both are right there.
 *
 * The outline is the thing a borderless control should not have. At 7% white it
 * is nearly invisible on the dark field, then shows up as a grey ring wherever
 * the transcript behind it is light — so the pill's edge changes as the text
 * scrolls past. A floating material has to keep its edge; that edge is the only
 * thing telling the user where the control ends.
 *
 * The scale is the instability. A graphics transform moves nothing in layout, so
 * it cannot shift a neighbour — but it does shrink the target under the finger
 * for as long as the finger is down, and at 0.88 the pill visibly recoils on
 * every press. Press feedback here is a fill change only: the box a finger lands
 * on is the same size before, during and after. Nothing on screen moves because
 * of a tap.
 *
 * ## Why the touch box is 48dp and the pill is 34dp
 *
 * 34dp is what the row reads at; 48dp is what a finger needs, so they are
 * separate boxes. Touch boxes tile edge to edge with no gap, so a tap cannot land
 * in a dead zone between two pills, and the 7dp of slack inside each box becomes
 * the visual gap between pills.
 */
object AgentPillDefaults {

    /** The drawn pill. */
    val Height: Dp = 34.dp
    val IconSize: Dp = 16.dp
    val ChevronSize: Dp = 14.dp
    val LabelSize = 13.sp

    /**
     * The clickable box for an icon-only pill.
     *
     * Material's 48dp minimum. A 34dp circle is not a target; the icon is the
     * affordance and the box is the target.
     */
    val IconTouchTarget: Dp = 48.dp

    /** Horizontal room inside a labelled pill. */
    val LabelPadding: Dp = 14.dp

    /** Motion length, in the 150–300ms band Material asks for. */
    const val MOTION_MS = 180

    /**
     * Blur radius for the glass fill.
     *
     * Strong enough that transcript text behind a pill is unreadable, which is the
     * point: a surface you can read through is not a surface.
     */
    val BlurRadius: Dp = 20.dp

    val Shape: Shape = RoundedCornerShape(17.dp)

    /**
     * The resting fill.
     *
     * Derived from [DroshSurfaceHigh] rather than a literal white at 8%, so it
     * works in the light theme. `Color.White.copy(alpha = 0.08f)` over a
     * near-white background is invisible — the surface would not be there at all
     * and the row would look like loose text with gaps in it.
     */
    val Fill: Color
        @Composable @ReadOnlyComposable get() = DroshSurfaceHigh.copy(alpha = 0.55f)

    /** Under a finger: brighter and more opaque. Same box. */
    val FillPressed: Color
        @Composable @ReadOnlyComposable get() = DroshSurfaceHigh.copy(alpha = 0.85f)

    /** Quieter, for a control that is present but not the point. */
    val FillGhost: Color
        @Composable @ReadOnlyComposable get() = DroshSurfaceHigh.copy(alpha = 0.35f)

    val FillGhostPressed: Color
        @Composable @ReadOnlyComposable get() = DroshSurfaceHigh.copy(alpha = 0.60f)
}

/**
 * The Haze state an agent screen shares between its content and its controls.
 *
 * One state, created by the screen: the scrolling content is the source, every
 * floating control is an effect. A screen that gave each control its own state
 * would render the backdrop once per pill — Haze coalesces sources that share a
 * state, so sharing costs one offscreen render however many pills are on screen.
 */
@Immutable
class AgentGlass(val state: HazeState)

/** Convenience for the common case: a screen that needs its own glass state. */
@Composable
fun rememberAgentGlass(): AgentGlass = AgentGlass(rememberHazeState())

/**
 * The style every agent glass surface shares.
 *
 * The tint under the blur is load-bearing, not decoration. A 20dp blur over a
 * dark field averages to near-black, and a near-black surface has no edge against
 * the transcript — which is the entire reason the control floats rather than
 * sitting inline in the flow.
 */
@Composable
fun agentGlassStyle(): HazeStyle = HazeStyle(
    tint = HazeTint(DroshSurfaceHigh.copy(alpha = 0.30f)),
    blurRadius = AgentPillDefaults.BlurRadius,
)

/** The shared glass, or null when a control is previewed on its own. */
val LocalAgentGlass = staticCompositionLocalOf<AgentGlass?> { null }

@Composable
fun ProvideAgentGlass(glass: AgentGlass, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAgentGlass provides glass, content = content)
}

/**
 * The blur, when there is something to blur.
 *
 * A control previewed outside a screen gets the fill and no blur rather than a
 * crash, so its shape is still reviewable on its own.
 */
@Composable
private fun Modifier.glassFill(glass: AgentGlass?, fill: Color): Modifier {
    val state = glass?.state
    return if (state == null) {
        background(fill)
    } else {
        hazeEffect(state, agentGlassStyle()).background(fill)
    }
}

/**
 * `clickable` with the ripple suppressed, wired to a supplied interaction source.
 *
 * The default indication ripples within the pill's bounds. The fill already
 * carries the press state here, and a ripple on top answers the same tap a second
 * time on a different clock.
 */
private fun Modifier.pressable(
    interaction: MutableInteractionSource,
    enabled: Boolean,
    onClick: () -> Unit,
): Modifier = if (enabled) {
    clickable(interactionSource = interaction, indication = null, onClick = onClick)
} else {
    this
}

/**
 * A labelled pill: text, an optional leading icon, an optional chevron.
 *
 * Height is fixed rather than derived from the label. A pill that resizes with its
 * content is what makes a control row feel unstable — swapping "Agent" for a long
 * model name must not resize the thing the user is about to press. The label
 * truncates instead.
 */
@Composable
fun AgentPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = label,
    leadingIcon: ImageVector? = null,
    trailingChevron: Boolean = false,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val glass = LocalAgentGlass.current

    val fill by animateColorAsState(
        targetValue = when {
            !enabled -> AgentPillDefaults.FillGhost
            primary -> if (pressed) DroshPrimary.copy(alpha = 0.82f) else DroshPrimary
            pressed -> AgentPillDefaults.FillPressed
            else -> AgentPillDefaults.Fill
        },
        animationSpec = tween(AgentPillDefaults.MOTION_MS),
        label = "agentPillFill",
    )

    val contentColor = when {
        !enabled -> DroshTextMuted
        primary -> DroshOnPrimary
        else -> DroshText
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = AgentPillDefaults.Height)
            .clip(AgentPillDefaults.Shape)
            .glassFill(glass, fill)
            .semantics { this.contentDescription = contentDescription }
            .pressable(interaction, enabled, onClick)
            .padding(horizontal = AgentPillDefaults.LabelPadding, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(AgentPillDefaults.IconSize),
                )
                Spacer(Modifier.width(6.dp))
            }

            Text(
                text = label,
                fontSize = AgentPillDefaults.LabelSize,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (trailingChevron) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = DroshIcons.ChevronDown,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.5f),
                    modifier = Modifier.size(AgentPillDefaults.ChevronSize),
                )
            }
        }
    }
}

/**
 * An icon-only pill.
 *
 * The 34dp circle is drawn inside a 48dp box rather than being 48dp itself, so
 * every pill in a row has the same hit area whether or not it carries a label. A
 * row mixing 34dp and 48dp targets is a row where the distance between two buttons
 * depends on which buttons they are.
 */
@Composable
fun AgentIconPill(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = DroshText,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val glass = LocalAgentGlass.current

    val fill by animateColorAsState(
        targetValue = if (enabled && pressed) {
            AgentPillDefaults.FillPressed
        } else {
            AgentPillDefaults.Fill
        },
        animationSpec = tween(AgentPillDefaults.MOTION_MS),
        label = "agentIconPillFill",
    )

    Box(
        modifier = modifier
            .size(AgentPillDefaults.IconTouchTarget)
            .semantics { this.contentDescription = contentDescription }
            .pressable(interaction, enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(AgentPillDefaults.Height)
                .clip(CircleShape)
                .glassFill(glass, fill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) tint else DroshTextMuted,
                modifier = Modifier.size(AgentPillDefaults.IconSize),
            )
        }
    }
}

/**
 * A row of pills.
 *
 * Zero spacing, deliberately: each pill already carries 7dp of slack inside its
 * own touch box, so the visual gap is already 14dp and adding more would make the
 * row drift away from the mockup's rhythm.
 */
@Composable
fun AgentPillRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        content = content,
    )
}

/** A rule between menu groups, matched to the surface rather than outlined. */
@Composable
fun AgentMenuDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(DroshText.copy(alpha = 0.08f)),
    )
}
