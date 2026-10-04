package dev.drosh.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.drosh.domain.agent.LlmModel
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.LlmProviderRepository
import dev.drosh.domain.agent.ToolCredentialRepository
import dev.drosh.domain.agent.ToolService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Agent settings: the API key and the model.
 *
 * Separate from the chat's ViewModel on purpose. The settings screen is reachable
 * from Agent Home and from a failed run, both of which have no chat, so sharing
 * the chat's ViewModel meant the screen inherited chat state it had no use for —
 * and made its lifetime depend on a route it does not belong to.
 *
 * ## Why the provider id is a constant here
 *
 * OpenRouter is the only built-in provider, so nothing here needs to be told
 * which one it is editing. When a second provider arrives this gains a selector,
 * and the credential methods already take a provider id — nothing else changes.
 */
@HiltViewModel
class AgentSettingsViewModel @Inject constructor(
    private val providers: LlmProviderRepository,
    private val toolCredentials: ToolCredentialRepository,
) : ViewModel() {

    data class State(
        val provider: LlmProvider? = null,
        val selectedModelId: String = "",
        val models: List<LlmModel> = emptyList(),
        val hasKey: Boolean = false,
        /** Whether an Exa key is stored, so the tool can search the web. */
        val hasSearchKey: Boolean = false,
        val loadingModels: Boolean = false,
        /** Non-null while something failed; shown next to the field that caused it. */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Set once the provider has been resolved.
     *
     * The provider is configuration, not state: there is exactly one and it does
     * not change while the screen is open. Resolving it into a field rather than
     * into [State] means a save that arrives before the load completes still has a
     * provider to write against, instead of being dropped on the floor.
     */
    private var resolvedProvider: LlmProvider? = null

    init {
        load()
    }

    /**
     * Store the web-search key.
     *
     * Same read-back as the provider key: the screen's indicator is derived from
     * what is stored, not from what was typed, so it cannot claim a key that a
     * blank or whitespace-only submission removed.
     */
    fun saveSearchKey(key: String) {
        viewModelScope.launch {
            toolCredentials.setCredential(ToolService.EXA, key.trim())
            _state.value = _state.value.copy(hasSearchKey = toolCredentials.credential(ToolService.EXA) != null)
        }
    }

    fun clearSearchKey() {
        viewModelScope.launch {
            toolCredentials.clearCredential(ToolService.EXA)
            _state.value = _state.value.copy(hasSearchKey = false)
        }
    }

    fun load() {
        viewModelScope.launch {
            val provider = providers.provider(DEFAULT_PROVIDER_ID)
            if (provider == null) {
                _state.value = _state.value.copy(error = "OpenRouter is not configured")
                return@launch
            }
            resolvedProvider = provider
            _state.value = _state.value.copy(
                provider = provider,
                hasKey = providers.credential(provider.id) != null,
                hasSearchKey = toolCredentials.credential(ToolService.EXA) != null,
                selectedModelId = providers.selectedModel(provider.id).orEmpty(),
            )
        }
    }

    /**
     * Store the key, then fetch the model list.
     *
     * `hasKey` is read back from the repository rather than inferred from the
     * text that was typed. The two can disagree — an all-whitespace value clears
     * the credential, a trimmed one differs from the raw input — and the screen's
     * placeholder is derived from this flag, so inferring it would show "saved" for
     * a key that was never stored.
     */
    fun saveApiKey(key: String) {
        val provider = resolvedProvider ?: return
        viewModelScope.launch {
            providers.setCredential(provider.id, key.trim())
            val stored = providers.credential(provider.id)
            _state.value = _state.value.copy(
                hasKey = stored != null,
                selectedModelId = providers.selectedModel(provider.id)
                    ?: _state.value.selectedModelId,
                error = null,
            )
            // Fetching afterwards is what makes saving feel like it did something:
            // the user sees their key take effect instead of reopening the screen
            // to find out.
            if (stored != null) fetchModels()
        }
    }

    fun clearApiKey() {
        val provider = resolvedProvider ?: return
        viewModelScope.launch {
            providers.clearCredential(provider.id)
            _state.value = _state.value.copy(hasKey = false, models = emptyList())
        }
    }

    fun fetchModels() {
        val provider = resolvedProvider ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingModels = true, error = null)
            val models = providers.fetchModels(provider.id)
            _state.value = _state.value.copy(
                models = models,
                loadingModels = false,
                // A provider whose model list is unreachable is not a broken screen —
                // the key can still be entered by hand and typed into the model field.
                error = if (models.isEmpty()) "Model listesi alınamadı" else null,
            )
        }
    }

    fun selectModel(modelId: String) {
        val provider = resolvedProvider ?: return
        _state.value = _state.value.copy(selectedModelId = modelId)
        viewModelScope.launch { providers.setSelectedModel(provider.id, modelId) }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    companion object {
        const val DEFAULT_PROVIDER_ID = "openrouter"
    }
}
