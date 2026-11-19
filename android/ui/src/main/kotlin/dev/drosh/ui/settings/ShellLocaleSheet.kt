package dev.drosh.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.core.ShellLocaleCatalog
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

/**
 * The shell's locale, as a sheet.
 *
 * The same shape as the app-language picker, and deliberately so: two
 * language rows that behave differently would be a small cruelty. This one
 * is short — a dozen locales rather than every language on earth — because
 * a terminal user who wants something rarer can type it straight into the
 * settings file, which is where the value lives anyway.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellLocaleSheet(
    currentTag: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DroshSurfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_shell_locale),
                color = DroshText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = stringResource(R.string.settings_shell_locale_desc),
                color = DroshTextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(ShellLocaleCatalog.options) { option ->
                    val selected = option.tag == currentTag
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onSelect(option.tag)
                                onDismiss()
                            }
                            .padding(horizontal = 12.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (selected) DroshPrimary else DroshTextMuted),
                        )
                        Spacer(Modifier.size(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.displayName,
                                color = DroshText,
                                fontSize = 15.sp,
                            )
                            Text(
                                text = option.tag.ifBlank { "system" },
                                color = DroshTextMuted,
                                fontSize = 12.sp,
                            )
                        }
                        if (selected) {
                            Spacer(Modifier.size(8.dp))
                            androidx.compose.material3.Icon(
                                imageVector = DroshIcons.Check,
                                contentDescription = null,
                                tint = DroshPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}
