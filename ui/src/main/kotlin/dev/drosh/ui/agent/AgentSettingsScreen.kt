package dev.drosh.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceLow
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.components.GlassPill
import dev.drosh.ui.agent.components.ActionButton
import dev.drosh.ui.agent.components.DroshAgentMark
import dev.drosh.ui.agent.components.IconAction
import dev.drosh.ui.agent.components.SectionHeader
import dev.drosh.ui.agent.components.TOUCH_TARGET

/**
 * Agent settings: the API key and the model.
 *
 * Reached from Agent Home, from the chat's top bar, and from the error banner when
 * a run cannot start — the three moments a user discovers they need it.
 *
 * The key field is a real labelled field rather than a placeholder: a password box
 * with no label is indistinguishable from a search box, and "no label" is the most
 * common way a settings screen becomes unusable.
 */
@Composable
fun AgentSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AgentSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.providerState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxWidth()
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
                contentDescription = "Geri",
                onClick = onBack,
            )
            DroshAgentMark(size = 20.dp, tint = DroshPrimary)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Agent ayarları",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = DroshText,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "provider") {
                Column {
                    Text(
                        text = "OpenRouter",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = DroshText,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Tek anahtarla yüzlerce modele eriş.",
                        fontSize = 12.sp,
                        color = DroshTextSecondary,
                    )
                }
            }

            item(key = "key") {
                ApiKeyField(
                    hasKey = state.hasKey,
                    onSave = viewModel::saveApiKey,
                    onClear = viewModel::clearApiKey,
                    loadingModels = state.loadingModels,
                )
            }

            state.error?.let { message ->
                item(key = "error") {
                    Text(
                        text = message,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = DroshError,
                    )
                }
            }

            item(key = "models_header") {
                SectionHeader("MODEL")
            }

            if (state.loadingModels) {
                item(key = "loading") {
                    Text(
                        text = "modeller yükleniyor…",
                        fontSize = 12.sp,
                        color = DroshTextMuted,
                    )
                }
            }

            items(state.models, key = { it.id }) { model ->
                ModelRow(
                    modelId = model.id,
                    label = model.label,
                    selected = state.selectedModelId == model.id,
                    onSelect = { viewModel.selectModel(model.id) },
                )
            }

            if (!state.loadingModels && state.models.isEmpty()) {
                item(key = "no_models") {
                    Text(
                        text = "Model listesi boş. Anahtarı kaydedince tekrar dene.",
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = DroshTextMuted,
                    )
                }
            }
        }

        // Saving is sticky rather than per-field: the key and the model list are
        // fetched together, so one button keeps the two in step.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(DroshSurface)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            ActionButton(
                text = "Modelleri yenile",
                onClick = viewModel::fetchModels,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.hasKey && !state.loadingModels,
            )
        }
    }
}

@Composable
private fun ApiKeyField(
    hasKey: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    loadingModels: Boolean,
) {
    var key by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }

    Column {
        Text(
            text = "API anahtarı",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = DroshTextSecondary,
        )
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = TOUCH_TARGET)
                .clip(RoundedCornerShape(12.dp))
                .background(DroshSurfaceVariant)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (key.isEmpty()) {
                    Text(
                        text = if (hasKey) "sk-or-v1-… (kayıtlı)" else "sk-or-v1-…",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = DroshTextMuted,
                    )
                }
                BasicTextField(
                    value = key,
                    onValueChange = { key = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = DroshText),
                    cursorBrush = SolidColor(DroshPrimary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    // Masked by default: a key is visible on someone's shoulder.
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp)
                        .semantics { contentDescription = "OpenRouter API anahtarı" },
                )
            }
            // A 34dp pill next to a 40dp field: the row is already tall, and a
            // full 48dp circle here would push the field's label out of line.
            GlassPill(
                contentDescription = if (visible) "Gizle" else "Göster",
                onClick = { visible = !visible },
                modifier = Modifier.height(40.dp),
                height = 34.dp,
                width = 34.dp,
                icon = if (visible) DroshIcons.EyeOff else DroshIcons.Eye,
            )
        }

        Spacer(Modifier.height(8.dp))

        // Enabled on *any* non-blank input, including whitespace — the view model
        // trims and treats an emptied result as a request to clear, and the user
        // should not have to know that to remove a key.
        ActionButton(
            text = if (hasKey) "Güncelle" else "Kaydet",
            onClick = { onSave(key) },
            modifier = Modifier.fillMaxWidth(),
            enabled = key.isNotBlank() && !loadingModels,
        )

        if (hasKey) {
            Spacer(Modifier.height(8.dp))
            FlatButton(
                text = "Anahtarı sil",
                onClick = onClear,
                modifier = Modifier.fillMaxWidth(),
                tint = DroshError,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = if (hasKey) {
                "Anahtar kayıtlı. Drosh sunucuya başka bir şey göndermez."
            } else {
                "Cihazda şifreli saklanır. Drosh sunucuya başka bir şey göndermez."
            },
            fontSize = 11.sp,
            lineHeight = 16.sp,
            color = DroshTextMuted,
        )
    }
}

@Composable
private fun ModelRow(
    modelId: String,
    label: String?,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TOUCH_TARGET)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) DroshSurfaceLow else DroshSurfaceVariant)
            .clickable(onClick = onSelect)
            // Selection is announced, and shown as a tick as well as a background —
            // the background alone is a colour-only signal.
            .semantics {
                contentDescription = modelId + if (selected) ", seçili" else ""
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = modelId,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = DroshText,
            )
            if (label != null && label != modelId) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    color = DroshTextMuted,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = DroshIcons.Check,
                contentDescription = null,
                tint = DroshPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
