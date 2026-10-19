package dev.drosh.di

import dev.drosh.data.file.RootfsGuestFileRepository
import dev.drosh.domain.file.GuestFileRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the editor's file access to the rootfs implementation.
 *
 * Its own file rather than an addition to `BindingsModule` because that module
 * is being edited elsewhere; the binding itself is one line and this keeps the
 * two changes from colliding.
 *
 * `UbuntuBootstrap` is provided by [TerminalModule], also in `:app`, so the
 * graph resolves as a whole at the application level even though this binding
 * names a `:data` type and a `:terminal` one.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EditorModule {

    @Binds
    @Singleton
    abstract fun bindGuestFileRepository(
        impl: RootfsGuestFileRepository,
    ): GuestFileRepository
}