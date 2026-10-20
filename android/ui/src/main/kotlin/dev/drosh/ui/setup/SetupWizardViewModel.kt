package dev.drosh.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drosh.domain.settings.ApplyThemeUseCase
import dev.drosh.domain.settings.GuestIsolation
import dev.drosh.domain.settings.SettingsRepository
import dev.drosh.domain.settings.DroshTheme
import dev.drosh.domain.settings.ThemePalette
import dev.drosh.domain.terminal.BootstrapProgress
import dev.drosh.domain.terminal.ObserveBootstrapUseCase
import dev.drosh.domain.terminal.ObserveFirstLaunchUseCase
import dev.drosh.domain.terminal.PackageProfile
import dev.drosh.domain.terminal.SetupPreferences
import dev.drosh.domain.terminal.ShellChoice
import dev.drosh.domain.terminal.TriggerBootstrapUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The onboarding wizard's state: the pages, the choices they collect, and the
 * two things that have to happen once the rootfs is really there.
 *
 * **The theme is the part with a chain behind it.** The palette picked on the
 * theme page cannot be written while setup is running: `.drosh/themes/` and the
 * rootfs copy of `settings.toml` are both inside the Linux environment, which
 * does not exist yet. So the pick is held here as [_selectedThemeId] and written
 * by [applySelectedTheme] the moment the bootstrap reports ready — which is the
 * first moment the write lands in the file the terminal actually reads. Without
 * that second step the picker would be a picture of a choice that goes nowhere,
 * which is the state this feature was in.
 */
@HiltViewModel
class SetupWizardViewModel @Inject constructor(
    observeBootstrap: ObserveBootstrapUseCase,
    private val triggerBootstrap: TriggerBootstrapUseCase,
    private val firstLaunch: ObserveFirstLaunchUseCase,
    private val applyTheme: ApplyThemeUseCase,
    private val settings: SettingsRepository,
) : ViewModel() {

    val progress: StateFlow<BootstrapProgress> = observeBootstrap.progress()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = BootstrapProgress.initial(),
        )

    val triggerState: StateFlow<TriggerBootstrapUseCase.State> =
        triggerBootstrap.stateFlow.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = TriggerBootstrapUseCase.State.NotStarted,
        )

    private val _liveLogs = MutableStateFlow<List<String>>(emptyList())
    val liveLogs: StateFlow<List<String>> = _liveLogs.asStateFlow()

    private val _isLogDrawerOpen = MutableStateFlow(false)
    val isLogDrawerOpen: StateFlow<Boolean> = _isLogDrawerOpen.asStateFlow()

    /**
     * Which isolation engine the guest runs under.
     *
     * The user's choice, kept in the settings so it survives a restart and so
     * the session that opens next uses it. The default is proot, the engine that
     * works on a device today.
     */
    val guestIsolation: StateFlow<GuestIsolation> = settings.guestIsolation
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = GuestIsolation.DEFAULT,
        )

    private val _packageProfile = MutableStateFlow(PackageProfile.Standard)
    val packageProfile: StateFlow<PackageProfile> = _packageProfile.asStateFlow()

    private val _shellChoice = MutableStateFlow(ShellChoice.Zsh)
    val shellChoice: StateFlow<ShellChoice> = _shellChoice.asStateFlow()

    private val _customPackagesText = MutableStateFlow("")
    val customPackagesText: StateFlow<String> = _customPackagesText.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * The palettes the theme page shows: the built-in set, plus anything the
     * store already has installed. Available before the rootfs exists — the
     * built-ins are compiled in, and the installed half is read from the
     * app-private copy.
     */
    val themeChoices: StateFlow<List<DroshTheme>> = applyTheme.choices

    private val _selectedThemeId = MutableStateFlow(DroshTheme.DEFAULT.id)
    val selectedThemeId: StateFlow<String> = _selectedThemeId.asStateFlow()

    /**
     * The palette the onboarding chrome is drawn in.
     *
     * This is the live half of the same choice: the accent the page indicator,
     * the pill button and the selected card take is the accent of the theme
     * under the finger, so picking a palette is something the screen does
     * rather than something it describes. It resolves against the choice list,
     * so a theme that has not loaded yet leaves the default rather than blanking
     * the page.
     */
    val previewPalette = combine(_selectedThemeId, themeChoices) { id, choices ->
        DroshTheme.find(id, choices)?.palette ?: ThemePalette.DEFAULT
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ThemePalette.DEFAULT)

    /** True once the theme is written to disk, for the "done" line on the page. */
    private val _themeApplied = MutableStateFlow(false)
    val themeApplied: StateFlow<Boolean> = _themeApplied.asStateFlow()

    init {
        observeBootstrap.liveLogs()
            .onEach { line ->
                _liveLogs.value = (_liveLogs.value + line).takeLast(LOG_DRAWER_LIMIT)
            }
            .launchIn(viewModelScope)

        // The write, on the one event that makes it possible. Watching the step
        // rather than the screen means it happens for every route to a finished
        // bootstrap: the normal one, and the re-entry after a rootfs reset
        // where the pipeline short-circuits to ready before setup ever starts.
        observeBootstrap.progress()
            .map { it.isReady }
            .distinctUntilChanged()
            .onEach { ready -> if (ready) applySelectedTheme() }
            .launchIn(viewModelScope)
    }

    fun selectProfile(profile: PackageProfile) {
        _packageProfile.value = profile
    }

    fun selectShell(shell: ShellChoice) {
        _shellChoice.value = shell
    }

    /**
     * Chooses the isolation engine and writes it to the settings.
     *
     * The write is a settings write like any other, so the session that opens
     * next picks it up without a restart and without a second path to keep in
     * step.
     */
    fun selectGuestIsolation(isolation: GuestIsolation) {
        viewModelScope.launch { settings.setGuestIsolation(isolation) }
    }

    fun selectTheme(themeId: String) {
        _selectedThemeId.value = themeId
    }

    fun setCustomPackagesText(text: String) {
        _customPackagesText.value = text
    }

    fun toggleLogDrawer() {
        _isLogDrawerOpen.value = !_isLogDrawerOpen.value
    }

    fun setLogDrawerOpen(open: Boolean) {
        _isLogDrawerOpen.value = open
    }

    fun startBootstrap() {
        val customPackages = _customPackagesText.value
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        val preferences = SetupPreferences(
            userName = "user",
            shellChoice = _shellChoice.value,
            packageProfile = _packageProfile.value,
            customPackages = customPackages,
            themeId = _selectedThemeId.value,
        )

        viewModelScope.launch {
            try {
                triggerBootstrap.start(preferences)
                firstLaunch.markCompleted()
                _errorMessage.value = null
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Unknown error"
            }
        }
    }

    /**
     * Writes the chosen theme, if it is not written yet, then runs [onApplied].
     *
     * Safe to call from anywhere, any number of times: [ApplyThemeUseCase.apply]
     * is a settings write, [ApplyThemeUseCase.isApplied] is what stops the
     * repeats from turning into a write per progress emission, and the callback
     * is the ordering guarantee. The write goes to disk and comes back through
     * the store's flow, so a caller that opens the terminal the moment this
     * returns would otherwise be racing the palette it just asked for — and a
     * session that opens in the default and turns a beat later is a session that
     * looks like the feature did not work.
     *
     * [onApplied] runs on the main dispatcher whether or not the write happened,
     * and whether or not it failed: a failed write must not strand the user on
     * the setup page with a working rootfs behind it.
     */
    fun applySelectedTheme(onApplied: () -> Unit = {}) {
        val themeId = _selectedThemeId.value
        if (applyTheme.isApplied(themeId)) {
            _themeApplied.value = true
            onApplied()
            return
        }
        viewModelScope.launch {
            applyTheme.apply(themeId)
                .onSuccess { _themeApplied.value = true }
                .onFailure { _themeApplied.value = false }
            onApplied()
        }
    }

    fun retry() {
        viewModelScope.launch {
            triggerBootstrap.retry()
        }
    }

    fun reDownloadRootfs() {
        viewModelScope.launch {
            triggerBootstrap.reDownloadRootfs()
        }
    }

    fun resetEverything() {
        viewModelScope.launch {
            triggerBootstrap.resetEverything()
        }
    }

    private companion object {
        const val LOG_DRAWER_LIMIT = 200
    }
}