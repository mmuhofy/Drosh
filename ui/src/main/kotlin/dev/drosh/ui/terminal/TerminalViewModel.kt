package dev.drosh.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drosh.domain.settings.AboutInfo
import dev.drosh.domain.settings.MotdMode
import dev.drosh.domain.settings.SettingsRepository
import dev.drosh.domain.terminal.SetTerminalFontSizeUseCase
import dev.drosh.domain.terminal.TerminalZoom
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
 * The value is fractional because a pinch follows the fingers: the terminal
 * itself owns the live size while the gesture runs (see
 * [TerminalView.zoomTo]), and this ViewModel is told the result twice — once
 * per frame for display ([onZoomFrame]), once when the fingers lift
 * ([onZoomCommitted]) to publish and persist.
 *
 * Publishing on every frame is what this deliberately does **not** do. The
 * screen reads [fontSizeSp] at its root and hands it to both panes, so an emit
 * per frame would recompose the whole terminal screen at 60Hz to change one
 * number that only a chip is showing. The live value reaches the chip through
 * the client's zoom callback instead, and only the committed value travels
 * through the flow.
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

    private val _fontSizeSp = MutableStateFlow(TerminalZoom.DEFAULT_SP)
    val fontSizeSp: StateFlow<Float> = _fontSizeSp.asStateFlow()

    init {
        viewModelScope.launch {
            persist.observe().collect { stored ->
                _fontSizeSp.value = stored.coerceIn(TerminalZoom.MIN_SP, TerminalZoom.MAX_SP)
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

    /** From Settings. Publishes immediately — there is no gesture to wait for. */
    fun setFontSize(value: Float) {
        val clamped = value.coerceIn(TerminalZoom.MIN_SP, TerminalZoom.MAX_SP)
        _fontSizeSp.value = clamped
        pendingPersistJob?.cancel()
        pendingPersistJob = viewModelScope.launch { persist.set(clamped) }
    }

    /**
     * A pinch (or a double-tap) finished: publish and persist, once.
     *
     * [TerminalView.zoomTo] has already drawn at this size, so nothing here is
     * needed for the terminal to look right — this is what makes the size
     * outlive the process, and what a settings change elsewhere in the app
     * reads.
     */
    fun onZoomCommitted(textSizeSp: Float) {
        setFontSize(textSizeSp)
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
