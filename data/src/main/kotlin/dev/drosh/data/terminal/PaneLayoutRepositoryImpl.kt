package dev.drosh.data.terminal

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.drosh.data.local.irisShellDataStore
import dev.drosh.domain.terminal.NormalizedRect
import dev.drosh.domain.terminal.PaneLayout
import dev.drosh.domain.terminal.PaneLayoutRepository
import dev.drosh.domain.terminal.PanePresentation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed [PaneLayoutRepository].
 *
 * Reads never throw: a corrupt or unreadable preferences file yields
 * [PaneLayout.EMPTY], so a bad value costs the user their split arrangement
 * and nothing else. Losing an arrangement is recoverable; a terminal screen
 * that cannot compose is not.
 *
 * UNTESTED — verify before use in production.
 */
@Singleton
class PaneLayoutRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : PaneLayoutRepository {

    private val dataStore: DataStore<Preferences> = context.irisShellDataStore

    override val layout: Flow<PaneLayout> = dataStore.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences())
            else throw cause
        }
        .map { prefs -> prefs.toLayout() }

    override suspend fun setLayout(layout: PaneLayout) {
        dataStore.edit { prefs ->
            val secondary = layout.secondarySessionId
            if (secondary == null) {
                prefs.remove(KEY_SECONDARY_SESSION_ID)
            } else {
                prefs[KEY_SECONDARY_SESSION_ID] = secondary
            }
            prefs[KEY_SPLIT_FRACTION] = layout.splitFraction
            prefs[KEY_PRESENTATION] = layout.presentation.name
            prefs[KEY_FLOAT_LEFT] = layout.floatingBounds.left
            prefs[KEY_FLOAT_TOP] = layout.floatingBounds.top
            prefs[KEY_FLOAT_WIDTH] = layout.floatingBounds.width
            prefs[KEY_FLOAT_HEIGHT] = layout.floatingBounds.height
            prefs[KEY_MAXIMIZED] = layout.maximized
        }
    }

    override suspend fun setSplitFraction(fraction: Float) {
        dataStore.edit { prefs ->
            prefs[KEY_SPLIT_FRACTION] = fraction
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_SECONDARY_SESSION_ID)
            prefs.remove(KEY_SPLIT_FRACTION)
            prefs.remove(KEY_PRESENTATION)
            prefs.remove(KEY_FLOAT_LEFT)
            prefs.remove(KEY_FLOAT_TOP)
            prefs.remove(KEY_FLOAT_WIDTH)
            prefs.remove(KEY_FLOAT_HEIGHT)
            prefs.remove(KEY_MAXIMIZED)
        }
    }

    /**
     * Rebuilds a layout from stored keys.
     *
     * Built through the model's own mutators rather than by assigning fields,
     * so a stored value that is out of range — from an older build with
     * different limits, say — is clamped on the way in instead of rendering as
     * a zero-width pane.
     */
    private fun Preferences.toLayout(): PaneLayout {
        val secondary = this[KEY_SECONDARY_SESSION_ID]
        if (secondary.isNullOrBlank()) return PaneLayout.EMPTY

        val presentation = this[KEY_PRESENTATION]
            ?.let { name ->
                PanePresentation.entries.firstOrNull { it.name == name }
            }
            ?: PanePresentation.DOCKED

        var layout = PaneLayout(secondarySessionId = secondary, presentation = presentation)
        layout = layout.withSplitFraction(this[KEY_SPLIT_FRACTION] ?: PaneLayout.DEFAULT_SPLIT_FRACTION)
        if (presentation == PanePresentation.FLOATING) {
            val bounds = NormalizedRect(
                left = this[KEY_FLOAT_LEFT] ?: NormalizedRect.DEFAULT.left,
                top = this[KEY_FLOAT_TOP] ?: NormalizedRect.DEFAULT.top,
                width = this[KEY_FLOAT_WIDTH] ?: NormalizedRect.DEFAULT.width,
                height = this[KEY_FLOAT_HEIGHT] ?: NormalizedRect.DEFAULT.height,
            )
            layout = layout.withFloatingBounds(bounds)
            if (this[KEY_MAXIMIZED] == true) layout = layout.toggleMaximized()
        }
        return layout
    }

    private companion object {
        val KEY_SECONDARY_SESSION_ID = stringPreferencesKey("pane_secondary_session_id")
        val KEY_SPLIT_FRACTION = floatPreferencesKey("pane_split_fraction")
        val KEY_PRESENTATION = stringPreferencesKey("pane_presentation")
        val KEY_FLOAT_LEFT = floatPreferencesKey("pane_float_left")
        val KEY_FLOAT_TOP = floatPreferencesKey("pane_float_top")
        val KEY_FLOAT_WIDTH = floatPreferencesKey("pane_float_width")
        val KEY_FLOAT_HEIGHT = floatPreferencesKey("pane_float_height")
        val KEY_MAXIMIZED = booleanPreferencesKey("pane_maximized")
    }
}