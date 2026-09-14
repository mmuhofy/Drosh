package com.iris.irisshell.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import com.iris.irisshell.data.local.irisShellDataStore
import com.iris.irisshell.domain.terminal.SetTerminalFontSizeUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed implementation of [SetTerminalFontSizeUseCase].
 *
 * Persists the user's terminal font size (sp) under [KEY_TERMINAL_FONT_SP].
 * Initial value is 14sp — picked up from the VM default constant so the first
 * launch lands on a comfortable font out of the box.
 *
 * Clamping is the responsibility of the use case (see VM); this layer
 * stores whatever it is told so we never silently differ from what the user
 * picked on the slider.
 */
@Singleton
class TerminalFontSizeRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : SetTerminalFontSizeUseCase {

    private val dataStore: DataStore<Preferences> = context.irisShellDataStore

    override fun observe(): Flow<Float> =
        dataStore.data.map { prefs -> prefs[KEY_TERMINAL_FONT_SP] ?: DEFAULT_FONT_SP }

    override suspend fun set(sp: Float) {
        dataStore.edit { prefs -> prefs[KEY_TERMINAL_FONT_SP] = sp }
    }

    private companion object {
        val KEY_TERMINAL_FONT_SP = floatPreferencesKey("terminal_font_sp")
        const val DEFAULT_FONT_SP: Float = 14f
    }
}
