package dev.drosh.data.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.drosh.data.di.ApplicationScope
import dev.drosh.data.local.DroshDatabase
import dev.drosh.data.local.irisShellDataStore
import dev.drosh.domain.session.SessionRepository
import dev.drosh.domain.session.DEFAULT_SESSION_NAME
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.domain.session.SessionState
import dev.drosh.domain.session.displayableSessionName
import dev.drosh.domain.session.isRuntimeProcessName
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
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomSessionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: DroshDatabase,
    @ApplicationScope private val appScope: CoroutineScope,
) : SessionRepository {

    private val dao: SessionDao = database.sessionDao()
    private val dataStore: DataStore<Preferences> = context.irisShellDataStore

    init {
        // Repair rows written by an older name probe. Those builds could persist
        // the guest runtime (`libchroot-ng.so`) as a session's name before the
        // shell had exec'd, and a name that is not a generated default was then
        // never replaced — the card and the top bar stayed titled after a file.
        // The runtime is never something the user typed, so the row goes back to
        // the default and the next probe can fill in the shell's real name.
        appScope.launch {
            dao.observeAll().first()
                .filter { isRuntimeProcessName(it.name) }
                .forEach { dao.rename(it.id, DEFAULT_SESSION_NAME) }
        }
    }

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
                // Legacy column — always ungrouped now that the feature is gone.
                workspaceId = null,
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
            val remaining = dao.observeAll().first()
            val fallback = remaining.firstOrNull()
            if (fallback != null) {
                dataStore.edit { it[KEY_ACTIVE_SESSION_ID] = fallback.id }
            } else {
                // No replacement. Deleting the last session is the user's call to
                // make: the terminal raises the exit dialog, and creating a
                // "Default" here silently undid the delete and made it look like
                // the session could not be removed.
                dataStore.edit { it.remove(KEY_ACTIVE_SESSION_ID) }
            }
        }
    }

    override suspend fun purgeEnded(keepActiveId: String?) {
        val ended = dao.observeAll().first()
            .filter { it.state == SessionState.Closed.name }
        ended.forEach { dao.delete(it.id) }
        // The active row can be one of these when every session has ended, and
        // leaving the pointer behind shows a session that no longer exists.
        if (keepActiveId != null && ended.any { it.id == keepActiveId }) {
            dataStore.edit { it.remove(KEY_ACTIVE_SESSION_ID) }
        }
    }

    override suspend fun deleteNonPersistentSessions(keepIds: Set<String>) {
        val rows = dao.observeAll().first()
        val doomed = rows.map { it.id }.toSet() - keepIds
        if (doomed.isEmpty()) return

        doomed.forEach { dao.delete(it) }
        // The preview map is in-memory state belonging to these rows. Leaving it
        // behind would keep closed sessions alive in the grid's ticker until the
        // process died, which is exactly what this sweep is meant to prevent.
        _livePreviews.update { current -> current.filterKeys { it !in doomed } }

        val activeId = dataStore.data.map { it[KEY_ACTIVE_SESSION_ID] }.first()
        if (activeId in doomed) {
            // Point at a surviving pinned row if there is one; a relaunch then
            // opens the session the user chose to keep instead of falling back
            // to whichever row happens to be first.
            val survivor = rows.firstOrNull { it.id in keepIds }?.id
            dataStore.edit { prefs ->
                if (survivor != null) prefs[KEY_ACTIVE_SESSION_ID] = survivor
                else prefs.remove(KEY_ACTIVE_SESSION_ID)
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
                // Carried over, not reset. Restoring a session is reopening a
                // record the user already filed under a project; dropping the
                // grouping would silently ungroup it as a side effect of a
                // restore the user thinks is harmless.
                workspaceId = snapshot.workspaceId,
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
        // A preview that has not changed is not an update. The grid's ticker
        // writes every live session on every tick, and an unconditional map
        // write would rebuild the snapshot list — and with it every card — once
        // per session per tick for output nobody can see change.
        if (_livePreviews.value[id] == lines) return
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
            // Defence in depth for a row the repair above has not reached yet:
            // no UI ever draws a runtime binary as a session's name.
            name = displayableSessionName(name),
            state = runCatching { SessionState.valueOf(state) }.getOrDefault(SessionState.Idle),
            createdAtMs = createdAtMs,
            lastUsedAtMs = lastUsedAtMs,
            liveSnapshotLines = live,
            workspaceId = workspaceId,
        )

    private companion object {
        val KEY_ACTIVE_SESSION_ID = stringPreferencesKey("active_session_id")
    }
}