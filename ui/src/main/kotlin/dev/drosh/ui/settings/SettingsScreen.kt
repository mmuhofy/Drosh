package dev.drosh.ui.settings

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.core.LanguageCatalog
import dev.drosh.core.copyToClipboard
import dev.drosh.core.toast
import dev.drosh.domain.settings.MotdMode
import dev.drosh.domain.settings.ThemeMode
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.session.DeviceIdentityViewModel
import kotlinx.coroutines.launch

/**
 * Settings, on Material 3's shape.
 *
 * Rebuilt rather than adjusted. The old screen was a column of containers
 * with rows inside them that each did their own spacing, so nothing lined up
 * and the eye had nowhere to land. What is here instead: one large top bar,
 * and every setting inside a labelled group on a tinted card, every row the
 * same 72dp shape with an icon, a title, optional supporting text and one
 * trailing control. Same state, same behaviour, new geometry.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    deviceIdentityViewModel: DeviceIdentityViewModel = hiltViewModel(),
) {
    val themeMode        by viewModel.themeMode.collectAsStateWithLifecycle()
    val locale           by viewModel.locale.collectAsStateWithLifecycle("")
    val useBlockEngine   by viewModel.useBlockEngine.collectAsStateWithLifecycle(false)
    val fontSizeSp       by viewModel.fontSizeSp.collectAsStateWithLifecycle(14)
    val prootStartCommand by viewModel.prootStartCommand.collectAsStateWithLifecycle("")
    val isPinLockEnabled by viewModel.isPinLockEnabled.collectAsStateWithLifecycle(false)
    val cursorStyle      by viewModel.cursorStyle.collectAsStateWithLifecycle("Block")
    val cursorBlinkRateMs by viewModel.cursorBlinkRateMs.collectAsStateWithLifecycle(500)
    val aboutInfo        by viewModel.aboutInfo.collectAsStateWithLifecycle(null)
    val motdMode         by viewModel.motdMode.collectAsStateWithLifecycle(MotdMode.PlainText)
    val motdText         by viewModel.motdText.collectAsStateWithLifecycle("")
    val deviceIdentity   by deviceIdentityViewModel.identity.collectAsStateWithLifecycle()

    val activityContext = LocalContext.current
    var showPinEntry by rememberSaveable { mutableStateOf(false) }
    var showMotdDialog by rememberSaveable { mutableStateOf(false) }
    var showProotDialog by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .testTag("settings_content"),
        ) {
            SettingsTopBar(title = "Settings", onBack = onBack)

            Spacer(Modifier.height(8.dp))
            DeviceHeaderCard(identity = deviceIdentity)

            // ── Appearance ───────────────────────────────────────────────────
            SettingsGroup(label = "Appearance") {
                SettingsCustom(
                    title = "Theme",
                    supporting = when (themeMode) {
                        ThemeMode.System -> "Follows the system setting"
                        ThemeMode.Light  -> "Always light"
                        ThemeMode.Dark   -> "Always dark"
                    },
                    icon = DroshIcons.Palette,
                ) {
                    SettingsSegmented(
                        options = listOf(
                            ThemeMode.System to "Auto",
                            ThemeMode.Light to "Light",
                            ThemeMode.Dark to "Dark",
                        ),
                        selected = themeMode,
                        onSelect = viewModel::setThemeMode,
                    )
                }
            }

            // ── Terminal ─────────────────────────────────────────────────────
            SettingsGroup(label = "Terminal") {
                SettingsToggle(
                    title = "Block mode",
                    supporting = "Show each command and its output as a block. Replaces the classic view.",
                    icon = DroshIcons.SquareTerminal,
                    checked = useBlockEngine,
                    onCheckedChange = viewModel::setUseBlockEngine,
                )
                SettingsDivider()
                SettingsCustom(
                    title = "Font size",
                    supporting = "${fontSizeSp}sp",
                    icon = DroshIcons.Type,
                ) {
                    SettingsSlider(
                        value = fontSizeSp.toFloat(),
                        onValueChange = { viewModel.setFontSize(it.toInt()) },
                        valueRange = 8f..24f,
                        steps = 15,
                    )
                }
                SettingsDivider()
                SettingsLink(
                    title = "Cursor style",
                    supporting = when (cursorStyle) {
                        "Block"  -> "A solid block"
                        "Underline" -> "An underline"
                        "Bar"    -> "A thin bar"
                        else     -> cursorStyle
                    },
                    icon = DroshIcons.Terminal,
                    onClick = { viewModel.setCursorStyle("Block") },
                )
                SettingsDivider()
                SettingsCustom(
                    title = "Cursor blink",
                    supporting = when (cursorBlinkRateMs) {
                        0 -> "Off"
                        else -> "${cursorBlinkRateMs}ms"
                    },
                    icon = DroshIcons.Gauge,
                ) {
                    SettingsSlider(
                        value = cursorBlinkRateMs.toFloat(),
                        onValueChange = { viewModel.setCursorBlinkRateMs(it.toInt()) },
                        valueRange = 0f..1200f,
                    )
                }
                SettingsDivider()
                SettingsLink(
                    title = "Startup command",
                    supporting = prootStartCommand.ifBlank { "\$shell --login" },
                    icon = DroshIcons.Terminal,
                    onClick = { showProotDialog = true },
                )
            }

            // ── Greeting ──────────────────────────────────────────────────────
            SettingsGroup(label = "Message of the day") {
                SettingsCustom(
                    title = "Show as",
                    supporting = when (motdMode) {
                        MotdMode.Disabled  -> "Do not show it"
                        MotdMode.PlainText -> "The shell echoes the text"
                        MotdMode.Compose   -> "Rendered as an interactive card"
                    },
                    icon = DroshIcons.Info,
                ) {
                    SettingsSegmented(
                        options = listOf(
                            MotdMode.Disabled to "Off",
                            MotdMode.PlainText to "Text",
                            MotdMode.Compose to "Card",
                        ),
                        selected = motdMode,
                        onSelect = viewModel::setMotdMode,
                    )
                }
                if (motdMode != MotdMode.Disabled) {
                    SettingsDivider()
                    SettingsLink(
                        title = "Message",
                        supporting = motdText.lineSequence().firstOrNull { it.isNotBlank() }
                            ?: "Tap to edit",
                        icon = DroshIcons.Pencil,
                        onClick = { showMotdDialog = true },
                    )
                }
            }

            // ── Language ──────────────────────────────────────────────────────
            SettingsGroup(label = "Language") {
                LanguageCatalog.options.forEachIndexed { index, option ->
                    if (index > 0) SettingsDivider(indent = 60)
                    SettingsLink(
                        title = option.displayName,
                        supporting = option.nativeName,
                        icon = DroshIcons.Languages,
                        onClick = {
                            viewModel.setLocale(option.tag)
                            (activityContext as Activity).recreate()
                        },
                    )
                }
            }

            // ── Security ──────────────────────────────────────────────────────
            SettingsGroup(label = "Security") {
                SettingsToggle(
                    title = "PIN lock",
                    supporting = "Ask for a PIN before opening a session",
                    icon = DroshIcons.Shield,
                    checked = isPinLockEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            showPinEntry = true
                        } else {
                            scope.launch { viewModel.clearPin() }
                        }
                    },
                )
            }

            // ── About ────────────────────────────────────────────────────────
            SettingsGroup(label = "About") {
                SettingsLink(
                    title = "Version",
                    supporting = aboutInfo?.version ?: "—",
                    icon = DroshIcons.Info,
                    onClick = {},
                )
                SettingsDivider()
                SettingsLink(
                    title = "Build",
                    supporting = aboutInfo?.build ?: "dev",
                    icon = DroshIcons.Terminal,
                    onClick = {
                        (activityContext as? Activity)?.let {
                            val build = aboutInfo?.build ?: "dev"
                            it.copyToClipboard("Build", build)
                            it.toast("Build copied")
                        }
                    },
                )
                SettingsDivider()
                SettingsLink(
                    title = "License",
                    supporting = "GPLv3",
                    icon = DroshIcons.Shield,
                    onClick = {},
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    if (showPinEntry) {
        dev.drosh.ui.pin.PinEntryScreen(
            title = "Set a PIN",
            subtitle = "You will be asked for it before a session opens.",
            onPinReady = { pin ->
                scope.launch {
                    viewModel.setPin(pin)
                    viewModel.setPinLockEnabled(true)
                }
                showPinEntry = false
            },
            onCancel = { showPinEntry = false },
        )
    }

    if (showMotdDialog) {
        val defaultText = "  ╔══════════════════════════════════════╗\n  ║   Welcome to Drosh v1.0            ║\n  ╚══════════════════════════════════════╝"
        MotdTextDialog(
            initialText = motdText.ifBlank { defaultText },
            onDismiss = { showMotdDialog = false },
            onConfirm = { text -> viewModel.setMotdText(text) },
            onRestoreDefault = { viewModel.setMotdText(defaultText) },
        )
    }

    if (showProotDialog) {
        TextEntryDialog(
            title = "Startup command",
            initial = prootStartCommand,
            placeholder = "\$shell --login",
            onDismiss = { showProotDialog = false },
            onConfirm = { command -> viewModel.setProotStartCommand(command) },
        )
    }
}

/** A single-field dialog, used for the startup command. */
@Composable
private fun TextEntryDialog(
    title: String,
    initial: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DroshSurfaceVariant,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        title = { Text(title, color = DroshText, fontWeight = FontWeight.Bold) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(placeholder, color = DroshTextMuted) },
                textStyle = androidx.compose.ui.text.TextStyle(color = DroshText),
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DroshPrimary,
                    unfocusedBorderColor = DroshOutline,
                    cursorColor = DroshPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }) {
                Text("Save", color = DroshPrimary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = DroshTextSecondary)
            }
        },
    )
}
