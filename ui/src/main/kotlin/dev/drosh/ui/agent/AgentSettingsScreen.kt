package dev.drosh.ui.agent

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
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshBorderSubtle
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.DroshTile
import dev.drosh.design.system.DroshTilePressed
import dev.drosh.design.system.DroshTrack
import dev.drosh.design.system.LocalFontSet
import dev.drosh.domain.agent.CatalogState
import dev.drosh.domain.agent.LlmModel
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.ProviderCatalogIds
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.ui.agent.components.IconAction
import dev.drosh.ui.agent.components.ProviderPicker
import dev.drosh.ui.agent.components.TOUCH_TARGET
import dev.drosh.ui.settings.GROUP_GAP
import dev.drosh.ui.settings.SettingsCategoryScreen
import dev.drosh.ui.settings.SettingsCheck
import dev.drosh.ui.settings.SettingsChevron
import dev.drosh.ui.settings.SettingsGroupColumn
import dev.drosh.ui.settings.SettingsSelectRow
import dev.drosh.ui.settings.SettingsTextFieldRow
import dev.drosh.ui.settings.SettingsTile
import dev.drosh.ui.settings.TileCap

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
 *
 * ## Same tiles as every other settings screen
 *
 * The rows, the groups, the corner radius that joins them, the text dialog and
 * the picker sheet are the ones the category screens are built from — one
 * visual language, not a second one for the agent. A key or a config value is
 * edited in the same dialog the startup command is, and the model list is a
 * sheet over the same tiles rather than a list bolted onto a scrolling page.
 */
@Composable
fun AgentSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AgentSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val provider = state.provider

    // The picker covers the screen rather than opening a route: choosing a
    // provider is the first half of configuring one, and the user comes back to
    // the same screen with the rest already laid out for whichever they picked.
    var showProviderPicker by rememberSaveable { mutableStateOf(false) }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var showKeyDialog by rememberSaveable { mutableStateOf(false) }
    var showSearchKeyDialog by rememberSaveable { mutableStateOf(false) }

    SettingsCategoryScreen(
        title = stringResource(R.string.settings_agent),
        onBack = onBack,
        modifier = modifier,
    ) {
        // ── Catalog status ──────────────────────────────────────────────────
        when (val catalogState = state.catalogState) {
            is CatalogState.Loading -> {
                SettingsCaption(
                    text = stringResource(R.string.agent_settings_catalog_loading),
                )
                Spacer(Modifier.height(GROUP_GAP))
            }

            is CatalogState.Failed -> {
                CatalogFailure(message = catalogState.message)
                if (catalogState.retryable) {
                    SettingsGroupColumn(label = null, items = 1) { _, cap ->
                        SettingsTile(
                            cap = cap,
                            title = stringResource(R.string.action_retry),
                            icon = DroshIcons.RotateCw,
                            onClick = viewModel::refreshCatalog,
                        )
                    }
                }
                Spacer(Modifier.height(GROUP_GAP))
            }

            is CatalogState.Ready -> Unit
        }

        // ── Provider ────────────────────────────────────────────────────────
        val configFields = provider?.configFields.orEmpty()
        val customUrl = provider != null && provider.id == ProviderCatalogIds.CUSTOM

        SettingsGroupColumn(
            label = stringResource(R.string.agent_settings_section_provider),
            items = 1 + configFields.size + if (customUrl) 1 else 0,
        ) { index, cap ->
            when {
                index == 0 -> SettingsTile(
                    cap = cap,
                    title = provider?.label
                        ?: stringResource(R.string.agent_settings_provider_select),
                    icon = DroshIcons.Globe,
                    // What a key is about to be pasted for: the env var name
                    // when the catalog names one, the endpoint when it does not.
                    supporting = provider?.keyEnvName
                        ?: provider?.baseUrl?.ifBlank { null }
                        ?: stringResource(R.string.agent_settings_provider_choose),
                    onClick = { showProviderPicker = true },
                ) { SettingsChevron() }

                // The custom endpoint is the one provider configured entirely by
                // hand, so its URL field lives with the rest of its config.
                customUrl && index == configFields.size + 1 -> SettingsTextFieldRow(
                    cap = cap,
                    title = stringResource(R.string.agent_settings_base_url),
                    value = provider?.baseUrl.orEmpty(),
                    onConfirm = viewModel::setCustomBaseUrl,
                    placeholder = stringResource(R.string.agent_settings_base_url_placeholder),
                    supportingWhenBlank = stringResource(R.string.agent_settings_not_set),
                )

                else -> {
                    val field = configFields[index - 1]
                    SettingsTextFieldRow(
                        cap = cap,
                        title = field.label,
                        value = field.value,
                        onConfirm = { viewModel.setConfigValue(field.key, it) },
                        placeholder = field.label,
                        supportingWhenBlank = stringResource(R.string.agent_settings_not_set),
                    )
                }
            }
        }

        // Why the provider is unusable, rather than silently not working.
        val unsupported = provider?.unsupportedReason
        if (unsupported != null) {
            SettingsCaption(text = unsupported, color = DroshError)
        }

        Spacer(Modifier.height(GROUP_GAP))

        // ── API key ─────────────────────────────────────────────────────────
        if (provider != null) {
            SettingsGroupColumn(
                label = stringResource(R.string.agent_settings_section_api_key),
                items = if (state.hasKey) 2 else 1,
            ) { index, cap ->
                if (index == 0) {
                    SettingsTile(
                        cap = cap,
                        // The env var name is the useful label: it tells the user
                        // which key they are about to paste.
                        title = provider.keyEnvName
                            ?: stringResource(R.string.agent_settings_api_key),
                        icon = DroshIcons.Lock,
                        supporting = if (state.hasKey) {
                            stringResource(R.string.agent_settings_key_saved)
                        } else {
                            stringResource(R.string.agent_settings_key_placeholder)
                        },
                        onClick = { showKeyDialog = true },
                    ) { SettingsChevron() }
                } else {
                    SettingsTile(
                        cap = cap,
                        title = stringResource(R.string.agent_settings_key_delete),
                        icon = DroshIcons.Trash2,
                        onClick = viewModel::clearApiKey,
                    )
                }
            }
            SettingsCaption(
                text = stringResource(
                    if (state.hasKey) {
                        R.string.agent_settings_key_stored_note
                    } else {
                        R.string.agent_settings_key_encrypted_note
                    },
                ),
            )
            Spacer(Modifier.height(GROUP_GAP))
        }

        state.error?.let { message ->
            SettingsCaption(text = message, color = DroshError)
        }

        // ── Web search ──────────────────────────────────────────────────────
        SettingsGroupColumn(
            label = stringResource(R.string.agent_settings_section_search),
            items = if (state.hasSearchKey) 2 else 1,
        ) { index, cap ->
            if (index == 0) {
                SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.agent_settings_search_key),
                    icon = DroshIcons.Search,
                    supporting = if (state.hasSearchKey) {
                        stringResource(R.string.agent_settings_key_saved)
                    } else {
                        stringResource(R.string.agent_settings_search_key_placeholder)
                    },
                    onClick = { showSearchKeyDialog = true },
                ) { SettingsChevron() }
            } else {
                SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.agent_settings_search_key_delete),
                    icon = DroshIcons.Trash2,
                    onClick = viewModel::clearSearchKey,
                )
            }
        }
        SettingsCaption(text = stringResource(R.string.agent_settings_search_desc))

        Spacer(Modifier.height(GROUP_GAP))

        // ── Model ───────────────────────────────────────────────────────────
        val selectedModel = state.models.firstOrNull { it.id == state.selectedModelId }
        val effortDefault = stringResource(R.string.agent_settings_effort_default)
        val effortOptions: List<Pair<String?, String>> =
            listOf(null to effortDefault) + state.efforts.map { it to it }

        SettingsGroupColumn(
            label = stringResource(R.string.agent_settings_section_model),
            items = 2 + if (state.efforts.isNotEmpty()) 1 else 0,
        ) { index, cap ->
            when (index) {
                0 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.agent_model),
                    icon = DroshIcons.Code,
                    // The picker owns its own search field and its own empty
                    // state, so this row says which model is current and
                    // nothing else.
                    supporting = selectedModel?.let { it.label ?: it.id }
                        ?: stringResource(R.string.agent_select_model),
                    onClick = { showModelPicker = true },
                ) { SettingsChevron() }

                1 -> SettingsTile(
                    cap = cap,
                    title = stringResource(R.string.agent_settings_refresh_models),
                    icon = DroshIcons.RotateCw,
                    supporting = if (state.loadingModels) {
                        stringResource(R.string.agent_models_loading)
                    } else {
                        null
                    },
                    onClick = viewModel::fetchModels,
                )

                else -> SettingsSelectRow(
                    cap = cap,
                    title = stringResource(R.string.agent_settings_effort),
                    icon = DroshIcons.Gauge,
                    options = effortOptions,
                    selected = state.selectedEffort,
                    onSelect = viewModel::selectEffort,
                )
            }
        }

        SettingsCaption(text = stringResource(R.string.agent_settings_model_hint))

        // ── Trust ───────────────────────────────────────────────────────────
        // Last, because it is the setting with the most consequences and the
        // one whose caption matters most: it has to say what the level does
        // *not* change as well as what it does.
        AgentTrustSection()
    }

    if (showKeyDialog) {
        KeyInputDialog(
            title = state.provider?.keyEnvName
                ?: stringResource(R.string.agent_settings_api_key),
            placeholder = stringResource(R.string.agent_settings_key_placeholder),
            onDismiss = { showKeyDialog = false },
            onConfirm = viewModel::saveApiKey,
        )
    }

    if (showSearchKeyDialog) {
        KeyInputDialog(
            title = stringResource(R.string.agent_settings_search_key),
            placeholder = stringResource(R.string.agent_settings_search_key_placeholder),
            onDismiss = { showSearchKeyDialog = false },
            onConfirm = viewModel::saveSearchKey,
        )
    }

    if (showModelPicker) {
        ModelPickerSheet(
            models = state.models,
            selectedId = state.selectedModelId.ifBlank { null },
            onSelect = viewModel::selectModel,
            onDismiss = { showModelPicker = false },
        )
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

/** A line of explanation beside a group, in the voice of the tiles. */
@Composable
private fun SettingsCaption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = DroshTextMuted,
) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        modifier = modifier.padding(horizontal = 22.dp, vertical = 4.dp),
    )
}

/**
 * The catalog fetch failed.
 *
 * A first launch with no network lands here, and the retry is the only way out
 * of it — so the retry is a row in the same language as everything else rather
 * than a bare button.
 */
@Composable
private fun CatalogFailure(message: String) {
    Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.agent_settings_catalog_failed),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = DroshError,
        )
        Text(
            text = message,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
            color = DroshTextMuted,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * One secret, entered in a dialog.
 *
 * Masked by default — a key is visible on someone's shoulder — and wearing the
 * same surface and buttons as the settings text dialog, because a dialog that
 * looked like something else would make the one field that holds a secret look
 * like an ordinary one.
 */
@Composable
private fun KeyInputDialog(
    title: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DroshSurfaceVariant,
        shape = RoundedCornerShape(20.dp),
        title = { Text(title, color = DroshText, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(placeholder, color = DroshTextMuted) },
                textStyle = TextStyle(
                    color = DroshText,
                    fontSize = 14.sp,
                    fontFamily = LocalFontSet.current.mono,
                ),
                visualTransformation = if (visible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            imageVector = if (visible) DroshIcons.EyeOff else DroshIcons.Eye,
                            contentDescription = stringResource(
                                if (visible) {
                                    R.string.agent_settings_key_hide
                                } else {
                                    R.string.agent_settings_key_show
                                },
                            ),
                            tint = DroshTextSecondary,
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DroshPrimary,
                    unfocusedBorderColor = DroshBorderSubtle,
                    cursorColor = DroshPrimary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            // Enabled on any non-blank input, including whitespace — the view
            // model trims and treats an emptied result as a request to clear.
            // An untouched dialog is not that request.
            TextButton(
                onClick = {
                    onConfirm(text.trim())
                    onDismiss()
                },
                enabled = text.isNotBlank(),
            ) {
                Text(
                    text = stringResource(R.string.settings_dialog_save),
                    color = if (text.isNotBlank()) DroshPrimary else DroshTextMuted,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.settings_dialog_cancel),
                    color = DroshTextSecondary,
                )
            }
        },
    )
}

/**
 * The model list, as a sheet over the settings tiles.
 *
 * The list used to sit in the screen itself, which is why the screen could not
 * simply scroll: a lazy list inside a scrolling column measures its height as
 * infinite. As a sheet it owns its own scrolling, keeps the search field a
 * 199-model provider needs, and reads as the same picker the language row
 * opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPickerSheet(
    models: List<LlmModel>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Owned here rather than hoisted: the filter is transient typing state that
    // nothing above the sheet needs to see.
    var query by remember { mutableStateOf("") }

    val visible = remember(models, query) {
        if (query.isBlank()) {
            models
        } else {
            val needle = query.trim()
            models.filter { model ->
                (model.label ?: model.id).contains(needle, ignoreCase = true) ||
                    model.id.contains(needle, ignoreCase = true)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DroshTile,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(DroshTrack),
            )
        },
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Text(
                text = stringResource(R.string.agent_model),
                color = DroshText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 12.dp),
            )

            SheetSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.agent_search_models),
            )

            Spacer(Modifier.size(8.dp))

            if (visible.isEmpty()) {
                Text(
                    text = stringResource(
                        if (models.isEmpty()) {
                            R.string.agent_models_empty
                        } else {
                            R.string.agent_no_models
                        },
                    ),
                    color = DroshTextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }

            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                itemsIndexed(visible, key = { _, model -> model.id }) { index, model ->
                    val selected = model.id == selectedId
                    SettingsTile(
                        cap = tileCap(index, visible.size),
                        title = model.label ?: model.id,
                        // The id, not the label: it is what a user has to type
                        // into a custom endpoint, and what a provider's error
                        // names.
                        supporting = model.label?.takeIf { it != model.id }
                            ?.let { model.id },
                        selected = selected,
                        onClick = {
                            onSelect(model.id)
                            onDismiss()
                        },
                    ) {
                        if (selected) SettingsCheck()
                    }
                }
            }

            Spacer(Modifier.size(8.dp))
        }
    }
}

/** The corner a tile takes at [index] in a run of [count]. */
private fun tileCap(index: Int, count: Int): TileCap = when {
    count <= 1 -> TileCap.Only
    index == 0 -> TileCap.First
    index == count - 1 -> TileCap.Last
    else -> TileCap.Middle
}

/** The search input over the model sheet, in the language of the picker. */
@Composable
private fun SheetSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(DroshTilePressed)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = DroshIcons.Search,
            contentDescription = null,
            tint = DroshTextMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    color = DroshTextMuted,
                    fontSize = 14.5.sp,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = DroshText, fontSize = 14.5.sp),
                cursorBrush = SolidColor(DroshPrimary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
