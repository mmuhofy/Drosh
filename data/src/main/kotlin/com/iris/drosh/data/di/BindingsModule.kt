package com.iris.irisshell.data.di

import com.iris.irisshell.data.input.HardwareKeyboardPresenceImpl
import com.iris.irisshell.data.input.InputPreferencesRepositoryImpl
import com.iris.irisshell.data.input.SubmitRawByteUseCaseImpl
import com.iris.irisshell.data.session.ObserveActiveSessionUseCaseImpl
import com.iris.irisshell.data.session.SessionRepositoryImpl
import com.iris.irisshell.data.block.BlockRepositoryImpl
import com.iris.irisshell.data.block.TrafficStatsCollector
import com.iris.irisshell.data.settings.PinLockRepositoryImpl
import com.iris.irisshell.data.settings.FirstLaunchRepositoryImpl
import com.iris.irisshell.data.settings.SettingsRepositoryImpl
import com.iris.irisshell.data.settings.TerminalFontSizeRepositoryImpl
import com.iris.irisshell.data.terminal.BootstrapObserver
import com.iris.irisshell.data.terminal.SubmitBlockCommandUseCaseImpl
import com.iris.irisshell.data.terminal.TriggerBootstrap
import com.iris.irisshell.domain.block.BlockRepository
import com.iris.irisshell.domain.block.NetworkMetricsCollector
import com.iris.irisshell.domain.input.HardwareKeyboardPresence
import com.iris.irisshell.domain.input.InputPreferencesRepository
import com.iris.irisshell.domain.input.SubmitRawByteUseCase
import com.iris.irisshell.domain.session.ObserveActiveSessionUseCase
import com.iris.irisshell.domain.session.SessionRepository
import com.iris.irisshell.domain.settings.PinLockRepository
import com.iris.irisshell.domain.settings.SettingsRepository
import com.iris.irisshell.domain.terminal.ObserveBootstrapUseCase
import com.iris.irisshell.domain.terminal.ObserveFirstLaunchUseCase
import com.iris.irisshell.domain.terminal.SetTerminalFontSizeUseCase
import com.iris.irisshell.domain.terminal.SubmitBlockCommandUseCase
import com.iris.irisshell.domain.terminal.TriggerBootstrapUseCase
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
}
