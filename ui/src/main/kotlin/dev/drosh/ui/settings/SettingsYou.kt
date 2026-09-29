package dev.drosh.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.ui.DroshIcons

/**
 * Settings, built to Material 3's shape rather than to whatever the screen
 * happened to accumulate.
 *
 * The three ideas doing the work: a **large top bar** that says where you
 * are, **groups** on a tinted card so related rows read as one thing, and
 * **rows** that all share one geometry — 72dp, a tonal icon, a title with
 * optional supporting text, and a trailing control. Every row the same shape
 * is what makes a settings screen scannable; the previous one mixed a
 * container per section with bespoke rows that each did their own spacing.
 *
 * Dividers are inset to the text, not the card edge, so the eye reads one
 * column of labels rather than a stack of boxes.
 */

/** The large top bar. Title first, then everything else. */
@Composable
fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DroshIcons.ArrowLeft,
                contentDescription = "Back",
                tint = DroshText,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = title,
            color = DroshText,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A labelled group. The label names the group; the card holds the rows. */
@Composable
fun SettingsGroup(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.padding(horizontal = 16.dp)) {
        Text(
            text = label,
            color = DroshPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            modifier = Modifier.padding(start = 8.dp, top = 20.dp, bottom = 8.dp),
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = DroshSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) { content() }
        }
    }
}

/** Divider between rows, inset so it lines up with the labels, not the card. */
@Composable
fun SettingsDivider(indent: Int = 68) {
    HorizontalDivider(
        color = DroshOutline.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = indent.dp, end = 16.dp),
    )
}

@Composable
private fun RowItem(
    icon: ImageVector?,
    title: String,
    supporting: String?,
    onClick: (() -> Unit)?,
    trailing: @Composable (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Surface(
                shape = CircleShape,
                color = DroshSurface,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = DroshTextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = DroshText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            if (!supporting.isNullOrBlank()) {
                Text(
                    text = supporting,
                    color = DroshTextMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** A row the user opens something with. */
@Composable
fun SettingsLink(
    title: String,
    supporting: String? = null,
    icon: ImageVector? = null,
    value: String? = null,
    onClick: () -> Unit,
) {
    RowItem(icon, title, supporting, onClick) {
        if (value != null) {
            Text(
                text = value,
                color = DroshTextSecondary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = DroshIcons.ChevronRight,
            contentDescription = null,
            tint = DroshTextMuted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A row with a switch. */
@Composable
fun SettingsToggle(
    title: String,
    supporting: String? = null,
    icon: ImageVector? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    RowItem(icon, title, supporting, null) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = DroshPrimary,
                checkedTrackColor = DroshPrimary.copy(alpha = 0.45f),
                uncheckedThumbColor = DroshTextMuted,
                uncheckedTrackColor = DroshOutline.copy(alpha = 0.5f),
                uncheckedBorderColor = DroshOutline,
            ),
        )
    }
}

/** A row carrying any control: a slider, a segmented control, a value. */
@Composable
fun SettingsCustom(
    title: String,
    supporting: String? = null,
    icon: ImageVector? = null,
    content: @Composable () -> Unit,
) {
    RowItem(icon, title, supporting, null, content)
}

/** Three options, one chosen. Material's segmented row, in the app's colours. */
@Composable
fun <T> SettingsSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = DroshPrimary.copy(alpha = 0.22f),
                    activeContentColor = DroshPrimary,
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = DroshTextSecondary,
                ),
                icon = {},
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (value == selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

/** A labelled value with a slider under it. */
@Composable
fun SettingsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = DroshPrimary,
            activeTrackColor = DroshPrimary,
            inactiveTrackColor = DroshOutline.copy(alpha = 0.6f),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    )
}
