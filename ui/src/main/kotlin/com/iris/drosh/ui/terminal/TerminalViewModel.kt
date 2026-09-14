package com.iris.irisshell.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iris.irisshell.domain.settings.SettingsRepository
import com.iris.irisshell.domain.terminal.SetTerminalFontSizeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Properties
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the terminal font size for [TerminalScreen].
 *
 * Pinch gestures on the terminal area call [bumpFontSize] with a relative
 * fraction (factor > 1 grows the font, < 1 shrinks it). The new value is
 * clamped to [MIN_FONT_SP]..[MAX_FONT_SP] and written to
 * [SetTerminalFontSizeUseCase] (DataStore) for process-death survival, while
 * [fontSizeSp] (a hot [MutableStateFlow]) emits instantly so the terminal
 * view can [TerminalView.setTextSize] on every scale event — giving the
 * smooth, jank-free pinch zoom that the previous persist-then-emit path
 * could not deliver.
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    setTerminalFontSize: SetTerminalFontSizeUseCase,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val persist = setTerminalFontSize
    private val settingsRepository = settingsRepository

    val useBlockEngine: StateFlow<Boolean> = settingsRepository.useBlockEngine
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val prootStartCommand: StateFlow<String> = settingsRepository.prootStartCommand
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Experimental: live color-scheme props pushed into the terminal view. */
    val colorProps: StateFlow<Properties> = combine(
        settingsRepository.terminalBgColor,
        settingsRepository.terminalTextColor,
        settingsRepository.accentColor,
    ) { bg, fg, accent ->
        Properties().apply {
            setProperty("background", bg)
            setProperty("foreground", fg)
            setProperty("color6", accent)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Properties())

    private val _fontSizeSp = MutableStateFlow(DEFAULT_FONT_SP)
    val fontSizeSp: StateFlow<Int> = _fontSizeSp.asStateFlow()

    init {
        viewModelScope.launch {
            persist.observe().collect { stored ->
                _fontSizeSp.value = stored.toInt().coerceIn(MIN_FONT_SP, MAX_FONT_SP)
            }
        }
    }

    private var pendingPersistJob: Job? = null

    fun setFontBgColor(hex: String) {
        viewModelScope.launch { settingsRepository.setTerminalBgColor(hex) }
    }

    fun setFontTextColor(hex: String) {
        viewModelScope.launch { settingsRepository.setTerminalTextColor(hex) }
    }

    fun setFontAccentColor(hex: String) {
        viewModelScope.launch { settingsRepository.setAccentColor(hex) }
    }

    fun setFontSize(value: Int) {
        val clamped = value.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        _fontSizeSp.value = clamped
        pendingPersistJob?.cancel()
        pendingPersistJob = viewModelScope.launch { persist.set(clamped.toFloat()) }
    }

    fun bumpFontSize(factor: Float) {
        val current = _fontSizeSp.value.toFloat()
        val target = (current * factor).coerceIn(MIN_FONT_SP.toFloat(), MAX_FONT_SP.toFloat())
        setFontSize(target.toInt())
    }

    fun setProotStartCommand(command: String) {
        viewModelScope.launch {
            settingsRepository.setProotStartCommand(command)
        }
    }

    override fun onCleared() {
        pendingPersistJob?.cancel()
        super.onCleared()
    }

    companion object {
        const val MIN_FONT_SP: Int = 10
        const val MAX_FONT_SP: Int = 32
        const val DEFAULT_FONT_SP: Int = 14
    }
}
