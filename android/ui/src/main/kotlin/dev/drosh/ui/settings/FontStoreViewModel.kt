package dev.drosh.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.drosh.domain.font.FontRepository
import dev.drosh.domain.font.InstalledFont
import dev.drosh.domain.font.StoreFont
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
 * The font store's state: what is on the device, what the shelf offers, and
 * which face the terminal is drawing with.
 *
 * ## Why applying is two writes
 *
 * The setting is the user's answer and the marker is the renderer's. [apply]
 * writes both, in that order, because the renderer reads the marker on a draw
 * thread and the screen reads the setting on the main one: a font that is
 * applied must not be applied to one of them and not the other.
 *
 * ## Why the store is a list rather than a fetch
 *
 * The shelf is part of the app (see `TerminalFontCatalog`); only the files are
 * remote. So the screen opens instantly and works offline, and a download is
 * the only thing that can fail.
 */
@HiltViewModel
class FontStoreViewModel @Inject constructor(
    private val fonts: FontRepository,
    private val store: SettingsStore,
) : ViewModel() {

    val installed: StateFlow<List<InstalledFont>> = fonts.installed

    val shelf: List<StoreFont> = fonts.store()

    /** The active font's id; `""` is the bundled face. */
    val active: StateFlow<String> = store.settings
        .map { it.appearance.terminalFontId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** The id whose download or removal is running, for the row's spinner. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun download(font: StoreFont) {
        viewModelScope.launch {
            _busy.value = font.id
            fonts.download(font)
                .onSuccess { apply(it.id) }
                .onFailure { error -> _message.value = error.message ?: "Download failed" }
            _busy.value = null
        }
    }

    /** Makes [id] the terminal's face; `""` returns to the bundled font. */
    fun apply(id: String) {
        viewModelScope.launch {
            // The marker first: if the settings write is the one that fails,
            // the renderer still agrees with what the screen is about to show.
            fonts.activate(id)
            store.update { current ->
                current.copy(appearance = current.appearance.copy(terminalFontId = id))
            }
        }
    }

    fun remove(id: String) {
        viewModelScope.launch {
            _busy.value = id
            fonts.delete(id)
                .onSuccess { _message.value = "Font removed" }
                .onFailure { error -> _message.value = error.message ?: "Remove failed" }
            _busy.value = null
        }
    }

    fun dismissMessage() {
        _message.value = null
    }
}
