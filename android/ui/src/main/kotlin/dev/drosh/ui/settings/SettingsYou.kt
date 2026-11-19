package dev.drosh.ui.settings

// Design foundation: ui-ux-pro-max `--design-system "developer terminal settings
// android dark minimal content-first tools"` — Minimal Single Column + Vibrant &
// Block-based. The generated slate/green palette is a direction, not a new
// brand: Drosh keeps its blue accent and theme tokens, and green is reserved
// for the active (running) theme. Typography comes from LocalFontSet — mono
// for section labels, sans for body — instead of adding the suggested font.
// Validation pass: `--domain ux "animation accessibility touch"`.

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusTarget
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.theme.DroshBorderSubtle
import dev.drosh.theme.DroshOnPrimary
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurfaceHigh
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.DroshTile
import dev.drosh.theme.DroshTilePressed
import dev.drosh.theme.DroshTileSelected
import dev.drosh.theme.DroshTrack
import dev.drosh.theme.LocalFontSet
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.ui.agent.components.AgentIconPill

/**
 * Settings, built as a flat list of tiles on the page — no heavy card behind
 * them, only a token hairline for depth, and the grouping done with the corner
 * radius instead of a container.
 *
 * The radius is the structure. A group is not a filled box; it is a run of tiles
 * whose ends are rounded and whose middle is not, so the run reads as one object
 * that opens and closes. Inside a group there are no dividers at all: the
 * separation is the 8dp gutter and the step in tone between the page and the tile.
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

/**
 * The corner a tile takes, from its position in a run of [count].
 *
 * Internal and shared because the run rule is the language, not one screen's:
 * the projects screen builds its rows through a `LazyColumn` rather than
 * through [SettingsGroupColumn], and it has to land the same 24dp/4dp rhythm
 * the settings groups do instead of a second copy of the arithmetic that
 * drifts from this one.
 */
internal fun tileCapFor(index: Int, count: Int): TileCap = when {
    count <= 1 -> TileCap.Only
    index == 0 -> TileCap.First
    index == count - 1 -> TileCap.Last
    else -> TileCap.Middle
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
        // 8dp between tiles clears the touch-spacing guideline while the end
        // caps still make a run read as one grouped object.
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (label != null) {
            // A section label is a hierarchy cue, not a sentence: a short accent
            // rule plus tracked uppercase text reads as a tier above the tiles
            // without adding a second fill or a shadow to the page.
            Row(
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 3.dp, height = 13.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(DroshPrimary),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    text = label.uppercase(),
                    color = DroshTextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    // Mono section labels carry the "code" half of the
                    // generated system without adding a new font file.
                    fontFamily = LocalFontSet.current.mono,
                )
            }
        }
        for (index in 0 until items) {
            content(index, tileCapFor(index, items))
        }
    }
}

/**
 * Large top bar.
 *
 * The back control is the agent's [AgentIconPill], not a second variant of it.
 * The agent home and the agent chat both lead with that pill — 44dp capsule, one
 * flat fill, one hairline glass edge, a fill change and nothing else under the
 * finger — and this screen had its own: a 44dp circle with a 1dp outline and a
 * 0.92 scale spring on press. Same size, three different chrome. A user who has
 * learned what the back control feels like on one screen should not have to
 * learn a second one here, so the reference is used rather than copied, and the
 * two now cannot drift apart again.
 */
@Composable
fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // One bar for every screen that borrows the settings chrome. The title is
    // the only large text on the page; a hairline under it separates the fixed
    // header from the scrolled content without adding a second type scale.
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // AgentIconPill already draws a 34dp face inside a 48dp hit area.
            AgentIconPill(
                icon = DroshIcons.ArrowLeft,
                contentDescription = stringResource(R.string.action_back),
                onClick = onBack,
                tint = DroshTextSecondary,
            )

            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                color = DroshText,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontFamily = LocalFontSet.current.sans,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(DroshBorderSubtle),
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
    /**
     * A second action behind a long press.
     *
     * Where a row's secondary or destructive action lives — the theme library
     * removes a theme here. A button for it on every row is a row that acts by
     * accident.
     */
    onLongClick: (() -> Unit)? = null,
    /** Sits to the left of the label, where an avatar or a device image goes. */
    leading: @Composable (() -> Unit)? = null,
    /** Sits to the right, for a switch, a value or a chevron. */
    control: @Composable (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // UNTESTED — verify before use (CI is the build gate): keyboard focus is
    // drawn as a 2dp accent ring rather than relying on the default ripple,
    // which is disabled below so the pressed tint stays the only press cue.
    val focused by interaction.collectIsFocusedAsState()
    val base = if (selected) DroshTileSelected else DroshTile
    val background by animateColorAsState(
        targetValue = when {
            pressed -> DroshTilePressed
            selected -> DroshTileSelected
            else -> base
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "tileBg",
    )
    // The generated system warns against flat design without depth. A token
    // hairline gives each tile an edge in both themes; keyboard focus swaps it
    // for a thicker accent ring without changing the tile's bounds.
    val outlineColor by animateColorAsState(
        targetValue = if (focused) DroshPrimary else DroshBorderSubtle,
        animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing),
        label = "tileOutline",
    )
    val shape = tileShape(cap)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .clip(shape)
            .background(background)
            .border(width = if (focused) 2.dp else 1.dp, color = outlineColor, shape = shape)
            .then(if (onClick != null) Modifier.focusTarget() else Modifier)
            .then(
                when {
                    onClick != null && onLongClick != null -> Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                    onClick != null -> Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                    )
                    else -> Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
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
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (selected) DroshPrimary else DroshText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = LocalFontSet.current.sans,
                )
                if (!supporting.isNullOrBlank()) {
                    Text(
                        text = supporting,
                        color = DroshTextMuted,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 2.dp),
                        fontFamily = LocalFontSet.current.sans,
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
    val track = DroshSurfaceHigh
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(track)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (value, label) ->
            val isOn = value == selected
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val background by animateColorAsState(
                targetValue = when {
                    pressed -> DroshTilePressed
                    isOn -> DroshPrimary.copy(alpha = 0.16f)
                    else -> Color.Transparent
                },
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                label = "segBg",
            )
            val content by animateColorAsState(
                targetValue = if (isOn) DroshPrimary else DroshTextSecondary,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                label = "segFg",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(background)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onSelect(value) },
                    )
                    .semantics { this.selected = isOn },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = content,
                    fontSize = 13.5.sp,
                    fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    fontFamily = LocalFontSet.current.sans,
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
    // A semantic Material 3 Slider: it announces its current value, supports
    // keyboard adjustment and keeps a 48dp touch target. The old canvas version
    // drew a good-looking thumb but was invisible to screen readers and had no
    // keyboard path. Detents stay through [steps]; the row's supporting text is
    // the value label, so no number is shown twice.
    // UNTESTED — verify before use (CI is the build gate).
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(
            thumbColor = DroshPrimary,
            activeTrackColor = DroshPrimary,
            inactiveTrackColor = DroshTrack,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        ),
    )
}


/**
 * Semantic switch. Material 3 owns the thumb, the 48dp touch target, the
 * on/off announcement and the keyboard path; only the palette is Drosh's.
 * The state is spatial (thumb position) as well as chromatic (track fill), so
 * it never relies on colour alone.
 * UNTESTED — verify before use (CI is the build gate).
 */
@Composable
fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = DroshOnPrimary,
            checkedTrackColor = DroshPrimary,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = DroshTextSecondary,
            uncheckedTrackColor = DroshTrack,
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

/**
 * A switch with an explicit On/Off label, for settings rows that carry state.
 *
 * The label is the "value" beside the control: it keeps a row legible when the
 * accent fill is dimmed in a sunlight screenshot, and it reads correctly to a
 * screen reader without the user having to activate the switch first.
 */
@Composable
fun SettingsLabeledSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(if (checked) R.string.settings_summary_on else R.string.settings_summary_off),
            color = DroshTextSecondary,
            fontSize = 13.sp,
            // A reserved width keeps the switch from moving when On becomes Off.
            modifier = Modifier.width(34.dp),
            textAlign = TextAlign.End,
            fontFamily = LocalFontSet.current.sans,
        )
        SettingsSwitch(checked = checked, onCheckedChange = onCheckedChange)
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
            .semantics { this.selected = selected }
            .padding(10.dp),
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
        Spacer(Modifier.height(10.dp))
        // The chosen card is named, not just tinted: a check mark next to the
        // label keeps the selection readable without colour.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = if (selected) DroshPrimary else DroshTextSecondary,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontFamily = LocalFontSet.current.sans,
            )
            if (selected) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = DroshIcons.Check,
                    contentDescription = stringResource(R.string.settings_a11y_selected),
                    tint = DroshPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
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
