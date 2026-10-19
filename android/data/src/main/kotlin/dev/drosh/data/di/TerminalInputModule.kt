package dev.drosh.data.di

import dev.drosh.domain.input.SendKeyIntentUseCase
import dev.drosh.domain.input.StickyModifierState
import dev.drosh.terminal.ExtraKeyState
import dev.drosh.terminal.TerminalManager
import dev.drosh.terminal.input.SendKeyIntentUseCaseImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module wiring the on-screen input system's terminal-side
 * singletons. Lives in `:data` because `:terminal` does NOT apply the
 * Hilt Gradle plugin — Hilt `@Module` classes must be compiled by a
 * module that has Hilt annotation processing enabled.
 *
 * `ExtraKeyState` is shared between `TerminalViewClientImpl` (classic
 * mode key-handler reads virtual CTRL/ALT) and `InputDispatcher`
 * (extra-keys bar dispatches modifier intent), hence `@Singleton`.
 */
@Module
@InstallIn(SingletonComponent::class)
object TerminalInputModule {

    @Provides
    @Singleton
    fun provideExtraKeyState(): ExtraKeyState = ExtraKeyState()

    @Provides
    @Singleton
    fun provideStickyModifierState(state: ExtraKeyState): StickyModifierState = state

    @Provides
    @Singleton
    fun provideSendKeyIntentUseCase(
        state: ExtraKeyState,
        manager: TerminalManager,
    ): SendKeyIntentUseCase = SendKeyIntentUseCaseImpl(state, manager)
}
