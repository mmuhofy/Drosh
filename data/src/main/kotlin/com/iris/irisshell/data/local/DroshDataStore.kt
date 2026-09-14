package com.iris.irisshell.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

internal val Context.irisShellDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "iris_shell_prefs",
)
