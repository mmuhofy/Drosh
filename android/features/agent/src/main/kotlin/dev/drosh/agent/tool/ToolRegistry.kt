package dev.drosh.agent.tool

import dev.drosh.domain.agent.Tool
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The tools available to a run.
 *
 * ## Why the set is injected rather than listed
 *
 * The set arrives through Hilt multibinding, so adding a tool in the next phase
 * is one `@Provides` in `AgentModule` and nothing else changes — no registry
 * edit, no loop edit, and no risk of forgetting one of the two.
 *
 * ## Ordering is stable
 *
 * Tools are sorted by name. The declaration order this set arrives in depends on
 * Hilt's binding graph, and the ordering decides which schema the model sees
 * first. An unstable order makes prompts differ between runs for no reason, and
 * makes a diff in model behaviour impossible to attribute.
 */
@Singleton
class ToolRegistry @Inject constructor(
    tools: Set<@JvmSuppressWildcards Tool>,
) {

    private val byName: Map<String, Tool> = tools
        .sortedBy { it.name }
        .associateBy { it.name }

    /** Every registered tool, name-ordered. */
    fun all(): List<Tool> = byName.values.toList()

    fun find(name: String): Tool? = byName[name]

    /**
     * Names the model may call.
     *
     * Sent to the UI in a parse failure so the message can list what *was*
     * available — a model that invented a tool name usually guessed it from the
     * conversation, and seeing the real list is what lets it correct itself.
     */
    fun names(): List<String> = byName.keys.sorted()

    val isEmpty: Boolean get() = byName.isEmpty()
}
