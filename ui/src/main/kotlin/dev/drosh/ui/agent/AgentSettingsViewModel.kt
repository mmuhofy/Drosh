package dev.drosh.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.drosh.domain.agent.CatalogProvider
import dev.drosh.domain.agent.CatalogState
import dev.drosh.domain.agent.LlmModel
import dev.drosh.domain.agent.LlmProvider
import dev.drosh.domain.agent.LlmProviderRepository
import dev.drosh.domain.agent.ProviderCatalogIds
import dev.drosh.domain.agent.ToolCredentialRepository
import dev.drosh.domain.agent.ToolService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Agent settings: the provider, its key, the model, and how hard it thinks.
 *
 * Separate from the chat's ViewModel on purpose. The settings screen is reachable
 * from Agent Home and from a failed run, both of which have no chat, so sharing
 * the chat's ViewModel meant the screen inherited chat state it had no use for —
 * and made its lifetime depend on a route it does not belong to.
 *
 * ## Why the provider is now state rather than a constant
 *
 * There used to be exactly one provider, so nothing here needed to be told which
 * one it was editing. The catalog adds 224 more, and a screen that has to choose
 * between them is a different screen: the provider is the first thing it asks
 * about, and everything below it — the key field's label, the extra config
 * fields, the model list, whether effort is offered at all — follows from it.
 */
@HiltViewModel
class AgentSettingsViewModel @Inject constructor(
    private val providers: LlmProviderRepository,
    private val toolCredentials: ToolCredentialRepository,
) : ViewModel() {

    data class State(
        /** What the catalog fetch is doing; drives the empty state. */
        val catalogState: CatalogState = CatalogState.Loading,

        /** Every provider the catalog offers, plus the custom row once it has a URL. */
        val providers: List<LlmProvider> = emptyList(),

        /** Id of the provider being edited. */
        val selectedProviderId: String? = null,

        /** The resolved provider, or null when the catalog has not loaded. */
        val provider: LlmProvider? = null,

        val hasKey: Boolean = false,
        val models: List<LlmModel> = emptyList(),
        val selectedModelId: String = "",

        /**
         * Effort values the selected model may be run at, already narrowed to
         * what its protocol can send. Empty means the model does no reasoning
         * and no selector should appear at all.
         */
        val efforts: List<String> = emptyList(),
        val selectedEffort: String? = null,

        /** Whether an Exa key is stored, so the tool can search the web. */
        val hasSearchKey: Boolean = false,

        val loadingModels: Boolean = false,

        /** Non-null while something failed; shown next to the field that caused it. */
        val error: String? = null,
    ) {
        /** Providers that can actually be used, for the picker's default view. */
        val usableProviders: List<LlmProvider> get() = providers.filter { it.unsupportedReason == null }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Set once the provider has been resolved.
     *
     * The provider is configuration, not state: for a given selection it does not
     * change while the screen is open. Resolving it into a field rather than into
     * [State] means a save that arrives before the load completes still has a
     * provider to write against, instead of being dropped on the floor.
     */
    private var resolvedProvider: LlmProvider? = null

    init {
        observeCatalogState()
        observeProviders()
        load()
    }

    /** Mirror the repository's catalog state into [State] for the screen. */
    private fun observeCatalogState() {
        viewModelScope.launch {
            providers.observeCatalogState().collect { catalogState ->
                _state.value = _state.value.copy(catalogState = catalogState)

                // Whatever is selected becomes resolvable the moment the catalog
                // lands. Without this the screen resolves once on open, finds
                // nothing, and stays blank while a full list sits behind it.
                val id = _state.value.selectedProviderId ?: return@collect
                if (catalogState is CatalogState.Failed) return@collect
                applySelection(id)
            }
        }
    }

    private fun observeProviders() {
        viewModelScope.launch {
            providers.observeProviders().collect { list ->
                _state.value = _state.value.copy(providers = list)

                // Re-resolve only what the change could have affected. A config
                // value being filled in changes the provider's endpoint, so the
                // resolved entry is refreshed rather than cached for the screen's
                // lifetime.
                val id = _state.value.selectedProviderId ?: return@collect
                if (list.none { it.id == id }) {
                    // The selection is gone — the custom row's URL was cleared.
                    _state.value = _state.value.copy(
                        selectedProviderId = null,
                        provider = null,
                        models = emptyList(),
                        efforts = emptyList(),
                        selectedEffort = null,
                    )
                    return@collect
                }
                applySelection(id)
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            val id = providers.selectedProvider() ?: DEFAULT_PROVIDER_ID
            _state.value = _state.value.copy(
                selectedProviderId = id,
                hasSearchKey = toolCredentials.credential(ToolService.EXA) != null,
            )
            applySelection(id)
        }
    }

    /**
     * Point the screen at another provider and load everything that depends on it.
     *
     * The key, the model, the effort and the config fields are all per-provider,
     * so switching means reading them again rather than clearing the screen — a
     * user comparing two providers would otherwise lose each one's settings.
     */
    fun selectProvider(providerId: String) {
        if (providerId == _state.value.selectedProviderId) return
        viewModelScope.launch {
            providers.setSelectedProvider(providerId)
            _state.value = _state.value.copy(
                selectedProviderId = providerId,
                error = null,
                models = emptyList(),
                efforts = emptyList(),
                selectedModelId = "",
                selectedEffort = null,
            )
            applySelection(providerId)
        }
    }

    /** Store one non-secret config value and re-resolve the endpoint. */
    fun setConfigValue(key: String, value: String) {
        val provider = resolvedProvider ?: return
        viewModelScope.launch {
            providers.setConfigValue(provider.id, key, value)
            applySelection(provider.id)
        }
    }

    /** Store the custom endpoint's base URL, creating the row. */
    fun setCustomBaseUrl(baseUrl: String) {
        viewModelScope.launch {
            providers.setCustomBaseUrl(baseUrl)
            if (baseUrl.isNotBlank()) {
                providers.setSelectedProvider(ProviderCatalogIds.CUSTOM)
                _state.value = _state.value.copy(
                    selectedProviderId = ProviderCatalogIds.CUSTOM,
                    error = null,
                )
                applySelection(ProviderCatalogIds.CUSTOM)
            }
        }
    }

    /**
     * Store the key, then load the model list.
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
                error = null,
                models = if (stored == null) emptyList() else _state.value.models,
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

    fun saveSearchKey(key: String) {
        viewModelScope.launch {
            toolCredentials.setCredential(ToolService.EXA, key.trim())
            _state.value = _state.value.copy(
                hasSearchKey = toolCredentials.credential(ToolService.EXA) != null,
            )
        }
    }

    fun clearSearchKey() {
        viewModelScope.launch {
            toolCredentials.clearCredential(ToolService.EXA)
            _state.value = _state.value.copy(hasSearchKey = false)
        }
    }

    /**
     * Load the model list.
     *
     * Served from the catalog, so this is instant and needs no key — the key gates
     * *running* a model, not *listing* them. The old implementation required a key
     * first, which meant a user could not see what a provider offered until they
     * had already committed to it.
     */
    fun fetchModels() {
        val provider = resolvedProvider ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingModels = true, error = null)
            val models = providers.fetchModels(provider.id, forceRefresh = true)
            _state.value = _state.value.copy(
                models = models,
                loadingModels = false,
                // A provider with no usable model list is not a broken screen —
                // the key can still be entered and the model typed by hand.
                error = if (models.isEmpty()) "Model listesi alınamadı" else null,
            )
        }
    }

    fun selectModel(modelId: String) {
        val provider = resolvedProvider ?: return
        _state.value = _state.value.copy(selectedModelId = modelId, error = null)
        viewModelScope.launch {
            providers.setSelectedModel(provider.id, modelId)
            refreshEfforts(provider.id, modelId)
        }
    }

    /**
     * Pick a reasoning-effort level for this model.
     *
     * Stored per model rather than globally: `high` on a reasoning model and
     * `high` on a small model are not the same choice, and a global setting would
     * silently apply one to the other.
     */
    fun selectEffort(effort: String?) {
        val provider = resolvedProvider ?: return
        val model = _state.value.selectedModelId.ifEmpty { return }
        _state.value = _state.value.copy(selectedEffort = effort)
        viewModelScope.launch {
            providers.setReasoningEffort(provider.id, model, effort)
        }
    }

    /** Retry the catalog fetch after a failure. */
    fun refreshCatalog() {
        viewModelScope.launch { providers.refreshCatalog() }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * Resolve [providerId] into everything the screen shows.
     *
     * One place, because every one of these values depends on the same question
     * and they must not be loaded piecemeal — a screen that has the model list
     * from one provider and the effort list from another is worse than a blank one.
     */
    private suspend fun applySelection(providerId: String) {
        val provider = providers.provider(providerId)
        if (provider == null) {
            _state.value = _state.value.copy(
                provider = null,
                hasKey = false,
                models = emptyList(),
                efforts = emptyList(),
                selectedEffort = null,
            )
            return
        }

        resolvedProvider = provider
        val hasKey = providers.credential(provider.id) != null
        val selectedModel = providers.selectedModel(provider.id).orEmpty()
        val models = providers.fetchModels(provider.id)
        val efforts = providers.reasoningEfforts(provider.id, selectedModel)
        val storedEffort = selectedModel.takeIf { it.isNotBlank() }
            ?.let { providers.reasoningEffort(provider.id, it) }

        _state.value = _state.value.copy(
            provider = provider,
            hasKey = hasKey,
            models = models,
            selectedModelId = selectedModel,
            efforts = efforts,
            selectedEffort = storedEffort,
        )
    }

    /**
     * Reload the effort list for a newly chosen model.
     */
    private suspend fun refreshEfforts(providerId: String, modelId: String) {
        _state.value = _state.value.copy(
            efforts = providers.reasoningEfforts(providerId, modelId),
            selectedEffort = providers.reasoningEffort(providerId, modelId),
        )
    }

    companion object {
        /**
         * The provider shown before the user has chosen anything.
         *
         * OpenRouter is the one provider that reaches hundreds of models behind a
         * single key, so it is the least-wrong starting point — but it is a
         * starting point, not a fixed answer, and [load] replaces it with
         * whatever was chosen last.
         */
        const val DEFAULT_PROVIDER_ID = "openrouter"
    }
}
