package dev.drosh.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.ui.R
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.DroshTile
import dev.drosh.design.system.DroshTilePressed
import dev.drosh.design.system.DroshTileSelected
import dev.drosh.design.system.DroshTrack

/**
 * Settings, built as a flat list of tiles on the page — no card behind them,
 * no strokes, and the grouping done with the corner radius instead of a
 * container.
 *
 * The radius is the structure. A group is not a filled box; it is a run of tiles
 * whose ends are rounded and whose middle is not, so the run reads as one object
 * that opens and closes. Inside a group there are no dividers at all: the
 * separation is the gutter and the step in tone between the page and the tile.
 *
 * The second idea is that a row's shape depends on what it carries. A switch, a
 * value or a chevron sits beside the label. A segmented control or a slider
 * does not — it goes under the label, full width. Beside it, a control that wants
 * to fill the row takes the width from the text and squeezes the label to a
 * character per line, which is exactly what this file used to do.
 */

/** The corner a tile takes, from its position in the group. */
enum class TileCap { First, Middle, Last, Only }

/** 24dp at the ends, 4dp in the middle — see the note on the file. */
private val CAP_RADIUS = 24.dp
private val FLOW_RADIUS = 4.dp

internal fun tileShape(cap: TileCap) = when (cap) {
    TileCap.First -> RoundedCornerShape(
        topStart = CAP_RADIUS, topEnd = CAP_RADIUS,
        bottomStart = FLOW_RADIUS, bottomEnd = FLOW_RADIUS,
    )
    TileCap.Last -> RoundedCornerShape(
        topStart = FLOW_RADIUS, topEnd = FLOW_RADIUS,
        bottomStart = CAP_RADIUS, bottomEnd = CAP_RADIUS,
    )
    TileCap.Middle -> RoundedCornerShape(FLOW_RADIUS)
    TileCap.Only -> RoundedCornerShape(CAP_RADIUS)
}

/** A labelled run of tiles with no fill behind it. */
@Composable
fun SettingsGroupColumn(
    label: String?,
    modifier: Modifier = Modifier,
    items: Int,
    content: @Composable (index: Int, cap: TileCap) -> Unit,
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (label != null) {
            Text(
                text = label.uppercase(),
                color = DroshTextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.9.sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 5.dp),
            )
        }
        for (index in 0 until items) {
            val cap = when {
                items == 1 -> TileCap.Only
                index == 0 -> TileCap.First
                index == items - 1 -> TileCap.Last
                else -> TileCap.Middle
            }
            content(index, cap)
        }
    }
}

/** Large top bar. The back control is a filled pill, not a bare chevron. */
@Composable
fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.92f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
            label = "backScale",
        )
        Box(
            modifier = Modifier
                .size(38.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(if (pressed) DroshTilePressed else DroshTile)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onBack,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = dev.drosh.ui.DroshIcons.ArrowLeft,
                contentDescription = "Back",
                tint = DroshText,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            color = DroshText,
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
        )
    }
}

/**
 * The tile itself: icon, title, optional supporting text, optional control.
 *
 * [stacked] puts the control under the label at full width. It is not a style
 * choice, it is the only arrangement that works for a control which fills its
 * row.
 */
@Composable
fun SettingsTile(
    cap: TileCap,
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supporting: String? = null,
    stacked: Boolean = false,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    /** Sits to the left of the label, where an avatar or a device image goes. */
    leading: @Composable (() -> Unit)? = null,
    /** Sits to the right, for a switch, a value or a chevron. */
    control: @Composable (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val base = if (selected) DroshTileSelected else DroshTile
    val background by animateColorAsState(
        targetValue = when {
            pressed -> DroshTilePressed
            selected -> DroshTileSelected
            else -> base
        },
        animationSpec = tween(durationMillis = 140),
        label = "tileBg",
    )
    val shape = tileShape(cap)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .clip(shape)
            .background(background)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        // Built once so both arrangements share it. A control that fills its
        // row cannot be a sibling of a weighted text column: in a Row it wins
        // the width argument and the label collapses to one character per line.
        val head: @Composable RowScope.() -> Unit = {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(14.dp))
            }
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = DroshTextSecondary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (selected) DroshPrimary else DroshText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!supporting.isNullOrBlank()) {
                    Text(
                        text = supporting,
                        color = DroshTextMuted,
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }

        if (stacked) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    content = head,
                )
                if (control != null) {
                    Spacer(Modifier.height(16.dp))
                    Box(modifier = Modifier.fillMaxWidth()) { control() }
                }
            }
        } else {
            // The control has to be a child of this Row. It used to be a
            // sibling of the Row inside the enclosing Box, so it was laid out
            // at the tile's top-left and drawn on top of the label: the
            // switches sat at the far left instead of the right edge, and
            // every chevron stamped itself over the row's icon, which is what
            // read as a reflection on the version, build, licence, language,
            // message, startup-command and cursor-style rows.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                head()
                if (control != null) {
                    Spacer(Modifier.width(8.dp))
                    control()
                }
            }
        }
    }
}

/** Pill segmented control. Selection is a tint, never a solid fill. */
@Composable
fun <T> SettingsSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = MaterialTheme.colorScheme.surface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(track)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val isOn = value == selected
            val background by animateColorAsState(
                targetValue = if (isOn) DroshPrimary.copy(alpha = 0.15f) else Color.Transparent,
                animationSpec = tween(durationMillis = 160),
                label = "segBg",
            )
            val content by animateColorAsState(
                targetValue = if (isOn) DroshPrimary else DroshTextSecondary,
                animationSpec = tween(durationMillis = 160),
                label = "segFg",
            )
            val interaction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(38.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(background)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onSelect(value) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = content,
                    fontSize = 13.5.sp,
                    fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Slider with detents.
 *
 * Two things were wrong with the plain version. It had no stops, so a font
 * size could land on 13.7sp and nobody could hit a round number by dragging
 * to it; and the handle snapped between values with no transition, so it read
 * as jumpy rather than as a control that responds.
 *
 * [steps] gives evenly spaced detents across the range. The value is
 * quantised to the nearest one, and the drawn handle eases toward it.
 *
 * [steps] counts the *interior* detents — the range is divided into steps + 1
 * intervals, matching `Slider`'s own convention. A large count is fine here: the
 * handle position is animated rather than snapped, so hundreds of detents read
 * as a continuous scale instead of a coarse one.
 */
@Composable
fun SettingsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val span = valueRange.endInclusive - valueRange.start
    val divisions = steps + 1
    val step = if (steps > 0 && divisions > 0) span / divisions else 0f
    val onChange by rememberUpdatedState(onValueChange)
    val onFinished by rememberUpdatedState(onValueChangeFinished)
    val trackColor = DroshTrack
    val accent = DroshPrimary

    // The drawn position eases toward the value, so a detent reads as being
    // pulled in rather than as a jump.
    val targetFraction = if (span <= 0f) 0f else ((value - valueRange.start) / span).coerceIn(0f, 1f)
    var drawnFraction by remember { mutableFloatStateOf(targetFraction) }
    val smoothFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "sliderPos",
    )
    LaunchedEffect(targetFraction) { drawnFraction = targetFraction }

    var dragging by remember { mutableStateOf(false) }

    // Press and release are not the same gesture, so they do not get the same
    // spring. Pressing is fast and definite — a stiff spring that reaches full
    // size immediately, because the thumb has to feel like it is under the
    // finger. Releasing is soft and slightly elastic, so the thumb settles back
    // with one small overshoot instead of stopping dead. One spec for both
    // directions is what made the old thumb read as a plain rectangle.
    val handleWidth by animateDpAsState(
        targetValue = if (dragging) 12.dp else 8.dp,
        animationSpec = if (dragging) {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            )
        } else {
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
        },
        label = "handleWidth",
    )
    val handleHeight by animateDpAsState(
        targetValue = if (dragging) 44.dp else 30.dp,
        animationSpec = if (dragging) {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            )
        } else {
            spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
        },
        label = "handleHeight",
    )
    val glow by animateFloatAsState(
        targetValue = if (dragging) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "handleGlow",
    )

    // Pointer input cannot read the layout size, and the draw pass needs it,
    // so it is measured here and read by both.
    var trackWidth by remember { mutableIntStateOf(1) }

    fun quantise(raw: Float): Float {
        if (step <= 0f) return raw.coerceIn(valueRange.start, valueRange.endInclusive)
        val snapped = valueRange.start + ((raw - valueRange.start) / step).let {
            Math.round(it.toDouble()).toFloat()
        } * step
        return snapped.coerceIn(valueRange.start, valueRange.endInclusive)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // Room for the thumb to grow to 44dp under the finger without
            // clipping against the tile edge.
            .height(56.dp)
            .onSizeChanged { trackWidth = it.width.coerceAtLeast(1) }
            .drawBehind {
                val w = trackWidth.toFloat()
                val cy = size.height / 2f
                val track = TRACK_THICKNESS.toPx()
                val half = handleWidth.toPx() / 2f
                val gap = HANDLE_GAP.toPx()
                val centre = w * smoothFraction
                val top = cy - handleHeight.toPx() / 2f

                // Glow first, so it sits behind the thumb rather than over it.
                if (glow > 0.01f) {
                    drawCircle(
                        color = accent.copy(alpha = 0.22f * glow),
                        radius = handleHeight.toPx() * 0.78f,
                        center = Offset(centre, cy),
                    )
                }

                val leftEnd = (centre - half - gap).coerceAtLeast(0f)
                if (leftEnd > 0f) {
                    drawLine(
                        color = accent,
                        start = Offset(0f, cy),
                        end = Offset(leftEnd, cy),
                        strokeWidth = track,
                        cap = StrokeCap.Round,
                    )
                }
                val rightStart = (centre + half + gap).coerceAtMost(w)
                if (w - rightStart > 0f) {
                    drawLine(
                        color = trackColor,
                        start = Offset(rightStart, cy),
                        end = Offset(w, cy),
                        strokeWidth = track,
                        cap = StrokeCap.Round,
                    )
                }
                // A thumb, not a dash: taller than it is wide, and it grows
                // taller still under the finger.
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(centre - half, top),
                    size = androidx.compose.ui.geometry.Size(handleWidth.toPx(), handleHeight.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(handleWidth.toPx() / 2f),
                )
            }
            .pointerInput(steps) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        onFinished?.invoke()
                    },
                    onDragCancel = {
                        dragging = false
                        onFinished?.invoke()
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val f = (change.position.x / trackWidth).coerceIn(0f, 1f)
                        val raw = valueRange.start + f * span
                        onChange(quantise(raw))
                    },
                )
            }
            .pointerInput(steps) {
                detectTapGestures { offset ->
                    val f = (offset.x / trackWidth).coerceIn(0f, 1f)
                    onChange(quantise(valueRange.start + f * span))
                    onFinished?.invoke()
                }
            },
    )
}

private val TRACK_THICKNESS = 10.dp
private val HANDLE_GAP = 4.dp


/** Switch with the accent fill and a soft drop on the thumb. */
@Composable
fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track by animateColorAsState(
        targetValue = if (checked) DroshPrimary else DroshTrack,
        animationSpec = tween(180),
        label = "swTrack",
    )
    Box(
        modifier = modifier
            .size(width = 50.dp, height = 30.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(track)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = { onCheckedChange(!checked) },
            ),
    ) {
        val offset by animateDpAsState(
            targetValue = if (checked) 20.dp else 0.dp,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "swThumb",
        )
        Box(
            modifier = Modifier
                .padding(start = 3.dp, top = 3.dp)
                .offset(x = offset)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.White)
                .then(
                    if (checked) {
                        Modifier.drawBehind {
                            drawCircle(
                                color = Color.Black.copy(alpha = 0.18f),
                                radius = 12.dp.toPx(),
                                center = center,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()),
                            )
                        }
                    } else {
                        Modifier
                    }
                ),
        )
    }
}

/** Chevron used on rows that open something. */
@Composable
fun SettingsChevron(modifier: Modifier = Modifier) {
    Icon(
        imageVector = dev.drosh.ui.DroshIcons.ChevronRight,
        contentDescription = null,
        tint = DroshTextMuted,
        modifier = modifier.size(20.dp),
    )
}

/** Check used on the chosen row. */
@Composable
fun SettingsCheck(modifier: Modifier = Modifier) {
    Icon(
        imageVector = dev.drosh.ui.DroshIcons.Check,
        contentDescription = null,
        tint = DroshPrimary,
        modifier = modifier.size(20.dp),
    )
}

/**
 * Terminal mode, chosen by preview instead of by switch.
 *
 * The switch said "block mode" and asked the reader to imagine the difference.
 * The two modes differ in how a command and its output are laid out, which is
 * exactly the kind of thing a picture settles and a label does not — so each
 * card draws a miniature of the output it produces.
 *
 * The preview is always dark. A terminal preview that turned pale in the light
 * theme would be showing the theme, not the thing being chosen.
 */
@Composable
fun SettingsTerminalModePicker(
    blockSelected: Boolean,
    onSelect: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TerminalModeCard(
            label = stringResource(R.string.settings_mode_terminal),
            block = false,
            selected = !blockSelected,
            onClick = { onSelect(false) },
            modifier = Modifier.weight(1f),
        )
        TerminalModeCard(
            label = stringResource(R.string.settings_mode_block),
            block = true,
            selected = blockSelected,
            onClick = { onSelect(true) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TerminalModeCard(
    label: String,
    block: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val border by animateColorAsState(
        targetValue = if (selected) DroshPrimary else DroshTrack,
        animationSpec = tween(durationMillis = 180),
        label = "modeCardBorder",
    )
    val background by animateColorAsState(
        targetValue = when {
            pressed -> DroshTilePressed
            selected -> DroshPrimary.copy(alpha = 0.10f)
            else -> DroshTile
        },
        animationSpec = tween(durationMillis = 160),
        label = "modeCardBg",
    )
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(width = if (selected) 1.5.dp else 1.dp, color = border, shape = shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(PREVIEW_SURFACE)
                .padding(horizontal = 7.dp, vertical = 7.dp),
        ) {
            if (block) {
                // Command and its output share one rounded plate, which is what
                // the block engine does.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(PREVIEW_PLATE)
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PreviewBar(widthFraction = 0.62f, color = PREVIEW_TEXT)
                    PreviewBar(widthFraction = 0.95f, color = PREVIEW_MUTED)
                    PreviewBar(widthFraction = 0.44f, color = PREVIEW_MUTED)
                }
            } else {
                // Everything runs together on one surface, no plates.
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PreviewBar(widthFraction = 0.52f, color = PREVIEW_TEXT)
                    PreviewBar(widthFraction = 0.88f, color = PREVIEW_MUTED)
                    PreviewBar(widthFraction = 0.36f, color = PREVIEW_TEXT)
                    PreviewBar(widthFraction = 0.70f, color = PREVIEW_MUTED)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            color = if (selected) DroshPrimary else DroshTextSecondary,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PreviewBar(
    widthFraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

private val PREVIEW_SURFACE = Color(0xFF0B0B0F)
private val PREVIEW_PLATE = Color(0xFF1C1C22)
private val PREVIEW_TEXT = Color(0xFFE8E8EC)
private val PREVIEW_MUTED = Color(0xFF6E6E78)
