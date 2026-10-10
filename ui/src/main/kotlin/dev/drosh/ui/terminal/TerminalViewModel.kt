package dev.drosh.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drosh.domain.settings.AboutInfo
import dev.drosh.domain.settings.BackgroundImageStore
import dev.drosh.domain.settings.BackgroundSettings
import dev.drosh.domain.settings.DroshSettings
import dev.drosh.domain.settings.MotdMode
import dev.drosh.domain.settings.ThemePalette
import dev.drosh.domain.settings.SettingsStore
import dev.drosh.domain.settings.TerminalSettings
import dev.drosh.domain.settings.SettingsRepository
import dev.drosh.domain.terminal.TerminalZoom
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
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
    private val store: SettingsStore,
    private val backgroundImages: BackgroundImageStore,
) : ViewModel() {

    private val settingsRepository = settingsRepository

    /**
     * The background image to draw, or null for none.
     *
     * Follows the convention file and the blur radius together: either
     * changing regenerates what the terminal shows, and neither of them is a
     * path the screen has to know about.
     */
    val backgroundFile: StateFlow<File?> = combine(
        backgroundImages.imageFile,
        store.settings.map { it.background.imageEnabled },
    ) { file, enabled -> file.takeIf { enabled } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
    val colorProps: StateFlow<Properties> = store.settings
        .map { it.terminal.palette }
        .map { palette -> paletteToProps(palette) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Properties())

    /**
     * The size a session opens at, as stored. The Settings slider writes this.
     */
    val defaultFontSizeSp: StateFlow<Float> = settingsRepository.fontSizeSp
        .stateIn(viewModelScope, SharingStarted.Eagerly, TerminalZoom.DEFAULT_SP)

    private val _fontSizeSp = MutableStateFlow(TerminalZoom.DEFAULT_SP)
    val fontSizeSp: StateFlow<Float> = _fontSizeSp.asStateFlow()

    /**
     * Appearance as the terminal screen reads it.
     *
     * A subset of the file rather than the whole snapshot: the screen needs
     * the terminal section, the background section, and nothing else, and
     * handing it everything would recompute the whole tree on every palette
     * tweak. The store's snapshot is what these come from — one file, one
     * collector.
     */
    val appearance: StateFlow<TerminalAppearance> = store.settings
        .map { settings ->
            TerminalAppearance(
                terminal = settings.terminal,
                background = settings.background,
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, TerminalAppearance.DEFAULT)

    init {
        viewModelScope.launch {
            // Follows the stored default, so a change made in Settings reaches
            // the session already on screen rather than waiting for the next
            // one. A pinch never emits here: it moves the live value alone.
            settingsRepository.fontSizeSp.collect { stored ->
                _fontSizeSp.value = stored.coerceIn(TerminalZoom.MIN_SP, TerminalZoom.MAX_SP)
            }
        }
        viewModelScope.launch {
            // The radius is a settings value and the file is the store's, so
            // the two meet here: each new radius re-resolves, and an import —
            // which is not a settings change at all — arrives through the
            // store's own flow.
            store.settings
                .map { it.background.blurRadius }
                .distinctUntilChanged()
                .collect { radius -> backgroundImages.applyBlur(radius) }
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

/**
 * The palette as the terminal's colour channel speaks it.
 *
 * Keys are the ones [com.termux.terminal.TerminalColorScheme.updateWith]
 * reads: the three outside the ANSI range, the selection pair, and the
 * sixteen indexed colours. A theme is twenty-two colours and this is
 * where all twenty-two land — the accent no longer borrows the cyan slot,
 * because a palette that has its own cyan has no room for a borrowed one.
 */
private fun paletteToProps(palette: ThemePalette): Properties = Properties().apply {
    setProperty("background", palette.background)
    setProperty("foreground", palette.foreground)
    setProperty("cursor", palette.cursor)
    setProperty("selectionBackground", palette.selectionBackground)
    setProperty("selectionForeground", palette.selectionForeground)
    setProperty("color0", palette.black)
    setProperty("color1", palette.red)
    setProperty("color2", palette.green)
    setProperty("color3", palette.yellow)
    setProperty("color4", palette.blue)
    setProperty("color5", palette.magenta)
    setProperty("color6", palette.cyan)
    setProperty("color7", palette.white)
    setProperty("color8", palette.brightBlack)
    setProperty("color9", palette.brightRed)
    setProperty("color10", palette.brightGreen)
    setProperty("color11", palette.brightYellow)
    setProperty("color12", palette.brightBlue)
    setProperty("color13", palette.brightMagenta)
    setProperty("color14", palette.brightCyan)
    setProperty("color15", palette.brightWhite)
}


/**
 * What the terminal screen needs from the appearance side of the file.
 *
 * A narrow view of the two sections the screen actually draws, so a change
 * to any other section does not recompute the terminal.
 */
data class TerminalAppearance(
    val terminal: TerminalSettings = TerminalSettings.DEFAULT,
    val background: BackgroundSettings = BackgroundSettings.DEFAULT,
) {
    companion object {
        val DEFAULT = TerminalAppearance()
    }
}

