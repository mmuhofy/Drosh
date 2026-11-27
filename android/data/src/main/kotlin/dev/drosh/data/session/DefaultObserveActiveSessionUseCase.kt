package dev.drosh.data.session

import dev.drosh.domain.session.ObserveActiveSessionUseCase
import dev.drosh.domain.session.SessionSnapshot
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class DefaultObserveActiveSessionUseCase @Inject constructor(
    private val sessionRepository: RoomSessionRepository,
) : ObserveActiveSessionUseCase {

    override fun activeId(): Flow<String?> =
        sessionRepository.observeActiveId()

    override fun activeSnapshot(): Flow<SessionSnapshot?> =
        sessionRepository.observeActive()

    override suspend fun setActive(id: String) {
        sessionRepository.setActiveId(id)
    }

    override suspend fun createAndActivate(name: String): String {
        val newId = sessionRepository.create(name)
        sessionRepository.setActiveId(newId)
        return newId
    }
}