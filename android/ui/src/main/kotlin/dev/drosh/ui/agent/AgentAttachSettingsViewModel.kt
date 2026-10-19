package dev.drosh.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.drosh.domain.agent.LlmProviderRepository
import dev.drosh.domain.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The two settings the composer's `+` sheet turns, and the effort list it offers.
 *
 * ## Why this is not a field of [AgentChatViewModel]
 *
 * They are not about the conversation: they say what the *tools* may do, they
 * outlive any one chat, and they are written to `settings.toml` where a shell or
 * a script reads them back. Holding them in the chat's ViewModel would tie a
 * global preference to whichever chat happened to be open when it was toggled.
 *
 * ## Why a StateFlow rather than a read per open
 *
 * The sheet can be opened while a run is in flight, and the switch has to show
 * what is actually in effect — including a change the settings screen, or a
 * `sed` from an SSH session, made a moment ago.
 */
@HiltViewModel
class AgentAttachSettingsViewModel @Inject constructor(
    private val store: SettingsStore,
    private val providers: LlmProviderRepository,
) : ViewModel() {

    /** Whether `web_search` is offered to the model. */
    val webSearch: StateFlow<Boolean> = store.settings
        .map { it.agentTools.webSearch }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** The reasoning effort in effect, or "" for the model's own default. */
    val effort: StateFlow<String> = store.settings
        .map { it.agentTools.effort }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _efforts = MutableStateFlow<List<String>>(emptyList())

    /**
     * The effort values the selected model actually offers.
     *
     * Read from the provider repository rather than hardcoded, because the
     * catalog is the source of truth for both which values exist and which order
     * they are offered in — and a value the protocol rejects would be a segment
     * that promises something the endpoint will not do.
     */
    val efforts: StateFlow<List<String>> = _efforts.asStateFlow()

    /** Reload the list whenever the model in hand changes. */
    fun loadEfforts(providerId: String?, modelId: String) {
        if (providerId.isNullOrBlank() || modelId.isBlank()) {
            _efforts.value = emptyList()
            return
        }
        viewModelScope.launch {
            _efforts.value = providers.reasoningEfforts(providerId, modelId)
        }
    }

    /**
     * Offer or withdraw the web-search tool.
     *
     * The write is whole-snapshot through the store, so the value lands in both
     * the Linux-side file and the app-private copy in one atomic replace — the
     * same path every other setting takes, which is what makes this survive a
     * restart without a second mechanism.
     */
    fun setWebSearch(enabled: Boolean) {
        viewModelScope.launch {
            store.update { it.copy(agentTools = it.agentTools.copy(webSearch = enabled)) }
        }
    }

    /**
     * Choose a reasoning effort, or clear it with null.
     *
     * Written to `[agent] effort` in the settings file so a shell can see what
     * the agent is being run at. The per-model value the loop actually sends
     * lives in the provider repository, and the chat screen writes both from the
     * same tap so the two cannot disagree.
     */
    fun setEffort(effort: String?) {
        viewModelScope.launch {
            store.update { it.copy(agentTools = it.agentTools.copy(effort = effort.orEmpty())) }
        }
    }
}