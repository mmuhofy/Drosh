package com.iris.irisshell.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iris.irisshell.domain.session.ObserveActiveSessionUseCase
import com.iris.irisshell.domain.session.SessionRepository
import com.iris.irisshell.domain.session.SessionSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionSwitcherViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val activeSession: ObserveActiveSessionUseCase,
) : ViewModel() {

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

    fun restoreSession(snapshot: SessionSnapshot, activate: Boolean = false) {
        viewModelScope.launch {
            sessionRepository.restoreSession(snapshot, activate)
        }
    }

    fun activate(id: String) {
        viewModelScope.launch { activeSession.setActive(id) }
    }
}
