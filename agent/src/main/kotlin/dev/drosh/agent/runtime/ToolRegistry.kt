package dev.drosh.agent.runtime

import dev.drosh.domain.agent.Tool
import dev.drosh.domain.agent.ToolDef
import dev.drosh.domain.agent.ToolResult
import dev.drosh.domain.agent.WorkMode
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ToolRegistry @Inject constructor(
    private val tools: Set<@ToolKey Tool>
) {
    private val toolMap: Map<String, Tool> = tools.associateBy { it.name }

    fun getToolDeclarations(mode: WorkMode): List<ToolDef> {
        return toolMap.values
            .filter { isAllowed(it.name, mode) }
            .map { it.toLlmToolDef() }
    }

    suspend fun execute(
        toolName: String,
        args: Map<String, Any>,
        mode: WorkMode
    ): ToolResult {
        if (!isAllowed(toolName, mode)) {
            return ToolResult.Cancelled(
                reason = "Tool '$toolName' is disabled in ${mode.name} mode"
            )
        }

        val tool = toolMap[toolName]
            ?: return ToolResult.Error("Unknown tool: $toolName")

        return tool.execute(args)
    }

    fun getTool(name: String): Tool? = toolMap[name]

    private fun isAllowed(toolName: String, mode: WorkMode): Boolean = when (mode) {
        WorkMode.PLAN -> toolName in setOf("read_file", "shell", "ask_user")
        WorkMode.BUILD, WorkMode.AUTO -> true
    }
}

@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FIELD,
    AnnotationTarget.VALUE_PARAMETER,
    AnnotationTarget.TYPE,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.FUNCTION
)
annotation class ToolKey