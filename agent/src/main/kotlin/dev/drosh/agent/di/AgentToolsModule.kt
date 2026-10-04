package dev.drosh.agent.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dev.drosh.agent.tool.GuestPaths
import dev.drosh.agent.tool.impl.AskUserTool
import dev.drosh.agent.tool.impl.ReadFileTool
import dev.drosh.agent.tool.impl.ShellTool
import dev.drosh.agent.tool.impl.UpdateTodoTool
import dev.drosh.agent.tool.impl.WebSearchTool
import dev.drosh.agent.tool.impl.WriteFileTool
import dev.drosh.domain.agent.Tool
import dev.drosh.terminal.UbuntuBootstrap
import javax.inject.Singleton

/**
 * The tools a run may call.
 *
 * A multibinding rather than a list, so adding a tool is one `@Provides` here and
 * nothing else: the registry, the loop and the model all read from this set, and
 * there is no second place to forget to update.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AgentToolsModule {

    @Binds
    @IntoSet
    abstract fun bindShellTool(impl: ShellTool): Tool

    @Binds
    @IntoSet
    abstract fun bindReadFileTool(impl: ReadFileTool): Tool

    @Binds
    @IntoSet
    abstract fun bindWriteFileTool(impl: WriteFileTool): Tool

    @Binds
    @IntoSet
    abstract fun bindAskUserTool(impl: AskUserTool): Tool

    @Binds
    @IntoSet
    abstract fun bindUpdateTodoTool(impl: UpdateTodoTool): Tool

    @Binds
    @IntoSet
    abstract fun bindWebSearchTool(impl: WebSearchTool): Tool

    companion object {

        /**
         * The default working directory for a file tool.
         *
         * A tool's `execute` receives its working directory per call, but
         * [GuestPaths] is constructed once. Resolving it here means the tools see
         * the guest home rather than the host's working directory, which is
         * usually somewhere else entirely and would make every relative path in a
         * prompt wrong.
         *
         * Per-call scoping arrives with the agent session's own terminal; until
         * then every chat is scoped to the guest home and the model is told the
         * working directory in the system prompt.
         */
        @Provides
        @Singleton
        fun provideGuestPaths(ubuntuBootstrap: UbuntuBootstrap): GuestPaths =
            GuestPaths(ubuntuBootstrap.rootfsDir, DEFAULT_WORKING_DIRECTORY)

        /** The guest home, as `ProotRunner` sets HOME. */
        private const val DEFAULT_WORKING_DIRECTORY = "/home"
    }
}
