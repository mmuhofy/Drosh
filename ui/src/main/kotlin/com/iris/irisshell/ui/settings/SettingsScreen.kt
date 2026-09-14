package com.iris.irisshell.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iris.irisshell.design.system.DroshBackground
import com.iris.irisshell.design.system.DroshError
import com.iris.irisshell.design.system.DroshPrimary
import com.iris.irisshell.design.system.DroshSurfaceHigh
import com.iris.irisshell.design.system.DroshText
import com.iris.irisshell.design.system.OutfitFontFamily
import com.iris.irisshell.domain.settings.CursorStyle
import com.iris.irisshell.ui.DroshIcons
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val useBlockEngine    by viewModel.useBlockEngine.collectAsStateWithLifecycle(false)
    val fontSizeSp        by viewModel.fontSizeSp.collectAsStateWithLifecycle(14)
    val prootStartCommand by viewModel.prootStartCommand.collectAsStateWithLifecycle("")
    val isPinLockEnabled  by viewModel.isPinLockEnabled.collectAsStateWithLifecycle(false)
    val cursorStyle       by viewModel.cursorStyle.collectAsStateWithLifecycle("Block")
    val cursorBlinkRateMs by viewModel.cursorBlinkRateMs.collectAsStateWithLifecycle(500)
    val aboutInfo         by viewModel.aboutInfo.collectAsStateWithLifecycle(null)

    var showPinEntry by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DroshBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .testTag("settings_content"),
        ) {
            SettingsTopBar(onBack = onBack)

            SettingsSection(label = "Terminal") {
                SettingsSectionContainer {
                    TerminalModeRow(
                        useBlockEngine = useBlockEngine,
                        onSelect = { viewModel.setUseBlockEngine(it) },
                    )
                    TerminalPreviewCard(
                        cursorStyle = cursorStyle,
                        cursorBlinkRateMs = cursorBlinkRateMs,
                        fontSizeSp = fontSizeSp,
                        useBlockEngine = useBlockEngine,
                    )
                    SettingsSubRow(
                        icon = DroshIcons.ALargeSmall,
                        label = "Cursor Style",
                    ) {
                        CursorSegmentedControl(
                            selected = cursorStyle,
                            options = listOf("Block", "Beam", "Underline"),
                            onSelect = {
                                viewModel.setCursorStyle(CursorStyle.fromString(it))
                            },
                        )
                    }
                    SettingsSliderRow(
                        icon = DroshIcons.Gauge,
                        label = "Cursor Blink Rate",
                        description = "Pulse interval",
                        trailing = {
                            Text(
                                text = "${cursorBlinkRateMs} ms",
                                color = DroshPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                fontFamily = OutfitFontFamily,
                                modifier = Modifier
                                    .background(DroshSurfaceHigh, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        },
                        sliderContent = {
                            BlinkRateSlider(
                                value = cursorBlinkRateMs,
                                onValueChange = { viewModel.setCursorBlinkRateMs(it) },
                            )
                        },
                    )
                    SettingsSliderRow(
                        icon = DroshIcons.Type,
                        label = "Font Size",
                        trailing = {
                            Text(
                                text = "${fontSizeSp} sp",
                                color = DroshPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = OutfitFontFamily,
                                modifier = Modifier
                                    .background(DroshSurfaceHigh, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        },
                        sliderContent = {
                            FontSizeSlider(
                                value = fontSizeSp,
                                onValueChange = { viewModel.setFontSize(it) },
                            )
                        },
                    )
                    SettingsCommandFieldRow(
                        icon = DroshIcons.Terminal,
                        label = "PRoot Start Command",
                        description = "Experimental — changing this can break sessions",
                        command = prootStartCommand.ifEmpty { "\$shell --login" },
                        onCommandChange = { viewModel.setProotStartCommand(it) },
                    )
                }
            }

            SettingsSection(label = "Security") {
                SettingsSectionContainer {
                    SettingsSubRow(
                        icon = DroshIcons.Lock,
                        iconTint = DroshError,
                        label = "App Lock (PIN)",
                        description = "Require PIN on launch",
                    ) {
                        SettingsToggleSwitch(
                            checked = isPinLockEnabled,
                            onCheckedChange = {
                                if (it) showPinEntry = true
                                else {
                                    scope.launch { viewModel.clearPin() }
                                }
                            },
                        )
                    }
                }
            }

            SettingsSection(label = "About") {
                SettingsSectionContainer {
                    SettingsNavigationRow(
                        icon = DroshIcons.Info,
                        label = "Version",
                        trailingText = aboutInfo?.version ?: "—",
                        onClick = {},
                    )
                    SettingsNavigationRow(
                        icon = DroshIcons.Terminal,
                        label = "Description",
                        trailingText = aboutInfo?.build ?: "Advanced terminal",
                        onClick = {},
                    )
                    SettingsNavigationRow(
                        icon = DroshIcons.Shield,
                        label = "License",
                        trailingBadge = aboutInfo?.license ?: "MIT",
                        showTrailingIcon = true,
                        onClick = {},
                    )
                }
            }

            SettingsSectionContainer(
                modifier = Modifier.padding(top = 24.dp),
            ) {
                SettingsNavigationRow(
                    icon = DroshIcons.CircleUser,
                    label = "Made by Muhofy",
                    trailingText = null,
                    onClick = {},
                )
            }
        }
    }

    if (showPinEntry) {
        com.iris.irisshell.ui.pin.PinEntryScreen(
            title = "Set PIN",
            subtitle = "Enter a new 4-digit PIN",
            onPinReady = { pin ->
                scope.launch {
                    viewModel.setPin(pin)
                    viewModel.setPinLockEnabled(true)
                    showPinEntry = false
                }
            },
            onCancel = { showPinEntry = false },
        )
    }
}

@Composable
fun SettingsTopBar(onBack: () -> Unit) {
    val statusBarH = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = statusBarH, start = 8.dp, end = 8.dp, bottom = 8.dp),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = DroshIcons.ArrowLeft,
                contentDescription = "Back",
                tint = DroshPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = "Settings",
            color = DroshText,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = OutfitFontFamily,
            modifier = Modifier.weight(1f),
        )
        Box(modifier = Modifier.size(40.dp))
    }
}
