package dev.drosh.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drosh.domain.settings.AboutInfo
import dev.drosh.domain.settings.MotdMode
import dev.drosh.domain.settings.SettingsRepository
import dev.drosh.domain.terminal.SetTerminalFontSizeUseCase
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

    // ── MOTD ────────────────────────────────────────────────────────────────────

    val motdMode: StateFlow<MotdMode> = settingsRepository.motdMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, MotdMode.PlainText)

    val motdText: StateFlow<String> = settingsRepository.motdText
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val appInfo: StateFlow<AboutInfo?> = settingsRepository.appInfo
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    /**
     * Terminal colours, including a pair reserved for the text selection.
     *
     * Selection used to be drawn by swapping fore and back, so it had no colour
     * of its own and inherited whatever the scheme happened to resolve to.
     * TerminalColorScheme reads these as separate keys.
     *
     * The terminal module cannot see the design system, so these travel the same
     * channel background and foreground already use rather than a new one.
     */
    val colorProps: StateFlow<Properties> = combine(
        settingsRepository.terminalBgColor,
        settingsRepository.terminalTextColor,
        settingsRepository.accentColor,
    ) { bg, fg, accent ->
        Properties().apply {
            setProperty("background", bg)
            setProperty("foreground", fg)
            setProperty("color6", accent)
            // TerminalColors.parse drops the alpha channel, so a translucent
            // colour cannot travel this channel — an 8-digit hex reads as an
            // invalid colour and update() throws. The tint is therefore mixed
            // here, which also means it follows whatever background the user
            // picked instead of assuming the default.
            setProperty("selectionBackground", mixOver(accent, bg, 0.38f))
            setProperty("selectionForeground", SELECTION_FOREGROUND)
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

/** Near-black, so selected text keeps its contrast on the tinted accent. */
private const val SELECTION_FOREGROUND = "#101014"

/**
 * Mixes [fg2] over [bg2] by [amount], returning an opaque #RRGGBB.
 *
 * The terminal's colour channel is opaque-only, so a translucent selection
 * tint has to arrive pre-mixed. Returns the background unchanged if either
 * colour is unparseable rather than throwing mid-composition.
 */
private fun mixOver(fg2: String, bg2: String, amount: Float): String {
    val a = parseHex(fg2) ?: return bg2
    val b = parseHex(bg2) ?: return fg2
    fun mix(shift: Int): Int {
        val hi = (a shr shift) and 0xFF
        val lo = (b shr shift) and 0xFF
        return (hi * amount + lo * (1f - amount)).toInt().coerceIn(0, 255)
    }
    return "#%02X%02X%02X".format(mix(16), mix(8), mix(0))
}

private fun parseHex(value: String): Int? {
    val hex = value.removePrefix("#")
    if (hex.length != 6) return null
    return hex.toIntOrNull(16)
}
