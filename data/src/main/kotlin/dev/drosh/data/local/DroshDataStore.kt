package dev.drosh.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

internal val Context.irisShellDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "drosh_shell_prefs",
)
