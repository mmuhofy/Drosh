package dev.drosh.ui.input

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drosh.domain.input.HardwareKeyboardPresence
import dev.drosh.domain.input.InputIntent
import dev.drosh.domain.input.InputPreferencesRepository
import dev.drosh.domain.input.SendKeyIntentUseCase
import dev.drosh.domain.input.StickyModifierState
import dev.drosh.domain.input.SubmitRawByteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for [InputBarHost].
 *
 * `barVisible` is the user's toggle persisted in DataStore — false on
 * first launch. `hardwareKeyboardPresent` suppresses the bar even when
 * the user has set `barVisible = true` (Termux convention — see
 * `docs/MEMORYBANK.md` §8).
 *
 * `ctrlStuck` / `altStuck` mirror the current state of
 * [StickyModifierState] so [ExtraKeyButton]s can paint the sticky
 * highlight. These values are pushed (not pulled) by
 * [InputBarViewModel] whenever a modifier is armed or consumed.
 */
data class InputBarUiState(
    val barVisible: Boolean = false,
    val hardwareKeyboardPresent: Boolean = false,
    val ctrlStuck: Boolean = false,
    val altStuck: Boolean = false,
) {
    /** The on-screen bar should actually render — both toggles must agree. */
    val renderBar: Boolean get() = barVisible && !hardwareKeyboardPresent
}

/**
 * Single source of truth for the on-screen extra-key bar.
 *
 * Depends only on `:domain` interfaces — concrete state lives behind
 * `StickyModifierState` (port in `:terminal`), byte flushing is owned
 * by `SubmitRawByteUseCase` (`:data`), intent dispatch goes through
 * `SendKeyIntentUseCase` (`:terminal`). Layering preserved per
 * AGENT.md §139.
 *
 * UNTESTED — verify on device.
 */
@HiltViewModel
class InputBarViewModel @Inject constructor(
    private val prefs: InputPreferencesRepository,
    private val hardwareKeyboard: HardwareKeyboardPresence,
    private val submitRawByte: SubmitRawByteUseCase,
    private val sendKeyIntent: SendKeyIntentUseCase,
    private val modifierState: StickyModifierState,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InputBarUiState())
    val uiState: StateFlow<InputBarUiState> = _uiState.asStateFlow()

    init {
        observePreferences()
        observeHardwareKeyboard()
        // When hardware keyboard consumes a sticky modifier (readCtrl/readAlt),
        // push refreshed state so the key-bar highlight clears.
        modifierState.setOnModifierConsumed {
            pushModifierState(
                ctrl = modifierState.peekCtrl(),
                alt = modifierState.peekAlt(),
            )
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            prefs.extraKeysBarVisible.collect { visible ->
                _uiState.value = _uiState.value.copy(barVisible = visible)
            }
        }
    }

    private fun observeHardwareKeyboard() {
        viewModelScope.launch {
            hardwareKeyboard.isPresent.collect { present ->
                _uiState.value = _uiState.value.copy(hardwareKeyboardPresent = present)
            }
        }
    }

    fun toggleBarVisible() {
        val next = !_uiState.value.barVisible
        viewModelScope.launch { prefs.setExtraKeysBarVisible(next) }
    }

    /**
     * Consume a UI-fired intent. Translates block-mode "interrupt"
     * intents to [submitRawByte] (so Ctrl+C etc. flush raw bytes to PTY
     * rather than going through the BasicTextField — see
     * `docs/MEMORYBANK.md` §8 "Block mode modifier semantics"), and
     * updates the sticky-modifier state for the UI highlight.
     */
    fun onIntent(intent: InputIntent) {
        if (intent is InputIntent.TypeChar) {
            val ctrl = modifierState.consumeCtrl()
            val alt = modifierState.consumeAlt()
            if (ctrl) {
                // Block mode override: flush the control byte straight to PTY
                // instead of typing the literal char; matches the agreed
                // semantics — see MEMORYBANK.md §8.
                viewModelScope.launch {
                    submitRawByte.submit(byteArrayOf(translateCtrlChar(intent.char).toByte()))
                }
                pushModifierState(ctrl = false, alt = alt)
                return
            }
        }
        sendKeyIntent.dispatch(intent)
        pushModifierState(ctrl = modifierState.peekCtrl(), alt = modifierState.peekAlt())
    }

    private fun pushModifierState(ctrl: Boolean, alt: Boolean) {
        _uiState.value = _uiState.value.copy(ctrlStuck = ctrl, altStuck = alt)
    }

    private fun translateCtrlChar(char: Char): Int = when (char.code) {
        in 'a'.code..'z'.code -> char.code - 'a'.code + 1
        in 'A'.code..'Z'.code -> char.code - 'A'.code + 1
        ' '.code, '2'.code -> 0
        '['.code, '3'.code -> 27
        '\\'.code, '4'.code -> 28
        ']'.code, '5'.code -> 29
        '^'.code, '6'.code -> 30
        '_'.code, '7'.code, '/'.code -> 31
        '8'.code -> 127
        else -> char.code
    }
}
