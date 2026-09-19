package dev.drosh.agent.di

import dev.drosh.agent.provider.OpenAiSseAdapter
import dev.drosh.agent.provider.ProviderAdapter
import dev.drosh.agent.runtime.ToolRegistry
import dev.drosh.agent.tool.ShellTool
import dev.drosh.domain.agent.Tool
import dev.drosh.terminal.TerminalManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun provideShellTool(terminalManager: TerminalManager): ShellTool = ShellTool(terminalManager)

    @Provides
    @Singleton
    fun provideToolRegistry(shellTool: ShellTool): ToolRegistry =
        ToolRegistry(setOf<Tool>(shellTool))

    @Provides
    @Singleton
    fun provideProviderAdapter(): ProviderAdapter = OpenAiSseAdapter()
}