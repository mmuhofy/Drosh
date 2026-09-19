package dev.drosh.agent.di

import dev.drosh.agent.provider.OpenAiSseAdapter
import dev.drosh.agent.provider.ProviderAdapter
import dev.drosh.agent.tool.ShellTool
import dev.drosh.domain.agent.Tool
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.drosh.terminal.TerminalManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ToolModule {

    @Provides
    @IntoSet
    fun provideShellTool(terminalManager: TerminalManager): Tool = ShellTool(terminalManager)
}

@Module
@InstallIn(SingletonComponent::class)
object AdapterModule {

    @Provides
    @Singleton
    fun provideProviderAdapter(): ProviderAdapter = OpenAiSseAdapter()
}