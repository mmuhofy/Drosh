package dev.drosh.ui.session

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drosh.domain.session.ObserveActiveSessionUseCase
import dev.drosh.domain.session.PersistentSessionStore
import dev.drosh.domain.session.ResourceSnapshot
import dev.drosh.domain.session.SessionProcessInfo
import dev.drosh.domain.session.SessionRepository
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.domain.session.SessionTelemetry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionSwitcherViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val activeSession: ObserveActiveSessionUseCase,
    private val persistentSessionStore: PersistentSessionStore,
    sessionTelemetry: SessionTelemetry,
) : ViewModel() {

    private val _thumbnails = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    /** Last captured frame per session, keyed by persistent id. */
    val thumbnails: StateFlow<Map<String, ImageBitmap>> = _thumbnails.asStateFlow()

    fun setThumbnail(sessionId: String, bitmap: ImageBitmap) {
        // Defence in depth: the capture site already filters these, but a bad
        // caller must not be able to put an empty picture on a session card.
        if (sessionId.isEmpty() || bitmap.width <= 0 || bitmap.height <= 0) return
        if (_thumbnails.value[sessionId] === bitmap) return
        _thumbnails.value = _thumbnails.value + (sessionId to bitmap)
    }

    /** Live process rows from the engine, sampled in :data. */
    val processes: StateFlow<List<SessionProcessInfo>> = sessionTelemetry.processes

    /** Live device and renderer sample from :data. */
    val resources: StateFlow<ResourceSnapshot> = sessionTelemetry.resources

    /** Sessions pinned to survive a full app teardown. Empty means none. */
    val persistentIds: StateFlow<Set<String>> = persistentSessionStore.persistentIds

    fun setPersistent(sessionId: String, enabled: Boolean) {
        viewModelScope.launch { persistentSessionStore.setPersistent(sessionId, enabled) }
    }

    val allSessions: StateFlow<List<SessionSnapshot>> =
        sessionRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val activeId: StateFlow<String?> =
        activeSession.activeId()
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val shouldExit: StateFlow<Boolean> =
        sessionRepository.shouldExit
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Display name of the currently-active session, or null. */
    val activeName: StateFlow<String?> =
        combine(allSessions, activeId) { list, id ->
            list.firstOrNull { it.id == id }?.name
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun createNew(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { activeSession.createAndActivate(name) }
    }

    fun rename(id: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch { sessionRepository.rename(id, newName) }
    }

    fun delete(id: String) {
        viewModelScope.launch { sessionRepository.delete(id) }
    }

    /** Clears out every session that has already ended. */
    fun purgeEnded() {
        viewModelScope.launch { sessionRepository.purgeEnded(activeId.value) }
    }

    fun restoreSession(snapshot: SessionSnapshot, activate: Boolean = false) {
        viewModelScope.launch {
            sessionRepository.restoreSession(snapshot, activate)
        }
    }

    fun activate(id: String) {
        viewModelScope.launch { activeSession.setActive(id) }
    }
}
