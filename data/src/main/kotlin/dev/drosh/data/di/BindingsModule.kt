package dev.drosh.data.di

import dev.drosh.data.input.HardwareKeyboardPresenceImpl
import dev.drosh.data.input.ImePresenceImpl
import dev.drosh.data.input.InputPreferencesRepositoryImpl
import dev.drosh.data.input.SubmitRawByteUseCaseImpl
import dev.drosh.data.session.ObserveActiveSessionUseCaseImpl
import dev.drosh.data.session.DeviceIdentityRepositoryImpl
import dev.drosh.data.session.SessionRepositoryImpl
import dev.drosh.data.agent.AgentChatRepositoryImpl
import dev.drosh.data.agent.LlmProviderRepositoryImpl
import dev.drosh.data.agent.ToolCredentialRepositoryImpl
import dev.drosh.data.agent.TranscriptStoreImpl
import dev.drosh.data.block.BlockRepositoryImpl
import dev.drosh.data.block.TrafficStatsCollector
import dev.drosh.data.settings.PinLockRepositoryImpl
import dev.drosh.data.settings.FirstLaunchRepositoryImpl
import dev.drosh.data.settings.SettingsRepositoryImpl
import dev.drosh.data.settings.toml.TomlSettingsStore
import dev.drosh.data.terminal.BootstrapObserver
import dev.drosh.data.terminal.PaneLayoutRepositoryImpl
import dev.drosh.data.terminal.SubmitBlockCommandUseCaseImpl
import dev.drosh.data.terminal.TriggerBootstrap
import dev.drosh.data.workspace.WorkspaceRepositoryImpl
import dev.drosh.data.ssh.SshHostRepositoryImpl
import dev.drosh.data.ssh.SshKeyRepositoryImpl
import dev.drosh.data.ssh.SshCredentialVaultImpl
import dev.drosh.data.ssh.SshSessionLauncherImpl
import dev.drosh.domain.agent.AgentChatRepository
import dev.drosh.domain.agent.AgentSession
import dev.drosh.domain.agent.LlmProviderRepository
import dev.drosh.domain.agent.ToolCredentialRepository
import dev.drosh.domain.agent.TranscriptStore
import dev.drosh.domain.block.BlockRepository
import dev.drosh.domain.block.NetworkMetricsCollector
import dev.drosh.domain.input.HardwareKeyboardPresence
import dev.drosh.domain.input.ImePresence
import dev.drosh.domain.input.InputPreferencesRepository
import dev.drosh.domain.input.SubmitRawByteUseCase
import dev.drosh.domain.session.ObserveActiveSessionUseCase
import dev.drosh.domain.session.DeviceIdentityRepository
import dev.drosh.domain.session.SessionRepository
import dev.drosh.domain.settings.PinLockRepository
import dev.drosh.domain.settings.SettingsRepository
import dev.drosh.domain.settings.SettingsStore
import dev.drosh.domain.terminal.ObserveBootstrapUseCase
import dev.drosh.domain.terminal.ObserveFirstLaunchUseCase
import dev.drosh.domain.terminal.PaneLayoutRepository
import dev.drosh.domain.terminal.SubmitBlockCommandUseCase
import dev.drosh.domain.terminal.TriggerBootstrapUseCase
import dev.drosh.domain.workspace.WorkspaceRepository
import dev.drosh.domain.ssh.SshHostRepository
import dev.drosh.domain.ssh.SshKeyRepository
import dev.drosh.domain.ssh.SshCredentialVault
import dev.drosh.domain.ssh.SshSessionLauncher
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
    abstract fun bindDeviceIdentityRepository(
        impl: DeviceIdentityRepositoryImpl,
    ): DeviceIdentityRepository

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
    abstract fun bindWorkspaceRepository(
        impl: WorkspaceRepositoryImpl,
    ): WorkspaceRepository

    @Binds
    @Singleton
    abstract fun bindSshHostRepository(
        impl: SshHostRepositoryImpl,
    ): SshHostRepository

    @Binds
    @Singleton
    abstract fun bindSshKeyRepository(
        impl: SshKeyRepositoryImpl,
    ): SshKeyRepository

    @Binds
    @Singleton
    abstract fun bindSshCredentialVault(
        impl: SshCredentialVaultImpl,
    ): SshCredentialVault

    @Binds
    @Singleton
    abstract fun bindSshSessionLauncher(
        impl: SshSessionLauncherImpl,
    ): SshSessionLauncher

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        impl: SettingsRepositoryImpl,
    ): SettingsRepository

    /** The TOML file behind the facade above. */
    @Binds
    @Singleton
    abstract fun bindSettingsStore(
        impl: TomlSettingsStore,
    ): SettingsStore

    @Binds
    @Singleton
    abstract fun bindPinLockRepository(
        impl: PinLockRepositoryImpl,
    ): PinLockRepository


    @Binds
    @Singleton
    abstract fun bindPaneLayoutRepository(
        impl: PaneLayoutRepositoryImpl,
    ): PaneLayoutRepository

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
    abstract fun bindImePresence(
        impl: ImePresenceImpl,
    ): ImePresence

    @Binds
    @Singleton
    abstract fun bindLlmProviderRepository(
        impl: LlmProviderRepositoryImpl,
    ): LlmProviderRepository

    @Binds
    @Singleton
    abstract fun bindAgentChatRepository(
        impl: AgentChatRepositoryImpl,
    ): AgentChatRepository

    @Binds
    @Singleton
    abstract fun bindTranscriptStore(
        impl: TranscriptStoreImpl,
    ): TranscriptStore

    @Binds
    @Singleton
    abstract fun bindToolCredentialRepository(
        impl: ToolCredentialRepositoryImpl,
    ): ToolCredentialRepository

    @Binds
    @Singleton
    abstract fun bindAgentSession(
        impl: dev.drosh.agent.runtime.AgentLoop,
    ): AgentSession
}
