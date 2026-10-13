package dev.drosh.data.theme

import dev.drosh.data.di.ApplicationScope
import dev.drosh.domain.settings.ApplyThemeUseCase
import dev.drosh.domain.settings.DroshTheme
import dev.drosh.domain.settings.SettingsStore
import dev.drosh.domain.settings.ThemeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ApplyThemeUseCase] over the theme store and the settings file.
 *
 * Nothing here knows about the rootfs: the store already keeps an app-private
 * copy of every theme directory, and the settings store keeps an app-private
 * copy of the settings file. What the rootfs being absent *does* change is who
 * should call this — onboarding waits until the bootstrap reports ready,
 * because that is the moment the settings file can be written to the copy the
 * terminal will actually read it from.
 */
@Singleton
class ApplyTheme @Inject constructor(
    private val themes: ThemeRepository,
    private val store: SettingsStore,
    @ApplicationScope private val scope: CoroutineScope,
) : ApplyThemeUseCase {

    // Seeded from what is already installed, not from nothing. The collector
    // below keeps this current, but a collector has not run yet at the moment
    // this object is constructed, and a picker that reads `choices` on its first
    // frame must not be shown a list that is missing themes already on disk.
    private val _choices = MutableStateFlow(DroshTheme.choices(themes.installed.value))
    override val choices: StateFlow<List<DroshTheme>> = _choices.asStateFlow()

    init {
        // What is on disk joins the built-ins in the same list, and leaves it
        // again when the store removes something.
        themes.installed
            .onEach { installed -> _choices.value = DroshTheme.choices(installed) }
            .launchIn(scope)
    }

    override suspend fun apply(themeId: String): Result<DroshTheme> {
        val theme = DroshTheme.find(themeId, choices.value)
            ?: return Result.failure(IOException("no theme named $themeId"))

        // The file first. `saveCustom` is the store's own write, so the rootfs
        // copy and the app-private copy are both maintained the way an install
        // maintains them — this is an install of a theme that shipped in the
        // APK, not a different kind of write.
        //
        // Only when nothing on disk already answers to that id: an installed
        // theme of the same id is the user's, possibly edited, and overwriting
        // it with the compiled-in copy would quietly undo the edit.
        if (themes.theme(theme.id) == null) {
            themes.saveCustom(theme).onFailure { error ->
                Timber.w(error, "themes: could not install starter %s", theme.id)
                return Result.failure(error)
            }
        }

        // The file, and the value: one settings write, which every surface that
        // reads the file follows. Nothing here tells a terminal to redraw.
        store.update { current ->
            current.copy(
                terminal = current.terminal.copy(
                    palette = theme.palette,
                    themeId = theme.id,
                ),
            )
        }

        return Result.success(theme)
    }

    override fun isApplied(themeId: String): Boolean {
        val theme = DroshTheme.find(themeId, choices.value) ?: return false
        val terminal = store.settings.value.terminal
        return terminal.themeId == theme.id && terminal.palette == theme.palette
    }
}