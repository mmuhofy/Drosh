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
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ToolModule {

    @Binds
    @IntoSet
    abstract fun bindShellTool(tool: ShellTool): Tool
}

@Module
@InstallIn(SingletonComponent::class)
object AdapterModule {

    @Provides
    @Singleton
    fun provideProviderAdapter(): ProviderAdapter = OpenAiSseAdapter()
}