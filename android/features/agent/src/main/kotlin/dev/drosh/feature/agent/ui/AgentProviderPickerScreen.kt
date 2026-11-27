package dev.drosh.feature.agent.ui

import dev.drosh.ui.components.IconAction
import dev.drosh.ui.components.TOUCH_TARGET
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.theme.DroshBackground
import dev.drosh.theme.DroshBorderSubtle
import dev.drosh.theme.DroshError
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurface
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.DroshTile
import dev.drosh.theme.DroshTilePressed
import dev.drosh.theme.DroshTrack
import dev.drosh.theme.LocalFontSet
import dev.drosh.domain.agent.CatalogState
import dev.drosh.domain.agent.LlmModel
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.ProviderCatalogIds
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.feature.agent.ui.components.ProviderPicker
import dev.drosh.ui.settings.GROUP_GAP
import dev.drosh.ui.settings.SettingsCategoryScreen
import dev.drosh.ui.settings.SettingsCheck
import dev.drosh.ui.settings.SettingsChevron
import dev.drosh.ui.settings.SettingsGroupColumn
import dev.drosh.ui.settings.SettingsSelectRow
import dev.drosh.ui.settings.SettingsTextFieldRow
import dev.drosh.ui.settings.SettingsTile
import dev.drosh.ui.settings.TileCap

/** The full-screen provider picker. */
@Composable
internal fun ProviderPickerScreen(
    providers: List<LlmProvider>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DroshBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DroshSurface)
                .statusBarsPadding()
                .heightIn(min = TOUCH_TARGET)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconAction(
                icon = DroshIcons.ArrowLeft,
                contentDescription = stringResource(R.string.action_back),
                onClick = onDismiss,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.agent_settings_provider_select),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = DroshText,
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            ProviderPicker(
                providers = providers,
                selectedId = selectedId,
                onSelect = onSelect,
            )
        }
    }
}
