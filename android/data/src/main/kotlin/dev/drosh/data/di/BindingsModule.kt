package dev.drosh.data.di

import dev.drosh.data.input.AndroidHardwareKeyboardPresence
import dev.drosh.data.input.AndroidImePresence
import dev.drosh.data.input.SettingsInputPreferencesRepository
import dev.drosh.data.input.TerminalSubmitRawByteUseCase
import dev.drosh.data.session.DefaultObserveActiveSessionUseCase
import dev.drosh.data.session.DataStoreDeviceIdentityRepository
import dev.drosh.data.session.DataStorePersistentSessionStore
import dev.drosh.data.session.SessionManagerAdapter
import dev.drosh.data.session.RoomSessionRepository
import dev.drosh.data.agent.RoomAgentChatRepository
import dev.drosh.data.agent.CatalogLlmProviderRepository
import dev.drosh.data.agent.EncryptedToolCredentialRepository
import dev.drosh.data.agent.RoomTranscriptStore
import dev.drosh.data.backup.FileBackupRepository
import dev.drosh.data.block.InMemoryBlockRepository
import dev.drosh.data.block.TrafficStatsCollector
import dev.drosh.data.settings.DataStorePinLockRepository
import dev.drosh.data.settings.DataStoreFirstLaunchRepository
import dev.drosh.data.settings.FileBackgroundImageStore
import dev.drosh.data.settings.TomlSettingsRepository
import dev.drosh.data.settings.toml.TomlSettingsStore
import dev.drosh.data.theme.ApplyTheme
import dev.drosh.data.font.FileFontRepository
import dev.drosh.data.theme.FileThemeRepository
import dev.drosh.data.terminal.BootstrapObserver
import dev.drosh.data.terminal.DataStorePaneLayoutRepository
import dev.drosh.data.terminal.TerminalSubmitBlockCommandUseCase
import dev.drosh.data.terminal.TriggerBootstrap
import dev.drosh.data.ssh.RoomSshHostRepository
import dev.drosh.data.ssh.RoomSshKeyRepository
import dev.drosh.data.ssh.EncryptedSshCredentialVault
import dev.drosh.data.ssh.SshjSessionLauncher
import dev.drosh.domain.agent.AgentChatRepository
import dev.drosh.domain.agent.AgentSession
import dev.drosh.domain.agent.LlmProviderRepository
import dev.drosh.domain.agent.ToolCredentialRepository
import dev.drosh.domain.agent.TranscriptStore
import dev.drosh.domain.backup.BackupRepository
import dev.drosh.domain.block.BlockRepository
import dev.drosh.domain.block.NetworkMetricsCollector
import dev.drosh.domain.input.HardwareKeyboardPresence
import dev.drosh.domain.input.ImePresence
import dev.drosh.domain.input.InputPreferencesRepository
import dev.drosh.domain.input.SubmitRawByteUseCase
import dev.drosh.domain.session.ObserveActiveSessionUseCase
import dev.drosh.domain.session.DeviceIdentityRepository
import dev.drosh.domain.session.PersistentSessionStore
import dev.drosh.domain.session.SessionGridLayoutStore
import dev.drosh.domain.session.SessionRepository
import dev.drosh.domain.session.SessionTelemetry
import dev.drosh.domain.settings.BackgroundImageStore
import dev.drosh.domain.settings.PinLockRepository
import dev.drosh.domain.settings.SettingsRepository
import dev.drosh.domain.settings.SettingsStore
import dev.drosh.domain.font.FontRepository
import dev.drosh.domain.settings.ThemeRepository
import dev.drosh.domain.settings.ApplyThemeUseCase
import dev.drosh.domain.terminal.ObserveBootstrapUseCase
import dev.drosh.domain.terminal.ObserveFirstLaunchUseCase
import dev.drosh.domain.terminal.PaneLayoutRepository
import dev.drosh.domain.terminal.SubmitBlockCommandUseCase
import dev.drosh.domain.terminal.TriggerBootstrapUseCase
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
        impl: DataStoreDeviceIdentityRepository,
    ): DeviceIdentityRepository

    @Binds
    @Singleton
    abstract fun bindFontRepository(
        impl: FileFontRepository,
    ): FontRepository

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
        impl: DataStoreFirstLaunchRepository,
    ): ObserveFirstLaunchUseCase

    @Binds
    @Singleton
    abstract fun bindSessionRepository(
        impl: RoomSessionRepository,
    ): SessionRepository

    @Binds
    @Singleton
    abstract fun bindObserveActiveSession(
        impl: DefaultObserveActiveSessionUseCase,
    ): ObserveActiveSessionUseCase

    @Binds
    @Singleton
    abstract fun bindPersistentSessionStore(
        impl: DataStorePersistentSessionStore,
    ): PersistentSessionStore

    @Binds
    @Singleton
    abstract fun bindSessionTelemetry(
        impl: SessionManagerAdapter,
    ): SessionTelemetry

    @Binds
    @Singleton
    abstract fun bindSshHostRepository(
        impl: RoomSshHostRepository,
    ): SshHostRepository

    @Binds
    @Singleton
    abstract fun bindSshKeyRepository(
        impl: RoomSshKeyRepository,
    ): SshKeyRepository

    @Binds
    @Singleton
    abstract fun bindSshCredentialVault(
        impl: EncryptedSshCredentialVault,
    ): SshCredentialVault

    @Binds
    @Singleton
    abstract fun bindSshSessionLauncher(
        impl: SshjSessionLauncher,
    ): SshSessionLauncher

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        impl: TomlSettingsRepository,
    ): SettingsRepository

    /** The TOML file behind the facade above. */
    @Binds
    @Singleton
    abstract fun bindSettingsStore(
        impl: TomlSettingsStore,
    ): SettingsStore

    @Binds
    @Singleton
    abstract fun bindThemeRepository(
        impl: FileThemeRepository,
    ): ThemeRepository

    /**
     * Putting a theme into force: writes the theme file, then the palette into
     * the settings file. Used by onboarding, where the pick is made before the
     * rootfs exists and the write has to wait for the bootstrap.
     */
    @Binds
    @Singleton
    abstract fun bindApplyTheme(
        impl: ApplyTheme,
    ): ApplyThemeUseCase

    @Binds
    @Singleton
    abstract fun bindBackgroundImageStore(
        impl: FileBackgroundImageStore,
    ): BackgroundImageStore

    @Binds
    @Singleton
    abstract fun bindPinLockRepository(
        impl: DataStorePinLockRepository,
    ): PinLockRepository


    @Binds
    @Singleton
    abstract fun bindPaneLayoutRepository(
        impl: DataStorePaneLayoutRepository,
    ): PaneLayoutRepository

    @Binds
    @Singleton
    abstract fun bindSessionGridLayoutStore(
        impl: DataStoreSessionGridLayoutStore,
    ): SessionGridLayoutStore

    @Binds
    @Singleton
    abstract fun bindBlockRepository(
        impl: InMemoryBlockRepository,
    ): BlockRepository

    @Binds
    @Singleton
    abstract fun bindNetworkMetrics(
        impl: TrafficStatsCollector,
    ): NetworkMetricsCollector

    @Binds
    @Singleton
    abstract fun bindSubmitBlockCommand(
        impl: TerminalSubmitBlockCommandUseCase,
    ): SubmitBlockCommandUseCase

    @Binds
    @Singleton
    abstract fun bindInputPreferencesRepository(
        impl: SettingsInputPreferencesRepository,
    ): InputPreferencesRepository

    @Binds
    @Singleton
    abstract fun bindSubmitRawByte(
        impl: TerminalSubmitRawByteUseCase,
    ): SubmitRawByteUseCase

    @Binds
    @Singleton
    abstract fun bindHardwareKeyboardPresence(
        impl: AndroidHardwareKeyboardPresence,
    ): HardwareKeyboardPresence

    @Binds
    @Singleton
    abstract fun bindImePresence(
        impl: AndroidImePresence,
    ): ImePresence

    @Binds
    @Singleton
    abstract fun bindLlmProviderRepository(
        impl: CatalogLlmProviderRepository,
    ): LlmProviderRepository

    @Binds
    @Singleton
    abstract fun bindAgentChatRepository(
        impl: RoomAgentChatRepository,
    ): AgentChatRepository

    @Binds
    @Singleton
    abstract fun bindTranscriptStore(
        impl: RoomTranscriptStore,
    ): TranscriptStore

    @Binds
    @Singleton
    abstract fun bindToolCredentialRepository(
        impl: EncryptedToolCredentialRepository,
    ): ToolCredentialRepository

    @Binds
    @Singleton
    abstract fun bindAgentSession(
        impl: dev.drosh.feature.agent.runtime.AgentLoop,
    ): AgentSession

    /**
     * Backup and restore.
     *
     * Singleton because one archive at a time is the honest concurrency story:
     * two backups running against the same cache directory would race over
     * the staging files, and the restore path moves the rootfs aside — a
     * second one mid-flight would find it missing.
     */
    @Binds
    @Singleton
    abstract fun bindBackupRepository(
        impl: FileBackupRepository,
    ): BackupRepository
}
