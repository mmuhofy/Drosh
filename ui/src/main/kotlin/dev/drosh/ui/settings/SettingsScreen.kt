package dev.drosh.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.app.Activity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshText
import dev.drosh.core.LanguageCatalog
import dev.drosh.core.LanguageOption
import dev.drosh.design.system.OutfitFontFamily
import dev.drosh.domain.settings.CursorStyle
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val locale            by viewModel.locale.collectAsStateWithLifecycle("")
    val useBlockEngine    by viewModel.useBlockEngine.collectAsStateWithLifecycle(false)
    val fontSizeSp        by viewModel.fontSizeSp.collectAsStateWithLifecycle(14)
    val prootStartCommand by viewModel.prootStartCommand.collectAsStateWithLifecycle("")
    val isPinLockEnabled  by viewModel.isPinLockEnabled.collectAsStateWithLifecycle(false)
    val cursorStyle       by viewModel.cursorStyle.collectAsStateWithLifecycle("Block")
    val cursorBlinkRateMs by viewModel.cursorBlinkRateMs.collectAsStateWithLifecycle(500)
    val aboutInfo         by viewModel.aboutInfo.collectAsStateWithLifecycle(null)

    val activityContext = LocalContext.current
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

            SettingsSection(label = stringResource(R.string.settings_language_title)) {
                SettingsSectionContainer {
                    SettingsLanguageRow(
                        currentLocale = locale,
                        options = LanguageCatalog.options,
                        onSelect = { tag ->
                            viewModel.setLocale(tag)
                            (activityContext as Activity).recreate()
                        },
                    )
                }
            }

            SettingsSection(label = stringResource(R.string.settings_terminal_section)) {
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
                    val cursorOptions = listOf(
                        stringResource(R.string.settings_cursor_style_block),
                        stringResource(R.string.settings_cursor_style_beam),
                        stringResource(R.string.settings_cursor_style_underline),
                    )
                    SettingsSubRow(
                        icon = DroshIcons.ALargeSmall,
                        label = stringResource(R.string.settings_cursor_style),
                    ) {
                        CursorSegmentedControl(
                            selected = cursorStyle,
                            options = cursorOptions,
                            onSelect = {
                                viewModel.setCursorStyle(CursorStyle.fromString(it))
                            },
                        )
                    }
                    SettingsSliderRow(
                        icon = DroshIcons.Gauge,
                        label = stringResource(R.string.settings_cursor_blink_rate),
                        description = stringResource(R.string.settings_cursor_blink_description),
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
                        label = stringResource(R.string.settings_font_size),
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
                        label = stringResource(R.string.settings_proot_start_command),
                        description = stringResource(R.string.settings_proot_start_description),
                        command = prootStartCommand.ifEmpty { "\$shell --login" },
                        onCommandChange = { viewModel.setProotStartCommand(it) },
                    )
                }
            }

            SettingsSection(label = stringResource(R.string.settings_security_section)) {
                SettingsSectionContainer {
                    SettingsSubRow(
                        icon = DroshIcons.Lock,
                        iconTint = DroshError,
                        label = stringResource(R.string.settings_app_lock),
                        description = stringResource(R.string.settings_app_lock_description),
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

            SettingsSection(label = stringResource(R.string.settings_about_section)) {
                SettingsSectionContainer {
                    SettingsNavigationRow(
                        icon = DroshIcons.Info,
                        label = stringResource(R.string.settings_version),
                        trailingText = aboutInfo?.version ?: "—",
                        onClick = {},
                    )
                    SettingsNavigationRow(
                        icon = DroshIcons.Terminal,
                        label = stringResource(R.string.settings_description),
                        trailingText = aboutInfo?.build ?: stringResource(R.string.settings_default_build_description),
                        onClick = {},
                    )
                    SettingsNavigationRow(
                        icon = DroshIcons.Shield,
                        label = stringResource(R.string.settings_license),
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
                    label = stringResource(R.string.settings_made_by),
                    trailingText = null,
                    onClick = {},
                )
            }
        }
    }

    if (showPinEntry) {
        dev.drosh.ui.pin.PinEntryScreen(
            title = stringResource(R.string.pin_entry_title),
            subtitle = stringResource(R.string.pin_entry_subtitle),
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
                contentDescription = stringResource(R.string.settings_back_content_description),
                tint = DroshPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = stringResource(R.string.settings_title),
            color = DroshText,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = OutfitFontFamily,
            modifier = Modifier.weight(1f),
        )
        Box(modifier = Modifier.size(40.dp))
    }
}

@Composable
fun SettingsLanguageRow(
    currentLocale: String,
    options: List<LanguageOption>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.find { it.tag == currentLocale } ?: options.first()
    Box(Modifier.clickable { expanded = true }) {
        SettingsSubRow(
            icon = DroshIcons.Globe,
            label = stringResource(R.string.settings_language_title),
            description = selected.displayName,
            trailing = {
                Text(
                    text = selected.nativeName,
                    color = DroshPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            },
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        options.forEach { option ->
            DropdownMenuItem(
                onClick = {
                    expanded = false
                    onSelect(option.tag)
                },
                text = { Text(option.nativeName) },
            )
        }
    }
}
