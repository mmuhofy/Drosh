package com.iris.irisshell.domain.session

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface SessionRepository {
    fun observeAll(): Flow<List<SessionSnapshot>>
    fun observe(id: String): Flow<SessionSnapshot?>
    suspend fun create(name: String): String
    suspend fun rename(id: String, newName: String)
    suspend fun delete(id: String)
    suspend fun restoreSession(snapshot: SessionSnapshot, activate: Boolean = false)
    suspend fun touch(id: String)
    suspend fun updateLivePreview(id: String, lines: List<String>)
    suspend fun updateState(id: String, state: SessionState)

    val shouldExit: StateFlow<Boolean>
    suspend fun setShouldExit(value: Boolean)
}