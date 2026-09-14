package com.iris.irisshell.data.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.iris.irisshell.data.di.ApplicationScope
import com.iris.irisshell.data.local.DroshDatabase
import com.iris.irisshell.data.local.irisShellDataStore
import com.iris.irisshell.domain.session.SessionRepository
import com.iris.irisshell.domain.session.SessionSnapshot
import com.iris.irisshell.domain.session.SessionState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: DroshDatabase,
    @ApplicationScope private val appScope: CoroutineScope,
) : SessionRepository {

    private val dao: SessionDao = database.sessionDao()
    private val dataStore: DataStore<Preferences> = context.irisShellDataStore

    private val _livePreviews = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val livePreviews: StateFlow<Map<String, List<String>>> = _livePreviews.asStateFlow()

    private val _shouldExit = MutableStateFlow(false)
    override val shouldExit: StateFlow<Boolean> = _shouldExit.asStateFlow()

    override suspend fun setShouldExit(value: Boolean) {
        _shouldExit.value = value
    }

    override fun observeAll(): Flow<List<SessionSnapshot>> =
        combine(
            dao.observeAll(),
            _livePreviews
        ) { rows, liveMap ->
            rows.map { row ->
                row.toSnapshot(liveMap[row.id] ?: emptyList())
            }
        }
            .distinctUntilChanged()

    override fun observe(id: String): Flow<SessionSnapshot?> =
        combine(
            dao.observe(id),
            _livePreviews
        ) { row, liveMap ->
            row?.toSnapshot(liveMap[row.id] ?: emptyList())
        }
            .distinctUntilChanged()

    override suspend fun create(name: String): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        dao.upsert(
            SessionEntity(
                id = id,
                name = name,
                state = SessionState.Idle.name,
                createdAtMs = now,
                lastUsedAtMs = now,
                lastSnapshot = "",
            )
        )
        dataStore.edit { it[KEY_ACTIVE_SESSION_ID] = id }
        _shouldExit.value = false
        return id
    }

    override suspend fun rename(id: String, newName: String) {
        dao.rename(id, newName)
    }

    override suspend fun delete(id: String) {
        val activeId = dataStore.data.map { it[KEY_ACTIVE_SESSION_ID] }.first()
        dao.delete(id)
        if (activeId == id) {
            dataStore.edit { it.remove(KEY_ACTIVE_SESSION_ID) }
            val remaining = dao.observeAll().first()
            val fallback = remaining.firstOrNull()
            if (fallback != null) {
                dataStore.edit { it[KEY_ACTIVE_SESSION_ID] = fallback.id }
            }
        }
    }

    override suspend fun restoreSession(snapshot: SessionSnapshot, activate: Boolean) {
        dao.upsert(
            SessionEntity(
                id = snapshot.id,
                name = snapshot.name,
                state = SessionState.Idle.name,
                createdAtMs = snapshot.createdAtMs,
                lastUsedAtMs = System.currentTimeMillis(),
                lastSnapshot = "",
            )
        )
        if (activate) {
            dataStore.edit { it[KEY_ACTIVE_SESSION_ID] = snapshot.id }
        }
    }

    override suspend fun touch(id: String) {
        val row = dao.observe(id).first() ?: return
        dao.updateRuntime(
            id = id,
            nowMs = System.currentTimeMillis(),
            state = row.state,
            snapshot = row.lastSnapshot,
        )
    }

    override suspend fun updateLivePreview(id: String, lines: List<String>) {
        _livePreviews.update { current ->
            current + (id to lines)
        }
    }

    override suspend fun updateState(id: String, state: SessionState) {
        dao.updateState(id, state.name)
    }

    fun observeActiveId(): Flow<String?> =
        dataStore.data.map { it[KEY_ACTIVE_SESSION_ID] }.distinctUntilChanged()

    fun observeAllIds(): Flow<List<String>> =
        dao.observeAll().map { rows -> rows.map { it.id } }

    suspend fun currentActiveId(): String? =
        dataStore.data.map { it[KEY_ACTIVE_SESSION_ID] }.first()

    suspend fun setActiveId(id: String?) {
        dataStore.edit { prefs ->
            if (id == null) prefs.remove(KEY_ACTIVE_SESSION_ID)
            else prefs[KEY_ACTIVE_SESSION_ID] = id
        }
    }

    fun observeActive(): Flow<SessionSnapshot?> =
        combine(
            observeActiveId(),
            dao.observeAll(),
            _livePreviews
        ) { activeId, rows, liveMap ->
            if (activeId == null) null
            else rows.firstOrNull { it.id == activeId }?.toSnapshot(liveMap[activeId] ?: emptyList())
        }
            .distinctUntilChanged()

    private fun SessionEntity.toSnapshot(live: List<String>): SessionSnapshot =
        SessionSnapshot(
            id = id,
            name = name,
            state = runCatching { SessionState.valueOf(state) }.getOrDefault(SessionState.Idle),
            createdAtMs = createdAtMs,
            lastUsedAtMs = lastUsedAtMs,
            liveSnapshotLines = live,
        )

    private companion object {
        val KEY_ACTIVE_SESSION_ID = stringPreferencesKey("active_session_id")
    }
}