package dev.drosh.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceContainerLowest
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextDisabled
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.DroshWarning
import dev.drosh.design.system.OutfitFontFamily
import androidx.compose.ui.graphics.Color
import dev.drosh.ui.DroshIcons

@Composable
fun SettingsSection(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            color = DroshTextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            fontFamily = OutfitFontFamily,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        content()
    }
}

@Composable
fun SettingsSectionContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(DroshSurface),
    ) {
        content()
    }
}

@Composable
fun SettingsSubRow(
    icon: ImageVector,
    label: String,
    description: String? = null,
    iconTint: Color = DroshPrimary,
    trailing: @Composable () -> Unit,
) {
    val bgTint = if (iconTint == DroshError) DroshError.copy(alpha = 0.12f) else DroshPrimary.copy(alpha = 0.12f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(100))
                .background(bgTint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = DroshText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = OutfitFontFamily,
            )
            if (description != null) {
                Text(
                    text = description,
                    color = DroshTextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 1.dp),
                    fontFamily = OutfitFontFamily,
                )
            }
        }
        trailing()
    }
}

@Composable
fun SettingsSliderRow(
    icon: ImageVector,
    label: String,
    description: String? = null,
    iconTint: Color = DroshPrimary,
    trailing: @Composable () -> Unit = {},
    sliderContent: @Composable () -> Unit,
) {
    val bgTint = if (iconTint == DroshError) DroshError.copy(alpha = 0.12f) else DroshPrimary.copy(alpha = 0.12f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(100))
                    .background(bgTint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = DroshText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = OutfitFontFamily,
                )
                if (description != null) {
                    Text(
                        text = description,
                        color = DroshTextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 1.dp),
                        fontFamily = OutfitFontFamily,
                    )
                }
            }
            trailing()
        }
        Spacer(Modifier.height(8.dp))
        sliderContent()
    }
}

@Composable
fun SettingsCommandFieldRow(
    icon: ImageVector,
    label: String,
    description: String? = null,
    iconTint: Color = DroshPrimary,
    command: String,
    onCommandChange: (String) -> Unit,
) {
    val bgTint = if (iconTint == DroshError) DroshError.copy(alpha = 0.12f) else DroshPrimary.copy(alpha = 0.12f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(100))
                    .background(bgTint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = DroshText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = OutfitFontFamily,
                )
                if (description != null) {
                    Text(
                        text = description,
                        color = DroshTextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 1.dp),
                        fontFamily = OutfitFontFamily,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        var text by rememberSaveable { mutableStateOf(command) }
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onCommandChange(it)
            },
            textStyle = TextStyle(
                color = DroshPrimary,
                fontSize = 12.sp,
                fontFamily = OutfitFontFamily,
            ),
            singleLine = true,
            shape = RoundedCornerShape(6.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = DroshPrimary.copy(alpha = 0.3f),
                cursorColor = DroshPrimary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun TerminalModeRow(
    useBlockEngine: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    SettingsSubRow(
        icon = DroshIcons.Terminal,
        label = "Terminal Mode",
    ) {
        SegmentControl(
            options = listOf("Classic", "Block"),
            selectedIndex = if (useBlockEngine) 1 else 0,
            onSelect = { onSelect(it == 1) },
            modifier = Modifier.widthIn(max = 160.dp),
        )
    }
}

@Composable
private fun SegmentControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackHeight = 32.dp
    Row(
        modifier = modifier
            .height(trackHeight)
            .background(DroshSurfaceHigh, RoundedCornerShape(16.dp))
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, opt ->
            val isSelected = index == selectedIndex
            val animColor by animateColorAsState(
                targetValue = if (isSelected) DroshPrimary else Color.Transparent,
                animationSpec = tween(200),
                label = "segment_color_$index",
            )
            val animTextColor by animateColorAsState(
                targetValue = if (isSelected) Color(0xFF14171B) else DroshTextSecondary,
                animationSpec = tween(200),
                label = "segment_text_$index",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(100))
                    .background(animColor)
                    .clickable(
                        onClick = { onSelect(index) },
                    )
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = opt,
                    color = animTextColor,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    fontFamily = OutfitFontFamily,
                )
            }
        }
    }
}

@Composable
fun CursorSegmentedControl(
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    val selectedIndex = options.indexOf(selected).coerceAtLeast(0)
    SegmentControl(
        options = options,
        selectedIndex = selectedIndex,
        onSelect = { onSelect(options[it]) },
        modifier = Modifier.widthIn(max = 220.dp),
    )
}

@Composable
fun TerminalPreviewCard(
    cursorStyle: String = "Block",
    cursorBlinkRateMs: Int = 500,
    fontSizeSp: Int = 14,
    useBlockEngine: Boolean = true,
) {
    val lines = listOf(
        Triple("user@irisshell ~ %", "neofetch", true),
        Triple("OS:", " Drosh Linux aarch64 (POSIX)", false),
        Triple("Shell:", " zsh 5.9 \u2022 Term: xterm-256color", false),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DroshSurfaceContainerLowest)
            .padding(14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(DroshError, DroshWarning, DroshPrimary).forEach { color ->
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(100)),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(modifier = Modifier.size(10.dp)) {
                        drawCircle(color = color, radius = 5f)
                    }
                }
            }
        }

        lines.forEachIndexed { index, (prompt, output, _) ->
            if (useBlockEngine && index > 0) {
                Divider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = DroshOutline.copy(alpha = 0.3f),
                    thickness = 1.dp,
                )
            }
            val text = buildAnnotatedString {
                withStyle(SpanStyle(color = DroshPrimary, fontWeight = FontWeight.Medium)) {
                    append(prompt)
                }
                withStyle(SpanStyle(color = DroshText)) {
                    append(output)
                }
            }
            Text(
                text = text,
                fontSize = fontSizeSp.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.Unspecified,
                lineHeight = (fontSizeSp * 1.42).sp,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            Text(
                text = "user@irisshell ~ %",
                color = DroshPrimary,
                fontSize = fontSizeSp.sp,
                fontFamily = FontFamily.Monospace,
            )
            var cmdText by rememberSaveable { mutableStateOf("") }
            Text(
                text = cmdText,
                color = DroshText,
                fontSize = fontSizeSp.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp),
            )
            BlinkingCursor(
                visible = true,
                rateMs = cursorBlinkRateMs,
                style = cursorStyle,
                fontSizeSp = fontSizeSp,
            )
        }
    }
}

@Composable
fun BlinkingCursor(visible: Boolean, rateMs: Int, style: String, fontSizeSp: Int) {
    if (visible) {
        var isVisible by remember { mutableStateOf(true) }
        LaunchedEffect(rateMs, style) {
            while (isActive) {
                delay(rateMs.toLong())
                isVisible = !isVisible
            }
        }
        val color = if (isVisible) DroshPrimary else Color.Transparent
        val cursorHeightDp = fontSizeSp * 1.15f

        val widthDp: Float
        val heightDp: Float
        when (style.lowercase()) {
            "beam" -> {
                widthDp = 2f
                heightDp = cursorHeightDp
            }
            "underline" -> {
                widthDp = 9f
                heightDp = 2.5f
            }
            else -> {
                widthDp = 8f
                heightDp = cursorHeightDp
            }
        }

        Canvas(modifier = Modifier.size(widthDp.dp, heightDp.dp)) {
            drawRoundRect(
                color = color,
                size = Size(widthDp, heightDp),
                cornerRadius = CornerRadius(2f),
            )
        }
    }
}

@Composable
fun BlinkRateSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Slow",
            color = DroshTextSecondary,
            fontSize = 12.sp,
            fontFamily = OutfitFontFamily,
        )
        ThinSlider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 150f..1000f,
            steps = 16,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "Fast",
            color = DroshTextSecondary,
            fontSize = 12.sp,
            fontFamily = OutfitFontFamily,
        )
    }
}

@Composable
fun FontSizeSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            onClick = { if (value > 10) onValueChange(value - 1) },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = DroshIcons.Minus,
                contentDescription = "Decrease font size",
                tint = DroshTextSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
         ThinSlider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 10f..24f,
            steps = 13,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = { if (value < 24) onValueChange(value + 1) },
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = DroshIcons.Plus,
                contentDescription = "Increase font size",
                tint = DroshTextSecondary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
fun ThinSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = Color.White,
            activeTrackColor = DroshPrimary,
            inactiveTrackColor = DroshSurfaceHigh,
            activeTickColor = Color.Transparent,
            inactiveTickColor = Color.Transparent,
        ),
        modifier = modifier.height(20.dp),
    )
}

@Composable
fun SettingsToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val trackColor by animateColorAsState(
        targetValue = if (checked) DroshPrimary else DroshSurfaceHigh,
        animationSpec = tween(200),
        label = "toggle_track",
    )
    val thumbOffset by animateFloatAsState(
        targetValue = if (checked) 18f else 2f,
        animationSpec = tween(200),
        label = "toggle_thumb_offset",
    )

    Box(
        modifier = Modifier
            .size(44.dp, 26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(trackColor)
            .clickable(
                onClick = { onCheckedChange(!checked) },
            )
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .offset(x = thumbOffset.dp)
                .clip(RoundedCornerShape(100)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(22.dp)) {
                drawCircle(color = Color.White, radius = 10f)
            }
        }
    }
}

@Composable
fun SettingsNavigationRow(
    icon: ImageVector,
    label: String,
    trailingText: String? = null,
    trailingBadge: String? = null,
    showTrailingIcon: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(100))
                .background(DroshSurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = DroshTextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        if (label.isNotBlank()) {
            Text(
                text = label,
                color = DroshText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = OutfitFontFamily,
                modifier = Modifier.weight(1f),
            )
        }
        if (trailingBadge != null) {
            Text(
                text = trailingBadge,
                color = DroshPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = OutfitFontFamily,
                modifier = Modifier
                    .background(DroshPrimary.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = DroshIcons.ArrowRight,
                contentDescription = null,
                tint = DroshTextDisabled,
                modifier = Modifier.size(16.dp),
            )
        } else if (trailingText != null) {
            Text(
                text = trailingText,
                color = DroshTextSecondary,
                fontSize = 15.sp,
                fontFamily = OutfitFontFamily,
            )
            if (showTrailingIcon) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = DroshIcons.ArrowRight,
                    contentDescription = null,
                    tint = DroshTextDisabled,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else if (showTrailingIcon) {
            Icon(
                imageVector = DroshIcons.ArrowRight,
                contentDescription = null,
                tint = DroshTextDisabled,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
