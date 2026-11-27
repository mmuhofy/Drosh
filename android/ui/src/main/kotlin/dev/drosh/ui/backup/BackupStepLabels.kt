package dev.drosh.ui.backup

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.theme.DroshError
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSuccess
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.DroshTrack
import dev.drosh.theme.LocalFontSet
import dev.drosh.domain.backup.BackupOptions
import dev.drosh.domain.backup.BackupProgress
import dev.drosh.domain.backup.BackupSectionKind
import dev.drosh.domain.backup.RestoreSelection
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.components.ActionButton
import dev.drosh.ui.settings.GROUP_GAP
import dev.drosh.ui.settings.SettingsCategoryScreen
import dev.drosh.ui.settings.SettingsGroupColumn
import dev.drosh.ui.settings.SettingsSegmented
import dev.drosh.ui.settings.SettingsSwitch
import dev.drosh.ui.settings.SettingsTile
import dev.drosh.ui.settings.TileCap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The steps the repository reports, as something a person reads.
 *
 * The contract asks the repository for "short, stable" step names and says the
 * UI may map them to a phrase — the phrase is what the progress row shows.
 * Known names are mapped; anything unknown falls through unchanged, so a step
 * the repository learns later still reaches the screen rather than being
 * swallowed as blank.
 */
internal fun stepLabel(step: String): String = when (step) {
    "settings" -> STEP_SETTINGS
    "app data" -> STEP_APP_DATA
    "database" -> STEP_DATABASE
    "themes" -> STEP_THEMES
    "snippets" -> STEP_SNIPPETS
    "input preferences" -> STEP_INPUT_PREFS
    "rootfs" -> STEP_ROOTFS
    "manifest" -> STEP_MANIFEST
    "verifying" -> STEP_VERIFYING
    else -> step
}

