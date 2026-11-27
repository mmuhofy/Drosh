package dev.drosh.ui.session

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drosh.domain.session.ObserveActiveSessionUseCase
import dev.drosh.domain.session.PersistentSessionStore
import dev.drosh.domain.session.ResourceSnapshot
import dev.drosh.domain.session.SessionProcessInfo
import dev.drosh.domain.session.SessionRepository
import dev.drosh.domain.session.SessionGridLayout
import dev.drosh.domain.session.SessionGridLayoutStore
import dev.drosh.domain.session.SessionGroupLayout
import dev.drosh.domain.session.SessionSnapshot
import dev.drosh.domain.session.SessionTelemetry
import dev.drosh.domain.session.displayableSessionName
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
    private val gridLayoutStore: SessionGridLayoutStore,
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

    /**
     * The grid's arrangement: pins, drag order and UI groups.
     *
     * Null until the store has been read. The screen must not write an empty
     * arrangement back while it is waiting — that is how a saved one is lost.
     */
    val gridLayout: StateFlow<SessionGridLayout?> = gridLayoutStore.layout

    private fun updateLayout(transform: (SessionGridLayout) -> SessionGridLayout) {
        viewModelScope.launch {
            val current = gridLayoutStore.layout.value ?: SessionGridLayout.EMPTY
            gridLayoutStore.save(transform(current))
        }
    }

    /** Pins a card to the top of its section, or unpins it. */
    fun togglePin(sessionId: String) = updateLayout { layout ->
        val pinned = if (sessionId in layout.pinnedIds) {
            layout.pinnedIds - sessionId
        } else {
            layout.pinnedIds + sessionId
        }
        layout.copy(pinnedIds = pinned)
    }

    /** Stores a completed drag: [heldSlotId] now sits where [targetSlotId] was. */
    fun reorder(heldSlotId: String, targetSlotId: String) = updateLayout { layout ->
        layout.copy(orderedIds = moveSlot(layout.orderedIds, heldSlotId, targetSlotId))
    }

    /** Replaces the grid's groups. The screen owns the drop rules, not the store. */
    internal fun setGroups(groups: List<SessionGroup>) = updateLayout { layout ->
        layout.copy(
            groups = groups.map { SessionGroupLayout(it.memberIds, it.name) },
        )
    }

    /**
     * Names a group the user already has.
     *
     * Matched by membership, not by slot: the group's first member can change
     * when its members are reordered, and the name must follow the group rather
     * than the first id.
     */
    fun renameGroup(memberIds: List<String>, name: String) {
        val ids = memberIds.toSet()
        val trimmed = name.trim().take(MAX_GROUP_NAME_LENGTH)
        updateLayout { layout ->
            layout.copy(
                groups = layout.groups.map { group ->
                    if (group.memberIds.toSet() == ids) group.copy(name = trimmed) else group
                },
            )
        }
    }

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
            // The bar asks which terminal it is over; a stored runtime binary is
            // not an answer, so an unusable name reads as the default.
            list.firstOrNull { it.id == id }?.name?.let(::displayableSessionName)
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

    private companion object {
        const val MAX_GROUP_NAME_LENGTH = 60
    }
}
