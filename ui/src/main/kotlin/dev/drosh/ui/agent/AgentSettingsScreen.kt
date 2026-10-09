package dev.drosh.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.drosh.design.system.LocalFontSet
import dev.drosh.domain.agent.CatalogState
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.ProviderCatalogIds
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.components.GlassPill
import dev.drosh.ui.agent.components.ActionButton
import dev.drosh.ui.agent.components.ConfigFields
import dev.drosh.ui.agent.components.CustomBaseUrlField
import dev.drosh.ui.agent.components.EffortPicker
import dev.drosh.ui.agent.components.FlatButton
import dev.drosh.ui.agent.components.IconAction
import dev.drosh.ui.agent.components.ModelPicker
import dev.drosh.ui.agent.components.ProviderPicker
import dev.drosh.ui.agent.components.SectionHeader
import dev.drosh.ui.agent.components.TOUCH_TARGET

/**
 * Agent settings: the provider, its key, the model, and how hard it thinks.
 *
 * Reached from Agent Home, from the chat's top bar, and from the error banner when
 * a run cannot start — the three moments a user discovers they need it.
 *
 * ## Why the provider is a row and not a fixed heading
 *
 * It used to be a heading, because there was exactly one provider. With 225 the
 * choice has to be the first thing on the screen and everything below it depends
 * on it: which key field appears, which extra config fields appear, whether an
 * effort selector is offered at all. All of that is read from the selected
 * provider rather than defaulted.
 */
@Composable
fun AgentSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AgentSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // The picker covers the screen rather than opening a route: choosing a
    // provider is the first half of configuring one, and the user comes back to
    // the same screen with the rest already laid out for whichever they picked.
    var showProviderPicker by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsTopBar(onBack = onBack)

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (val catalogState = state.catalogState) {
                    is CatalogState.Loading -> item(key = "catalog_loading") {
                        Text(
                            text = "Provider kataloğu yükleniyor…",
                            fontSize = 12.sp,
                            color = DroshTextMuted,
                        )
                    }

                    is CatalogState.Failed -> item(key = "catalog_error") {
                        CatalogFailure(
                            message = catalogState.message,
                            retryable = catalogState.retryable,
                            onRetry = viewModel::refreshCatalog,
                        )
                    }

                    is CatalogState.Ready -> Unit
                }

                item(key = "provider_header") {
                    SectionHeader("PROVIDER")
                }

                item(key = "provider") {
                    ProviderSummary(
                        provider = state.provider,
                        onClick = { showProviderPicker = true },
                    )
                }

                state.provider?.let { provider ->
                    if (provider.unsupportedReason != null) {
                        item(key = "unsupported") {
                            Text(
                                text = provider.unsupportedReason,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                color = DroshError,
                            )
                        }
                    }

                    item(key = "config") {
                        ConfigFields(
                            fields = provider.configFields,
                            onValueChange = viewModel::setConfigValue,
                        )
                    }

                    // The custom endpoint is the one provider configured entirely
                    // by hand, so its URL field lives with the rest of its config.
                    if (provider.id == ProviderCatalogIds.CUSTOM) {
                        item(key = "custom_url") {
                            CustomBaseUrlField(
                                value = provider.baseUrl,
                                onValueChange = { viewModel.setCustomBaseUrl(it) },
                            )
                        }
                    }
                }

                item(key = "key") {
                    ApiKeyField(
                        label = state.provider?.keyEnvName ?: "API anahtarı",
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

                item(key = "search") {
                    SearchKeyField(
                        hasKey = state.hasSearchKey,
                        onSave = viewModel::saveSearchKey,
                        onClear = viewModel::clearSearchKey,
                    )
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

                // The picker owns its own search field and its own empty state,
                // so the screen does not repeat either.
                item(key = "models") {
                    ModelPicker(
                        models = state.models,
                        selectedId = state.selectedModelId.ifBlank { null },
                        onSelect = viewModel::selectModel,
                    )
                }

                item(key = "effort") {
                    EffortPicker(
                        efforts = state.efforts,
                        selected = state.selectedEffort,
                        onSelect = viewModel::selectEffort,
                    )
                }

                item(key = "hint") {
                    Text(
                        text = "Model, sohbetin üstündeki model düğmesinden de değiştirilir. " +
                            "Agent simgesi yalnızca terminal ekranındaki agent düğmesinde " +
                            "görünür — ayarlarda değil.",
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        color = DroshTextMuted,
                        modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
                    )
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
                    enabled = state.provider != null && !state.loadingModels,
                )
            }
        }

        if (showProviderPicker) {
            ProviderPickerScreen(
                providers = state.providers,
                selectedId = state.selectedProviderId,
                onSelect = { id ->
                    showProviderPicker = false
                    viewModel.selectProvider(id)
                },
                onDismiss = { showProviderPicker = false },
            )
        }
    }
}

@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
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
        // No agent mark: the title says it. See the chat screen's empty state.
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Agent ayarları",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = DroshText,
        )
    }
}

/**
 * The catalog fetch failed.
 *
 * A first launch with no network lands here, and the retry is the only way out
 * of it — so it is a button rather than a message.
 */
@Composable
private fun CatalogFailure(
    message: String,
    retryable: Boolean,
    onRetry: () -> Unit,
) {
    Column {
        Text(
            text = "Provider kataloğu alınamadı.",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = DroshError,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = message,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            color = DroshTextMuted,
        )
        if (retryable) {
            Spacer(Modifier.height(10.dp))
            ActionButton(
                text = "Tekrar dene",
                onClick = onRetry,
            )
        }
    }
}

/** The provider being edited. */
@Composable
private fun ProviderSummary(provider: LlmProvider?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TOUCH_TARGET)
            .clip(RoundedCornerShape(10.dp))
            .background(DroshSurfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { contentDescription = provider?.label ?: "Provider seç" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider?.label ?: "Provider seç",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = DroshText,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = provider?.keyEnvName
                    ?: provider?.baseUrl?.ifBlank { null }
                    ?: "225 provider arasından seç",
                fontSize = 11.sp,
                color = DroshTextMuted,
            )
        }
        Icon(
            imageVector = DroshIcons.ChevronRight,
            contentDescription = null,
            tint = DroshTextMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** The full-screen provider picker. */
@Composable
private fun ProviderPickerScreen(
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
                contentDescription = "Geri",
                onClick = onDismiss,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Provider seç",
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

@Composable
private fun ApiKeyField(
    label: String,
    hasKey: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    loadingModels: Boolean,
) {
    var key by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }

    Column {
        Text(
            text = label,
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
                        text = if (hasKey) "kayıtlı" else "sk-…",
                        fontSize = 13.sp,
                        fontFamily = LocalFontSet.current.mono,
                        color = DroshTextMuted,
                    )
                }
                BasicTextField(
                    value = key,
                    onValueChange = { key = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, fontFamily = LocalFontSet.current.mono, color = DroshText),
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
                        .semantics { contentDescription = label },
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

/**
 * The web-search key.
 *
 * Its own field rather than a row in the provider list, because it is a
 * different kind of secret: it is never sent to a model, and leaving it out
 * disables one tool rather than the agent.
 */
@Composable
private fun SearchKeyField(
    hasKey: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var key by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.padding(top = 4.dp)) {
        SectionHeader("WEB ARAMA")
        Text(
            text = "Dışarıdaki bilgiler için. Anahtarsız da çalışır — agent sadece " +
                "bu cihazdaki dosyalarla sınırlı kalır.",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = DroshTextSecondary,
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(DroshSurfaceVariant)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (key.isEmpty()) {
                    Text(
                        text = if (hasKey) "exa-… (kayıtlı)" else "exa-…",
                        fontSize = 13.sp,
                        fontFamily = LocalFontSet.current.mono,
                        color = DroshTextMuted,
                    )
                }
                BasicTextField(
                    value = key,
                    onValueChange = { key = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 13.sp,
                        fontFamily = LocalFontSet.current.mono,
                        color = DroshText,
                    ),
                    cursorBrush = SolidColor(DroshPrimary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (visible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .semantics { contentDescription = "Exa API anahtarı" },
                )
            }
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasKey) {
                FlatButton(
                    text = "Sil",
                    onClick = onClear,
                    modifier = Modifier.width(96.dp),
                    tint = DroshError,
                )
            }
            ActionButton(
                text = if (hasKey) "Güncelle" else "Kaydet",
                onClick = { onSave(key) },
                enabled = key.isNotBlank(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}
