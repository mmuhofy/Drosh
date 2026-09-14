package com.iris.irisshell.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.iris.irisshell.data.local.irisShellDataStore
import com.iris.irisshell.domain.terminal.ObserveFirstLaunchUseCase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore-backed implementation of [ObserveFirstLaunchUseCase].
 *
 * Persists the boolean under [KEY_FIRST_LAUNCH_COMPLETED]. The UI uses this flag
 * to decide whether to render OnboardingScreen or jump straight to the
 * bootstrap stepper / terminal.
 */
@Singleton
class FirstLaunchRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ObserveFirstLaunchUseCase {

    private val dataStore: DataStore<Preferences> = context.irisShellDataStore

    override fun isCompleted(): Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[KEY_FIRST_LAUNCH_COMPLETED] ?: false }

    override suspend fun markCompleted() {
        dataStore.edit { prefs -> prefs[KEY_FIRST_LAUNCH_COMPLETED] = true }
    }

    private companion object {
        val KEY_FIRST_LAUNCH_COMPLETED = booleanPreferencesKey("first_launch_completed")
    }
}
