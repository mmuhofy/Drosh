package com.iris.irisshell.data.session

import com.iris.irisshell.domain.session.ObserveActiveSessionUseCase
import com.iris.irisshell.domain.session.SessionSnapshot
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveActiveSessionUseCaseImpl @Inject constructor(
    private val sessionRepositoryImpl: SessionRepositoryImpl,
) : ObserveActiveSessionUseCase {

    override fun activeId(): Flow<String?> =
        sessionRepositoryImpl.observeActiveId()

    override fun activeSnapshot(): Flow<SessionSnapshot?> =
        sessionRepositoryImpl.observeActive()

    override suspend fun setActive(id: String) {
        sessionRepositoryImpl.setActiveId(id)
    }

    override suspend fun createAndActivate(name: String): String {
        val newId = sessionRepositoryImpl.create(name)
        sessionRepositoryImpl.setActiveId(newId)
        return newId
    }
}