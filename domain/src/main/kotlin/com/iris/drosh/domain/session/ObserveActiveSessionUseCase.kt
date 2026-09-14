package com.iris.irisshell.domain.session

import kotlinx.coroutines.flow.Flow

interface ObserveActiveSessionUseCase {
    fun activeId(): Flow<String?>
    fun activeSnapshot(): Flow<SessionSnapshot?>
    suspend fun setActive(id: String)
    suspend fun createAndActivate(name: String): String
}