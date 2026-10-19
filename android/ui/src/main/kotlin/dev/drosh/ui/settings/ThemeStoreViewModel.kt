package dev.drosh.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.drosh.domain.settings.DroshTheme
import dev.drosh.domain.settings.SettingsStore
import dev.drosh.domain.settings.ThemeIndexEntry
import dev.drosh.domain.settings.ThemeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The theme store's state: what is installed, what the store lists, which
 * theme is active, and the moves between them.
 *
 * Applying a theme is a settings write like any other — the palette lands
 * in the file, the file is what every surface reads — so a theme is not a
 * parallel state that has to be kept in step. That is also what makes an
 * edit made in the terminal (or by the agent) take effect here.
 */
@HiltViewModel
class ThemeStoreViewModel @Inject constructor(
    private val themes: ThemeRepository,
    private val store: SettingsStore,
) : ViewModel() {

    val installed: StateFlow<List<DroshTheme>> = themes.installed

    private val _storeIndex = MutableStateFlow<List<ThemeIndexEntry>>(emptyList())
    val storeIndex: StateFlow<List<ThemeIndexEntry>> = _storeIndex.asStateFlow()

    val activeThemeId: StateFlow<String> = store.settings
        .map { it.terminal.themeId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** The theme currently being applied, for the screen's overlay. */
    private val _applying = MutableStateFlow<String?>(null)
    val applying: StateFlow<String?> = _applying.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _urlDialogOpen = MutableStateFlow(false)
    val urlDialogOpen: StateFlow<Boolean> = _urlDialogOpen.asStateFlow()

    /** The theme the creator starts from, when it is open. */
    private val _createFrom = MutableStateFlow<String?>(null)
    val createFrom: StateFlow<String?> = _createFrom.asStateFlow()

    init {
        viewModelScope.launch {
            themes.index().onSuccess { index -> _storeIndex.value = index.themes }
        }
    }

    fun refreshIndex() {
        viewModelScope.launch {
            _busy.value = true
            themes.index(force = true)
                .onSuccess { index -> _storeIndex.value = index.themes }
                .onFailure { error -> _message.value = error.message ?: "Index could not be fetched" }
            _busy.value = false
        }
    }

    fun apply(themeId: String) {
        val theme = themes.theme(themeId) ?: return
        viewModelScope.launch {
            // The overlay runs while the write lands: the palette is applied
            // by the time it finishes, so what it animates is the announcement
            // of a change that has already taken effect.
            _applying.value = themeId
            store.update { current ->
                current.copy(
                    terminal = current.terminal.copy(
                        palette = theme.palette,
                        themeId = theme.id,
                    ),
                )
            }
            kotlinx.coroutines.delay(600)
            _applying.value = null
        }
    }

    fun install(entry: ThemeIndexEntry) {
        viewModelScope.launch {
            _busy.value = true
            themes.install(entry)
                .onSuccess { theme ->
                    _message.value = "${theme.name} installed"
                    apply(theme.id)
                }
                .onFailure { error -> _message.value = error.message ?: "Install failed" }
            _busy.value = false
        }
    }

    fun installFromUrl(url: String) {
        if (url.isBlank()) return
        viewModelScope.launch {
            _busy.value = true
            themes.installFromUrl(url)
                .onSuccess { theme ->
                    _message.value = "${theme.name} installed"
                    apply(theme.id)
                }
                .onFailure { error -> _message.value = error.message ?: "That did not look like a theme" }
            _busy.value = false
        }
    }

    fun startUrlInstall() {
        _urlDialogOpen.value = true
    }

    fun dismissUrlInstall() {
        _urlDialogOpen.value = false
    }

    fun startCreate() {
        _createFrom.value = activeThemeId.value.ifBlank { "drosh" }
    }

    fun createFrom(id: String) {
        _createFrom.value = id
    }

    fun cancelCreate() {
        _createFrom.value = null
    }

    fun saveCustom(name: String, author: String, description: String) {
        val base = themes.theme(_createFrom.value ?: "") ?: DroshTheme.DEFAULT
        viewModelScope.launch {
            val theme = base.copy(
                id = slug(name),
                name = name.ifBlank { "My theme" },
                author = author,
                description = description,
            )
            themes.saveCustom(theme)
                .onSuccess {
                    _createFrom.value = null
                    _message.value = "${theme.name} saved"
                    apply(theme.id)
                }
                .onFailure { error -> _message.value = error.message ?: "Save failed" }
        }
    }

    /**
     * Removes an installed theme.
     *
     * The active one is refused by the caller: taking the theme the terminal
     * is drawing with out from under it would leave a palette pointing at a
     * file that is no longer there.
     */
    fun remove(id: String) {
        viewModelScope.launch {
            themes.delete(id)
                .onSuccess { _message.value = "Theme removed" }
                .onFailure { error -> _message.value = error.message ?: "Remove failed" }
        }
    }

    fun dismissMessage() {
        _message.value = null
    }

    /** "My Dark Theme" → "my-dark-theme", the id a theme file is filed under. */
    private fun slug(name: String): String =
        name.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "my-theme" }
}
