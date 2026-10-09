package dev.drosh.ui.agent.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshSurfaceLow
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.domain.agent.ConfigField
import dev.drosh.domain.agent.LlmModel
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.agent.components.ActionButton
import dev.drosh.ui.agent.components.IconAction
import dev.drosh.ui.agent.components.SectionHeader
import dev.drosh.ui.agent.components.TOUCH_TARGET

/**
 * Searchable pickers over the provider catalog.
 *
 * ## Why these are searchable
 *
 * The catalog is 225 providers and 8453 models. A bare list is usable for the
 * dozen providers anyone has heard of and useless for the other 213 — and
 * amazon-bedrock alone has 199 models, which no one scrolls. Both lists
 * virtualise through [LazyColumn] and filter as the user types.
 *
 * ## Why one field, not two screens
 *
 * The provider and model pickers are separate [Column]s in the same bottom
 * sheet rather than navigable routes. Going back and forth between them while
 * comparing two providers is the main thing a user does here, and a navigation
 * hop per comparison is friction for no benefit.
 */

/** A provider row, chosen. */
@Composable
internal fun ProviderRow(
    provider: LlmProvider,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) DroshSurfaceVariant else DroshSurfaceLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = provider.label,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = DroshText,
            )
            Text(
                // The env var name is the useful part of the label: it tells the
                // user which key they are about to paste.
                text = provider.keyEnvName
                    ?: provider.baseUrl.ifBlank { CUSTOM_ROW_HINT },
                fontSize = 11.sp,
                color = DroshTextMuted,
            )
        }

        // Why the provider is unusable, rather than silently not working.
        provider.unsupportedReason?.let { reason ->
            Text(
                text = reason,
                fontSize = 10.sp,
                color = DroshTextMuted,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        if (selected) {
            Icon(
                imageVector = DroshIcons.Check,
                contentDescription = "Seçili",
                tint = DroshText,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(16.dp),
            )
        }
    }
}

/** The searchable provider list. */
@Composable
internal fun ProviderPicker(
    providers: List<LlmProvider>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Owned here rather than hoisted: the filter is transient typing state that
    // nothing above the list needs to see.
    var query by remember { mutableStateOf("") }

    val visible = remember(providers, query) {
        if (query.isBlank()) {
            providers
        } else {
            val needle = query.trim()
            providers.filter { provider ->
                provider.label.contains(needle, ignoreCase = true) ||
                    provider.id.contains(needle, ignoreCase = true) ||
                    provider.keyEnvName?.contains(needle, ignoreCase = true) == true
            }
        }
    }

    Column(modifier = modifier) {
        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Provider ara — 225 adet",
        )

        ProviderPickerEmptyStates(providers = providers, visibleCount = visible.size)

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Always first, and always present: it is the escape hatch for
            // anything the catalog does not cover, so it should not be something
            // the user has to find.
            item(key = "custom") {
                ProviderRow(
                    provider = CUSTOM_ROW,
                    selected = selectedId == CUSTOM_ROW.id,
                    onClick = { onSelect(CUSTOM_ROW.id) },
                )
            }
            items(visible, key = { it.id }) { provider ->
                ProviderRow(
                    provider = provider,
                    selected = provider.id == selectedId,
                    onClick = { onSelect(provider.id) },
                )
            }
        }
    }
}

/** Explains an empty list, which has three different causes. */
@Composable
private fun ProviderPickerEmptyStates(providers: List<LlmProvider>, visibleCount: Int) {
    when {
        // Nothing has arrived at all: the fetch is in flight or failed.
        providers.isEmpty() -> Text(
            text = "Katalog yükleniyor…",
            fontSize = 12.sp,
            color = DroshTextMuted,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        visibleCount == 0 -> Text(
            // A provider whose endpoint needs a value the user has not supplied is
            // still shown by `providers`, so this really is "no match".
            text = "Eşleşen provider yok",
            fontSize = 12.sp,
            color = DroshTextMuted,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

/** The custom endpoint's row, before a URL has been stored for it. */
private const val CUSTOM_ROW_HINT = "Ollama, vLLM, kendi sunucun"

private val CUSTOM_ROW = LlmProvider(
    id = "custom",
    label = "Custom endpoint",
    kind = dev.drosh.domain.agent.ProviderKind.OPENAI_COMPAT,
    baseUrl = "",
    keyEnvName = null,
)

/** A model row. */
@Composable
internal fun ModelRow(
    model: LlmModel,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) DroshSurfaceVariant else DroshSurfaceLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.label ?: model.id,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = DroshText,
            )
            // The id, not the label: it is what a user has to type into a custom
            // endpoint, and it is what appears in a provider's error message.
            if (model.label != null && model.label != model.id) {
                Text(
                    text = model.id,
                    fontSize = 10.sp,
                    color = DroshTextMuted,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = DroshIcons.Check,
                contentDescription = "Seçili",
                tint = DroshText,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(16.dp),
            )
        }
    }
}

/** The searchable model list. */
@Composable
internal fun ModelPicker(
    models: List<LlmModel>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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

    Column(modifier = modifier) {
        SearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Model ara — ${models.size} adet",
        )

        if (visible.isEmpty()) {
            Text(
                text = if (models.isEmpty()) "Model listesi boş" else "Eşleşen model yok",
                fontSize = 12.sp,
                color = DroshTextMuted,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(visible, key = { it.id }) { model ->
                ModelRow(
                    model = model,
                    selected = model.id == selectedId,
                    onClick = { onSelect(model.id) },
                )
            }
        }
    }
}

/**
 * The effort selector.
 *
 * Rendered only when the selected model offers at least one level; a model that
 * does no reasoning gets no selector rather than one that does nothing.
 */
@Composable
internal fun EffortPicker(
    efforts: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (efforts.isEmpty()) return

    Column(modifier = modifier) {
        SectionHeader("DÜŞÜNME EFFORTU")

        // "Varsayılan" first: the model's own default is a real choice and is
        // what a user who never touched this should be on.
        EffortPill("Varsayılan", selected == null) { onSelect(null) }

        efforts.forEach { effort ->
            EffortPill(effort, selected == effort) { onSelect(effort) }
        }
    }
}

@Composable
private fun EffortPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) DroshSurfaceVariant else DroshSurfaceLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = DroshText,
        )
    }
}

/**
 * Extra config fields a provider needs beyond its key.
 *
 * Rendered from what the catalog's endpoint template actually names, so a
 * provider asking for `CLOUDFLARE_ACCOUNT_ID` gets one field and one that asks
 * for nothing gets none — nothing is hardcoded per provider.
 */
@Composable
internal fun ConfigFields(
    fields: List<ConfigField>,
    onValueChange: (key: String, value: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (fields.isEmpty()) return

    Column(modifier = modifier) {
        SectionHeader("EK AYARLAR")
        fields.forEach { field ->
            ConfigFieldRow(field = field, onValueChange = onValueChange)
        }
    }
}

@Composable
private fun ConfigFieldRow(
    field: ConfigField,
    onValueChange: (String, String) -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 10.dp)) {
        Text(
            text = field.label,
            fontSize = 11.sp,
            color = DroshTextSecondary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        SearchField(
            value = field.value,
            onValueChange = { onValueChange(field.key, it) },
            placeholder = field.label,
        )
    }
}

/** The custom endpoint's base URL field. */
@Composable
internal fun CustomBaseUrlField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "Base URL",
            fontSize = 11.sp,
            color = DroshTextSecondary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        SearchField(
            value = value,
            onValueChange = onValueChange,
            placeholder = "https://localhost:11434/v1",
        )
    }
}

/**
 * The search input shared by both pickers.
 *
 * A plain [BasicTextField] rather than `OutlinedTextField` because the rest of
 * this screen is built from the same primitives and a Material text field here
 * would be the only one of its kind in the app.
 */
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(DroshSurfaceLow)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { contentDescription = placeholder },
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                fontSize = 13.sp,
                color = DroshTextMuted,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, color = DroshText),
            cursorBrush = SolidColor(DroshText),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Text,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
