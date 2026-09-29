package dev.drosh.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    Row(
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
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val head: @Composable () -> Unit = {
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
            head()
            if (control != null) {
                Spacer(Modifier.height(16.dp))
                Box(modifier = Modifier.fillMaxWidth()) { control() }
            }
        } else {
            head()
            if (control != null) {
                Spacer(Modifier.width(8.dp))
                control()
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
 * A thin bar with a small dot that grows while held. Deliberately not a chunky
 * ringed handle — that shape reads as a control from 2012.
 */
@Composable
fun SettingsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span <= 0f) 0f else ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val onChange by rememberUpdatedState(onValueChange)
    val trackColor = DroshTrack
    val accent = DroshPrimary

    var dragging by remember { mutableStateOf(false) }
    val dotSize by animateDpAsState(
        targetValue = if (dragging) 21.dp else 16.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "dotSize",
    )

    // Pointer input cannot read the layout size, and the draw pass needs the
    // width, so it is measured here and read by both.
    var trackWidth by remember { mutableIntStateOf(1) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .onSizeChanged { trackWidth = it.width.coerceAtLeast(1) }
            .drawBehind {
                val cy = size.height / 2f
                val w = trackWidth.toFloat()
                val end = w * fraction
                drawLine(
                    color = trackColor,
                    start = Offset(0f, cy),
                    end = Offset(w, cy),
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                if (end > 0f) {
                    drawLine(
                        color = accent,
                        start = Offset(0f, cy),
                        end = Offset(end, cy),
                        strokeWidth = 6.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                    drawCircle(
                        color = accent,
                        radius = dotSize.toPx() / 2f,
                        center = Offset(end, cy),
                    )
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val f = (change.position.x / trackWidth).coerceIn(0f, 1f)
                        onChange(valueRange.start + f * span)
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val f = (offset.x / trackWidth).coerceIn(0f, 1f)
                    onChange(valueRange.start + f * span)
                }
            },
    )
}

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
