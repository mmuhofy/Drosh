package dev.drosh.ui.agent.components

import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.rememberHazeState
import dev.drosh.design.system.DroshOnPrimary
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshSurfaceLow
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.domain.agent.ChatMessage
import dev.drosh.domain.agent.ToolCallState
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

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

    /**
     * The drawn pill.
     *
     * 40 rather than 34. At 34 the row read as chrome around the transcript rather
     * than as controls — the glyphs were legible but the boxes were not obviously
     * tappable, and a row of things that look decorative is a row people hesitate
     * over. Still one fixed height for every pill, which is the part that matters:
     * the size never depends on the label.
     */
    // The same box the terminal's own pills draw: 44 high, a 22 glyph, a
    // fully-rounded capsule. The agent's pills used to be their own size — 40
    // with a 19 glyph and a 17dp radius — which made the two screens look like
    // two apps that happened to share a palette.
    val Height: Dp = 44.dp
    val IconSize: Dp = 22.dp
    val ChevronSize: Dp = 16.dp
    val LabelSize = 14.sp

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
    // The terminal's own blur radius, so the glass reads the same in both
    // places: strong enough that nothing behind a pill is legible.
    val BlurRadius: Dp = 44.dp

    /** Fully rounded, as the terminal's pills are. */
    val Shape: Shape = RoundedCornerShape(percent = 50)

    /**
     * The drawn tool pill, inside its own touch box.
     *
     * Smaller than [Height] on purpose: a tool call is one line among a
     * paragraph of prose, and a 44dp capsule in the middle of an answer is a
     * slab. 40dp is still a comfortable target, and the transcript row keeps the
     * whole pill as its hit area.
     */
    val ToolHeight: Dp = 40.dp
    val ToolIconSize: Dp = 15.dp
    val ToolLabelSize = 12.5.sp

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
/**
 * The glass style: the blur, and nothing else.
 *
 * The tint used to live here as well, which made a second surface — the haze
 * painted one and [Modifier.glassSurface] another. The fill is one flat colour
 * now, and the style is asked only for the blur.
 */
fun agentGlassStyle(): HazeStyle = HazeStyle(
    // Named, and null: the library offers this style twice — one tint, a list of
    // tints — and a call that names neither is a call the compiler cannot choose
    // between.
    tint = null,
    blurRadius = AgentPillDefaults.BlurRadius,
)

/** The shared glass, or null when a control is previewed on its own. */
val LocalAgentGlass = staticCompositionLocalOf<AgentGlass?> { null }

@Composable
fun ProvideAgentGlass(glass: AgentGlass, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAgentGlass provides glass, content = content)
}

/**
 * The agent's surface.
 *
 * ## Two surfaces, not one
 *
 * There are exactly two glass surfaces in the whole agent UI: the pill row at
 * the top of a chat and the composer under it. They are the two things that
 * float over content rather than sitting inside it, and they are the two a user
 * reaches for.
 *
 * Everything else — a tool pill in a transcript, a reply box, an approval card —
 * is a plain opaque fill. Glass in the middle of a conversation turns the
 * conversation into a gallery: every row has an edge of its own, the eye has to
 * re-decide what is a control on each line, and the thing the user is reading
 * sits behind four layers of translucency. A transcript should read as text.
 *
 * So [edged] is the whole switch. It is true in two places and nowhere else:
 *
 *  - **true** — blur, translucent fill, gradient hairline. Floating chrome.
 *  - **false** — one flat [DroshSurfaceHigh], no blur, no edge. Flow content.
 *
 * ## The blur is the depth
 *
 * A flat translucent colour over a blur is what a surface over a surface looks
 * like, and the blur is the part that does the work. The blur is Haze because
 * the alternative — sampling the backdrop into a bitmap — is what the
 * terminal's selection menu does, and it cannot keep up with a scrolling
 * transcript.
 */
@Composable
fun Modifier.glassSurface(
    glass: AgentGlass?,
    pressed: Boolean,
    primary: Boolean = false,
    shape: Shape = AgentPillDefaults.Shape,
    /**
     * Whether this is one of the two floating glass surfaces.
     *
     * Off everywhere but the top pill row and the composer: an outline on every
     * surface is a surface that looks like a form field — a card with a border
     * reads as something to fill in, and the whole screen becomes a stack of
     * boxes. The edge is a control's feature; a pill is a control, a card is
     * a card.
     */
    edged: Boolean = false,
    /**
     * Replaces the fill outright.
     *
     * The composer passes [Color.Transparent]: it is glass-edged but unfilled,
     * so the transcript runs under it and the shadow alone carries the box.
     * A filled composer is the thing that made the input look welded to the
     * bottom of the screen instead of floating over it.
     */
    fill: Color? = null,
): Modifier {
    // Only an *edged* surface reads its backdrop. Everywhere else there is no
    // blur to sit on, so asking for one would cost an offscreen render of the
    // transcript per frame for something nobody can see.
    val state = if (edged) glass?.state else null

    val base = fill ?: when {
        // Accent-filled, not a second glass surface: "Yeni" is the one filled
        // control on the agent's list screen, and the accent is what makes it
        // the one filled control. A surface-filled pill here reads as chrome
        // rather than as the primary action.
        primary -> DroshPrimary
        state != null -> DroshSurfaceHigh.copy(alpha = AGENT_FILL_GLASS)
        pressed -> DroshSurfaceHigh.copy(alpha = AGENT_FILL_PRESSED)
        else -> DroshSurfaceHigh
    }

    return this
        .clip(shape)
        // The blur alone, with no tint of its own: the fill below is the
        // surface, and a tint in the haze style would be a second one.
        .then(
            if (state == null) {
                Modifier
            } else {
                Modifier.hazeEffect(state, agentGlassStyle())
            },
        )
        .background(base)
        .then(
            if (edged) {
                Modifier.glassEdge(
                    shape = shape,
                    strength = when {
                        primary -> 0.6f
                        pressed -> 1.35f
                        else -> 1f
                    },
                )
            } else {
                Modifier
            },
        )
}

/** The fill a floating surface uses: light, because the blur is separating. */
private const val AGENT_FILL_GLASS = 0.28f

/** The press on a flat fill. A shade down, never a new colour. */
private const val AGENT_FILL_PRESSED = 0.72f

/**
 * The light on the glass edge, as (position, alpha) along the top-left -> bottom-right
 * diagonal. Same stops as the terminal top bar, so both screens carry one edge.
 *
 * Bright at the top-left crest, nearly gone through the middle, a softer second
 * catch at the bottom-right. The floor is never 0: a little rim all the way round
 * keeps the silhouette readable on a bright transcript.
 */
private val GLASS_EDGE_STOPS = arrayOf(
    0.00f to 0.42f,
    0.30f to 0.14f,
    0.50f to 0.09f,
    0.70f to 0.14f,
    1.00f to 0.30f,
)

/**
 * A fake liquid-glass edge: a diagonal gradient stroke that follows [shape].
 *
 * It strokes the shape's own outline at double width and clips to the shape, which
 * leaves exactly [width] inside -- so it cannot disagree with the clip, whatever
 * the shape is. Drawn after the content so nothing opaque inside can cover it.
 *
 * [strength] scales every stop: quiet rows use under 1, a pressed control more.
 */
fun Modifier.glassEdge(
    shape: Shape,
    width: Dp = 1.dp,
    strength: Float = 1f,
): Modifier = this
    .clip(shape)
    .drawWithCache {
        val strokePx = width.toPx()
        val outline = shape.createOutline(size, layoutDirection, this)
        val brush = Brush.linearGradient(
            colorStops = Array(GLASS_EDGE_STOPS.size) { i ->
                GLASS_EDGE_STOPS[i].first to
                    Color.White.copy(alpha = (GLASS_EDGE_STOPS[i].second * strength).coerceIn(0f, 1f))
            },
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        onDrawWithContent {
            drawContent()
            if (strokePx > 0f) {
                drawOutline(outline, brush, style = Stroke(width = strokePx * 2f))
            }
        }
    }

/**
 * A list row / card surface: one flat fill, and no edge.
 *
 * For things that sit in a sheet or in the flow rather than floating over the
 * transcript: they do not need a backdrop, only to read as the same material.
 * [raised] is the lifted fill — a selected row, a choice, an input.
 *
 * No outline. Every card on a screen carrying one turns the screen into a
 * stack of boxes, and a card that reads as a box reads as a form field. The
 * edge is a control's feature and the pills keep theirs.
 *
 * Clip first, so a ripple or [clickable] after this stays inside the shape.
 */
@Composable
fun Modifier.glassRow(shape: Shape, raised: Boolean = false): Modifier {
    // DroshSurfaceVariant and DroshSurfaceLow are composable getters over the
    // theme, so this has to be @Composable too.
    val fill = if (raised) DroshSurfaceVariant else DroshSurfaceLow
    return this
        .clip(shape)
        .background(fill)
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
    /**
     * Where the label sits inside the pill.
     *
     * Needed because a pill that fills its share of a row has to be able to hold its
     * content at the left instead of centring it, or the label drifts to the middle
     * of the row as the text changes length.
     */
    contentAlignment: Alignment = Alignment.Center,
    /**
     * Whether this pill is one of the two floating glass surfaces.
     *
     * True for a control row that floats over content. False for anything drawn
     * inside a transcript: a glass chip halfway down a conversation reads as
     * chrome sitting in the middle of somebody's prose.
     */
    edged: Boolean = true,
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
            .glassSurface(glass, pressed, primary, edged = edged)
            .semantics { this.contentDescription = contentDescription }
            .pressable(interaction, enabled, onClick)
            .padding(horizontal = AgentPillDefaults.LabelPadding, vertical = 9.dp),
        contentAlignment = contentAlignment,
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
    /** See [AgentPill]. True for a floating control row, false inside a transcript. */
    edged: Boolean = true,
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
                .glassSurface(glass, pressed, shape = CircleShape, edged = edged),
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
 * A tool call, as a pill.
 *
 * ## There is no tool text in the transcript
 *
 * A tool call used to be a line of prose — a verb, the command that ran, the
 * elapsed time — sitting in the middle of somebody's answer. On a turn that ran
 * five tools that was five rows of monospace between the user and the model, and
 * the conversation stopped reading as a conversation: it read as a log the model
 * happened to be narrating.
 *
 * So the call is a pill. It is one capsule, it says the verb, and the colour of
 * the verb says how it went. Everything the tool actually produced — the output,
 * the diff, the copy action — is on the detail sheet the pill opens. The pill is
 * the door, not the room.
 *
 * ## Not glass
 *
 * It sits inside the transcript, so it is a flat [DroshSurfaceHigh] capsule with
 * no edge and no blur. Glass in the middle of a conversation is what turned the
 * transcript into a gallery of surfaces; see [Modifier.glassSurface].
 *
 * ## The verb is the status
 *
 * Present tense while the call runs, past tense once it finishes, so the pill
 * says whether something is still going without a separate indicator to look
 * for. Colour carries the outcome on top: a failure is red whatever the tense.
 */
@Composable
fun AgentToolPill(
    message: ChatMessage.ToolCall,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val glass = LocalAgentGlass.current

    val (present, past) = toolVerbs(message.name)
    val label = if (message.state == ToolCallState.Running) present else past
    val tint = toolVerbColor(message.state)

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = AgentPillDefaults.ToolHeight)
            // edged = false, always: this is a transcript surface, not floating
            // chrome. See the class note.
            .glassSurface(glass, pressed, shape = AgentPillDefaults.Shape, edged = false)
            .semantics { contentDescription = toolRowDescription(message) }
            .pressable(interaction, enabled, onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            ToolIcon(message.name)
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                fontSize = AgentPillDefaults.ToolLabelSize,
                fontWeight = FontWeight.Medium,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
            // The dot is the outcome at a glance, so the row is readable without
            // parsing the verb or the colour: a failure is red and full, a success
            // is a small filled bead, a call that was cancelled is a ring.
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (tint == DroshTextMuted) Color.Transparent else tint),
            )
        }
    }
}

/** The glyph for a tool's name. See the transcript's own [toolVerbs]. */
@Composable
fun ToolIcon(name: String) {
    val icon = when (name) {
        "shell" -> DroshIcons.Terminal
        "read_file" -> DroshIcons.Code
        "write_file" -> DroshIcons.Pencil
        "ask_user" -> DroshIcons.Info
        "update_todo" -> DroshIcons.ListChecks
        else -> DroshIcons.SquareTerminal
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = DroshTextMuted,
        modifier = Modifier.size(AgentPillDefaults.ToolIconSize),
    )
}

/**
 * The verb for a tool, in present and past tense.
 *
 * Present while the call runs, past once it finishes — the tense is the status, so
 * the pill does not need a separate indicator to say whether something is still
 * going.
 *
 * `write_file` is `Wrote` rather than `Edited` because the model creates as often
 * as it modifies, and a verb that only fits one of the two would be wrong half the
 * time.
 */
fun toolVerbs(name: String): Pair<String, String> = when (name) {
    "shell" -> "Running" to "Ran"
    "read_file" -> "Reading" to "Read"
    "write_file" -> "Writing" to "Wrote"
    "grep" -> "Searching" to "Searched"
    "move_file" -> "Moving" to "Moved"
    "ask_user" -> "Asking" to "Asked"
    "update_todo" -> "Updating" to "Updated"
    "web_search" -> "Searching" to "Searched"
    else -> "Working" to "Done"
}

/**
 * The verb's colour, which carries the outcome the tense does not.
 *
 * A failure is red whether or not the reader parses "Ran" — the verb says it
 * finished, the colour says how.
 */
@Composable
fun toolVerbColor(state: ToolCallState): Color = when (state) {
    ToolCallState.Running -> DroshPrimary
    ToolCallState.Succeeded -> DroshText
    ToolCallState.Failed -> DroshError
    ToolCallState.Cancelled -> DroshTextMuted
    ToolCallState.AwaitingApproval -> DroshWarning
    ToolCallState.Pending -> DroshTextMuted
}

/**
 * The spoken form of a tool pill, for a screen reader rather than the eye.
 *
 * Everything a sighted user loses by not having the row's text: the tool's name,
 * its summary, and the outcome. A pill that only said "Ran" would leave a
 * screen-reader user with no way to learn what ran.
 */
@Composable
fun toolRowDescription(message: ChatMessage.ToolCall): String = buildString {
    append(message.name)
    if (message.summary.isNotEmpty()) append(", ${message.summary}")
    append(
        ", " + when (message.state) {
            ToolCallState.Running -> runningCd
            ToolCallState.Succeeded -> succeededCd
            ToolCallState.Failed -> failedCd
            ToolCallState.Cancelled -> cancelledCd
            else -> ""
        },
    )
}

/**
 * The state words [toolRowDescription] reads.
 *
 * A `@ReadOnlyComposable` getter each, so they can be read from inside
 * `buildString` — a block that is not itself a composition slot. An ordinary
 * `@Composable` call from there would be a call outside a composable scope.
 */
private val runningCd: String
    @Composable @ReadOnlyComposable get() = stringResource(R.string.agent_tool_running)

private val succeededCd: String
    @Composable @ReadOnlyComposable get() = stringResource(R.string.agent_tool_succeeded)

private val failedCd: String
    @Composable @ReadOnlyComposable get() = stringResource(R.string.agent_tool_failed)

private val cancelledCd: String
    @Composable @ReadOnlyComposable get() = stringResource(R.string.agent_tool_cancelled)

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
