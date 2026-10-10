package dev.drosh.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drosh.domain.settings.AboutInfo
import dev.drosh.domain.settings.MotdMode
import dev.drosh.domain.settings.SettingsRepository
import dev.drosh.domain.terminal.TerminalZoom
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the terminal font size for [TerminalScreen].
 *
 * Two values, deliberately: the **default** is what a session opens at and is
 * what the Settings slider writes; the **live** size is what the session on
 * screen is drawing right now. A pinch moves the live value only — it is the
 * session's, not the app's, and rewriting the default underneath it would
 * mean the next session opens at a size the user chose by accident while
 * reading.
 *
 * The value is fractional because a pinch follows the fingers: the terminal
 * itself owns the live size while the gesture runs (see
 * [TerminalView.zoomTo]), and this ViewModel is told the result once, when the
 * fingers lift ([onZoomCommitted]).
 *
 * Publishing on every frame is what this deliberately does **not** do. The
 * screen reads [fontSizeSp] at its root and hands it to both panes, so an emit
 * per frame would recompose the whole terminal screen at 60Hz to change one
 * number that only a chip is showing. The live value reaches the chip through
 * the client's zoom callback instead, and only the settled value travels
 * through the flow.
 */
@HiltViewModel
class TerminalViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {

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

    /**
     * The size a session opens at, as stored. The Settings slider writes this.
     */
    val defaultFontSizeSp: StateFlow<Float> = settingsRepository.fontSizeSp
        .stateIn(viewModelScope, SharingStarted.Eagerly, TerminalZoom.DEFAULT_SP)

    private val _fontSizeSp = MutableStateFlow(TerminalZoom.DEFAULT_SP)
    val fontSizeSp: StateFlow<Float> = _fontSizeSp.asStateFlow()

    init {
        viewModelScope.launch {
            // Follows the stored default, so a change made in Settings reaches
            // the session already on screen rather than waiting for the next
            // one. A pinch never emits here: it moves the live value alone.
            settingsRepository.fontSizeSp.collect { stored ->
                _fontSizeSp.value = stored.coerceIn(TerminalZoom.MIN_SP, TerminalZoom.MAX_SP)
            }
        }
    }

    fun setFontBgColor(hex: String) {
        viewModelScope.launch { settingsRepository.setTerminalBgColor(hex) }
    }

    fun setFontTextColor(hex: String) {
        viewModelScope.launch { settingsRepository.setTerminalTextColor(hex) }
    }

    fun setFontAccentColor(hex: String) {
        viewModelScope.launch { settingsRepository.setAccentColor(hex) }
    }

    /**
     * From Settings: writes the default. Published immediately — there is no
     * gesture to wait for — and the collector above re-affirms it once the
     * write lands.
     */
    fun setFontSize(value: Float) {
        val clamped = value.coerceIn(TerminalZoom.MIN_SP, TerminalZoom.MAX_SP)
        _fontSizeSp.value = clamped
        viewModelScope.launch { settingsRepository.setFontSize(clamped) }
    }

    /**
     * A pinch (or a double-tap) finished: the live size moved, the default
     * did not.
     *
     * [TerminalView.zoomTo] has already drawn at this size, so nothing here is
     * needed for the terminal to look right. What used to happen — persisting
     * it — is what made the stored default quietly become a record of the last
     * pinch, so the next session opened at a size nobody had chosen twice.
     */
    fun onZoomCommitted(textSizeSp: Float) {
        _fontSizeSp.value = textSizeSp.coerceIn(TerminalZoom.MIN_SP, TerminalZoom.MAX_SP)
    }

    /**
     * A session opened in a pane, so its size goes back to the default.
     *
     * Called where a view reports that it took a *new* session — a pinch is
     * per session, and the next one has to start from the default rather than
     * from whatever the last one was left at.
     */
    fun onSessionOpened() {
        _fontSizeSp.value = defaultFontSizeSp.value
    }

    fun setProotStartCommand(command: String) {
        viewModelScope.launch {
            settingsRepository.setProotStartCommand(command)
        }
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
