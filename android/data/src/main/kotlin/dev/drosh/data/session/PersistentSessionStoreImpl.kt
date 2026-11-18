package dev.drosh.data.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.drosh.data.di.ApplicationScope
import dev.drosh.data.local.irisShellDataStore
import dev.drosh.domain.session.PersistentSessionStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The pinned-session set, stored in the existing shell preferences DataStore.
 *
 * A set of ids rather than a column on `SessionEntity`: pinning is a user choice
 * about a session that may not have a row yet, and adding a column would need a
 * Room migration for a single boolean. Empty by default — nothing is persistent
 * until the user says so.
 */
@Singleton
class PersistentSessionStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope appScope: CoroutineScope,
) : PersistentSessionStore {

    private val dataStore: DataStore<Preferences> = context.irisShellDataStore

    private val _persistentIds = MutableStateFlow<Set<String>>(emptySet())
    override val persistentIds: StateFlow<Set<String>> = _persistentIds.asStateFlow()

    init {
        // The flow follows the file, so a write from another process (a shell
        // editing preferences) cannot leave the in-memory set behind.
        dataStore.data
            .map { it[KEY_PERSISTENT_IDS] ?: emptySet() }
            .distinctUntilChanged()
            .onEach { _persistentIds.value = it }
            .launchIn(appScope)
    }

    override suspend fun snapshot(): Set<String> =
        dataStore.data.map { it[KEY_PERSISTENT_IDS] ?: emptySet() }.first()

    override suspend fun setPersistent(sessionId: String, enabled: Boolean) {
        if (sessionId.isEmpty()) return
        val current = snapshot()
        val updated = if (enabled) current + sessionId else current - sessionId
        dataStore.edit { prefs ->
            if (updated.isEmpty()) prefs.remove(KEY_PERSISTENT_IDS)
            else prefs[KEY_PERSISTENT_IDS] = updated
        }
        _persistentIds.value = updated
    }

    private companion object {
        val KEY_PERSISTENT_IDS = stringSetPreferencesKey("persistent_session_ids")
    }
}
