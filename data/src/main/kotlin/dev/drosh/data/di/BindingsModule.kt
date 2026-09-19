package dev.drosh.data.di

import dev.drosh.data.input.HardwareKeyboardPresenceImpl
import dev.drosh.data.input.InputPreferencesRepositoryImpl
import dev.drosh.data.input.SubmitRawByteUseCaseImpl
import dev.drosh.data.session.ObserveActiveSessionUseCaseImpl
import dev.drosh.data.session.SessionRepositoryImpl
import dev.drosh.data.block.BlockRepositoryImpl
import dev.drosh.data.block.TrafficStatsCollector
import dev.drosh.data.settings.PinLockRepositoryImpl
import dev.drosh.data.settings.FirstLaunchRepositoryImpl
import dev.drosh.data.settings.SettingsRepositoryImpl
import dev.drosh.data.settings.TerminalFontSizeRepositoryImpl
import dev.drosh.data.terminal.BootstrapObserver
import dev.drosh.data.terminal.SubmitBlockCommandUseCaseImpl
import dev.drosh.data.terminal.TriggerBootstrap
import dev.drosh.domain.block.BlockRepository
import dev.drosh.domain.block.NetworkMetricsCollector
import dev.drosh.domain.input.HardwareKeyboardPresence
import dev.drosh.domain.input.InputPreferencesRepository
import dev.drosh.domain.input.SubmitRawByteUseCase
import dev.drosh.domain.session.ObserveActiveSessionUseCase
import dev.drosh.domain.session.SessionRepository
import dev.drosh.domain.settings.PinLockRepository
import dev.drosh.domain.settings.SettingsRepository
import dev.drosh.domain.terminal.ObserveBootstrapUseCase
import dev.drosh.domain.terminal.ObserveFirstLaunchUseCase
import dev.drosh.domain.terminal.SetTerminalFontSizeUseCase
import dev.drosh.domain.terminal.SubmitBlockCommandUseCase
import dev.drosh.domain.terminal.TriggerBootstrapUseCase
import dev.drosh.domain.agent.AgentSession
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings that wire `:data` implementations into the domain interfaces
 * consumed by `:ui`.
 *
 * Per AGENT.md §119-121 the data layer implements interfaces declared in
 * `:domain`. This module is the seam.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {

    @Binds
    @Singleton
    abstract fun bindObserveBootstrap(
        impl: BootstrapObserver,
    ): ObserveBootstrapUseCase

    @Binds
    @Singleton
    abstract fun bindTriggerBootstrap(
        impl: TriggerBootstrap,
    ): TriggerBootstrapUseCase

    @Binds
    @Singleton
    abstract fun bindObserveFirstLaunch(
        impl: FirstLaunchRepositoryImpl,
    ): ObserveFirstLaunchUseCase

    @Binds
    @Singleton
    abstract fun bindSetTerminalFontSize(
        impl: TerminalFontSizeRepositoryImpl,
    ): SetTerminalFontSizeUseCase

    @Binds
    @Singleton
    abstract fun bindSessionRepository(
        impl: SessionRepositoryImpl,
    ): SessionRepository

    @Binds
    @Singleton
    abstract fun bindObserveActiveSession(
        impl: ObserveActiveSessionUseCaseImpl,
    ): ObserveActiveSessionUseCase

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        impl: SettingsRepositoryImpl,
    ): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindPinLockRepository(
        impl: PinLockRepositoryImpl,
    ): PinLockRepository

    @Binds
    @Singleton
    abstract fun bindBlockRepository(
        impl: BlockRepositoryImpl,
    ): BlockRepository

    @Binds
    @Singleton
    abstract fun bindNetworkMetrics(
        impl: TrafficStatsCollector,
    ): NetworkMetricsCollector

    @Binds
    @Singleton
    abstract fun bindSubmitBlockCommand(
        impl: SubmitBlockCommandUseCaseImpl,
    ): SubmitBlockCommandUseCase

    @Binds
    @Singleton
    abstract fun bindInputPreferencesRepository(
        impl: InputPreferencesRepositoryImpl,
    ): InputPreferencesRepository

    @Binds
    @Singleton
    abstract fun bindSubmitRawByte(
        impl: SubmitRawByteUseCaseImpl,
    ): SubmitRawByteUseCase

    @Binds
    @Singleton
    abstract fun bindHardwareKeyboardPresence(
        impl: HardwareKeyboardPresenceImpl,
    ): HardwareKeyboardPresence

    @Binds
    @Singleton
    abstract fun bindAgentSession(
        impl: dev.drosh.agent.runtime.AgentRuntime,
    ): AgentSession
}
