package dev.drosh.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshTile
import dev.drosh.theme.DroshTilePressed
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTrack
import dev.drosh.domain.agent.CatalogState
import dev.drosh.domain.agent.LlmProviderRepository
import dev.drosh.domain.settings.SettingsStore
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.ui.settings.SettingsCheck
import dev.drosh.ui.settings.SettingsChevron
import dev.drosh.ui.settings.SettingsTile
import dev.drosh.ui.settings.TileCap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The row that points the jobs nobody asked for at a cheaper model.
 *
 * ## Why a file of its own
 *
 * `AgentSettingsScreen` is owned by another change landing beside this one, and
 * a row folded into it would put the two over the same screen. The row is
 * therefore self-contained and self-wiring: drop [SideJobModelRow] into a
 * `SettingsGroupColumn` and it carries its own state, its own sheet and its own
 * write. The settings screen needs one line; this file needs no edit there.
 *
 * ## Why the list is the catalog and not the provider's models
 *
 * A side job is worth handing to whichever provider has the cheap model, and
 * that is rarely the one in use — see `SideJobModel`, which resolves the stored
 * id against every provider's catalog. Offering only the selected provider's
 * models here would promise a choice the resolution would then undo.
 *
 * ## Why the row is a value and not a switch
 *
 * "Off" is a real answer: a blank setting means the conversation's own model
 * answers, which is what a user who never opened this row gets. So the sheet
 * opens with that choice as its first row, and picking any other model is the
 * whole of the gesture.
 */
@HiltViewModel
class AgentSideJobModelViewModel @Inject constructor(
    private val store: SettingsStore,
    private val providers: LlmProviderRepository,
) : ViewModel() {

    private val _options = MutableStateFlow<List<SideJobModelOption>>(emptyList())

    /** The stored id, and whether the catalog still has it. */
    private val _selectedId = MutableStateFlow("")
    private val _selectedKnown = MutableStateFlow(true)
    /** The last catalog, kept so the row can say "not any more" rather than lie. */
    private var catalog: CatalogState = CatalogState.Loading

    /**
     * What the row and its sheet render.
     *
     * Combined rather than read at open time: the write is a whole-snapshot
     * transform through the store, and the answer has to arrive from the same
     * place an edit from an SSH session would have arrived from.
     */
    val state: StateFlow<SideJobModelRowState> = combine(
        _options,
        _selectedId,
        _selectedKnown,
    ) { options, selectedId, selectedKnown ->
        SideJobModelRowState(
            options = options,
            selectedId = selectedId,
            selectedKnown = selectedKnown,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(),
        SideJobModelRowState(),
    )

    init {
        viewModelScope.launch {
            providers.observeCatalogState().collect { incoming ->
                catalog = incoming
                _options.value = when (incoming) {
                    // Deduplicated by id: the same cheap model is declared by more
                    // than one gateway, and a list with two rows for one model
                    // would ask the user to choose between identical options.
                    // `SideJobModel.resolve` picks the first provider that declares
                    // one, so the first row is the one that would be used.
                    is CatalogState.Ready -> incoming.providers
                        .flatMap { provider ->
                            provider.models.values.map { model ->
                                SideJobModelOption(
                                    id = model.id,
                                    label = model.label,
                                    providerLabel = provider.label,
                                )
                            }
                        }
                        .distinctBy { it.id }

                    is CatalogState.Loading -> emptyList()

                    is CatalogState.Failed -> emptyList()
                }
                refreshSelection()
            }
        }
        // The row follows an edit from a shell as surely as one from the tap:
        // the store watches the file, and this is where that reaches a surface.
        viewModelScope.launch {
            store.settings.collect { refreshSelection() }
        }
    }

    /**
     * Read the stored id, and whether the catalog still has it.
     *
     * The second half is the point: an id no provider declares any more falls
     * back silently to the chat's own model, and a row that showed it as if it
     * were in effect would be a setting the user had set and that was being
     * ignored.
     */
    private fun refreshSelection() {
        val stored = store.settings.value.agentTools.sideJobModel.trim()
        _selectedId.value = stored
        _selectedKnown.value = isKnown(stored)
    }

    private fun isKnown(modelId: String): Boolean {
        // Nothing set is always fine: it is the default, not a broken value.
        if (modelId.isEmpty()) return true
        val providers = (catalog as? CatalogState.Ready)?.providers ?: return true
        return providers.any { it.models.containsKey(modelId) }
    }

    /**
     * Point the side jobs at [modelId], or back at the conversation's own model.
     *
     * The write is whole-snapshot through the store, so the value lands in both
     * the Linux-side file and the app-private copy in one atomic replace — the
     * same path every other setting takes. Blank is written as blank, because a
     * key with an empty value reads as "the user chose the chat model" and an
     * absent key reads as "this build does not know about it".
     */
    fun select(modelId: String) {
        val trimmed = modelId.trim()
        // Shown at once, before the file lands: a row that waited for the disk to
        // answer would sit still under the finger.
        _selectedId.value = trimmed
        _selectedKnown.value = isKnown(trimmed)
        viewModelScope.launch {
            store.update { snap ->
                snap.copy(agentTools = snap.agentTools.copy(sideJobModel = trimmed))
            }
        }
    }
}

/** One model in the picker, with the provider that has it. */
data class SideJobModelOption(
    val id: String,
    val label: String,
    val providerLabel: String,
)

/** What the row and its sheet read. */
data class SideJobModelRowState(
    val options: List<SideJobModelOption> = emptyList(),
    val selectedId: String = "",
    /** False once the stored id is one the catalog no longer has. */
    val selectedKnown: Boolean = true,
) {
    val hasModels: Boolean get() = options.isNotEmpty()

    /** The chosen model, or null for the conversation's own. */
    val selected: SideJobModelOption?
        get() = options.firstOrNull { it.id == selectedId }
}

/**
 * The row, in the language of every other settings screen.
 *
 * Cap it as part of the run it sits in — `SettingsGroupColumn` hands that in.
 */
@Composable
fun SideJobModelRow(
    cap: TileCap,
    modifier: Modifier = Modifier,
    viewModel: AgentSideJobModelViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }

    val chosen = state.selected
    val supporting = when {
        chosen != null -> chosen.label
        // An id the catalog no longer has has to say so: it is already falling
        // back to the chat's own model, and a row that showed it as though it
        // were in effect would be a lie the user can only catch by watching
        // what runs.
        state.selectedId.isNotBlank() && !state.selectedKnown ->
            stringResource(R.string.agent_side_job_model_unavailable)

        // Set, and the catalog has not arrived yet. The id is what the file
        // holds, and a row that claimed "the chat model" while a cheap model
        // was configured would be the same lie in the other direction.
        state.selectedId.isNotBlank() -> state.selectedId

        else -> stringResource(R.string.agent_side_job_model_default)
    }

    SettingsTile(
        cap = cap,
        title = stringResource(R.string.agent_side_job_model),
        modifier = modifier,
        icon = DroshIcons.Gauge,
        supporting = supporting,
        onClick = { picking = true },
    ) { SettingsChevron() }

    if (picking) {
        SideJobModelSheet(
            state = state,
            onSelect = viewModel::select,
            onDismiss = { picking = false },
        )
    }
}

/** The row in the sheet that means "the conversation's own model answers". */
private const val SIDE_JOB_NONE = ""

/**
 * The catalog's models, as a sheet over the settings tiles.
 *
 * The same shape the language row and the model row open, because a picker that
 * looked like something else would read as a different kind of setting.
 *
 * "The chat model" is a row rather than a dismiss gesture: clearing the setting
 * is half of what this row is for, and a user who picked the wrong cheap model
 * has to be able to undo it without editing the file by hand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SideJobModelSheet(
    state: SideJobModelRowState,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    val matches = remember(state.options, query) {
        if (query.isBlank()) {
            state.options
        } else {
            val needle = query.trim()
            state.options.filter { option ->
                option.label.contains(needle, ignoreCase = true) ||
                    option.id.contains(needle, ignoreCase = true) ||
                    option.providerLabel.contains(needle, ignoreCase = true)
            }
        }
    }

    val rows = remember(matches) {
        listOf(SideJobSheetRow(SIDE_JOB_NONE, null)) + matches.map { SideJobSheetRow(it.id, it) }
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
                text = stringResource(R.string.agent_side_job_model),
                color = DroshText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 12.dp),
            )

            SideJobSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.agent_side_job_model_search),
            )

            Spacer(Modifier.size(8.dp))

            if (!state.hasModels) {
                Text(
                    text = stringResource(R.string.agent_side_job_model_empty),
                    color = DroshTextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
            } else if (rows.size == 1) {
                // Nothing but the "chat model" row left: a search that matched
                // nothing. Said plainly, because a blank sheet under a full one
                // reads as a bug rather than as a filter that found nothing.
                Text(
                    text = stringResource(R.string.agent_no_models),
                    color = DroshTextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }

            LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                itemsIndexed(
                    items = rows,
                    // Ids are unique: the option list is deduplicated by id and
                    // the "chat model" row is the empty string, which no model
                    // carries.
                    key = { _, row -> row.id },
                ) { index, row ->
                    val option = row.option
                    val selected = row.id == state.selectedId
                    SettingsTile(
                        cap = sideJobTileCap(index, rows.size),
                        title = option?.label
                            ?: stringResource(R.string.agent_side_job_model_default),
                        // The id, not the label: it is what a hand-edited
                        // settings file has to carry.
                        supporting = option?.id,
                        selected = selected,
                        onClick = {
                            onSelect(row.id)
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

/**
 * One row of the sheet: the model, or nothing.
 *
 * The option is carried rather than looked up per row — the list is the whole
 * catalog, and resolving a model id from it for every row composed was a scan
 * of eight thousand entries ten times over.
 */
private data class SideJobSheetRow(val id: String, val option: SideJobModelOption?)

/** The corner a tile takes at [index] in a run of [count]. */
private fun sideJobTileCap(index: Int, count: Int): TileCap = when {
    count <= 1 -> TileCap.Only
    index == 0 -> TileCap.First
    index == count - 1 -> TileCap.Last
    else -> TileCap.Middle
}

/** The search input over the sheet, in the language of the picker. */
@Composable
private fun SideJobSearchField(
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
