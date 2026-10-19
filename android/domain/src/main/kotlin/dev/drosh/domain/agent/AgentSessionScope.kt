package dev.drosh.domain.agent

/**
 * What one agent session may reach, and from where.
 *
 * ## Two things, one value
 *
 * A run is scoped twice over: by the directory it works in, and by the tools it
 * may call. They travel together because they answer the same question from the
 * user's side — "what is this session allowed to do" — and because a sheet that
 * edits one of them is the same sheet that edits the other.
 *
 * ## The keys come from the tools, not from a list
 *
 * [ToolPermissions.schema] derives its entries from the [Tool] set a run is
 * actually wired with, so a tool added next month appears in the permission
 * surface with no edit to this file. A hardcoded list here would be a list that
 * goes stale the moment someone adds a tool, and a permission nobody can switch
 * off is not a permission — it is a surprise waiting for the first read of the
 * tool list.
 *
 * ## Everything is on until it is turned off
 *
 * [revoked] starts empty. A session that has never been edited may use every
 * tool it has, including the ones that write; the approval layer
 * ([Tool.requiresApproval], [AutonomyLevel]) is what stands between a writing
 * tool and the filesystem, and duplicating it as a default-off switch would
 * make a fresh session useless for the sake of looking careful.
 */
data class AgentSessionScope(
    /** Absolute path inside the guest filesystem the session works in. */
    val workingDirectory: String,
    /** Keys turned off for this session. Empty by default. */
    val revoked: Set<String> = emptySet(),
) {

    /** Whether [key] may be called. Unknown keys are allowed — see [ToolPermissions]. */
    fun isGranted(key: String): Boolean = key !in revoked

    /** This scope with [key] switched on. */
    fun grant(key: String): AgentSessionScope = copy(revoked = revoked - key)

    /** This scope with [key] switched off. */
    fun revoke(key: String): AgentSessionScope = copy(revoked = revoked + key)

    /** Flip one key. What a single switch in a sheet does. */
    fun toggle(key: String): AgentSessionScope =
        if (isGranted(key)) revoke(key) else grant(key)

    /** Move the session to another working directory. */
    fun moveTo(path: String): AgentSessionScope = copy(workingDirectory = path)

    companion object {

        /**
         * The guest home.
         *
         * * `ProotRunner` sets HOME=/home, and a user's first `cd` lands there.
         * * The agent's file tools default to it when a call carries no directory.
         *
         * Duplicated rather than referenced across module boundaries: `:ui` reads
         * `AgentChatRepositoryImpl.DEFAULT_DIRECTORY` with its own literal, and two
         * literals that describe the same directory are cheaper than a dependency
         * from the domain layer onto the data layer to describe it once.
         */
        const val DEFAULT_WORKING_DIRECTORY: String = "/home"

        /** A scope at the guest home, with nothing revoked. */
        val DEFAULT: AgentSessionScope = AgentSessionScope(DEFAULT_WORKING_DIRECTORY)
    }
}

/**
 * One switch in the permission surface.
 *
 * @param key the wire name of the tool it governs, e.g. `write_file`. Stable,
 *        and the only part of this a caller should persist.
 * @param requiresApproval copied from [Tool.requiresApproval], so a sheet can
 *        say "this one asks first" without reaching into the tool list itself.
 * @param granted whether the session may call it right now
 * @param description the tool's own one-liner, sent to the model verbatim. Model
 *        prose, not UI copy: a sheet that wants a label should resolve one from
 *        string resources keyed by [key], which is also what lets a translation
 *        name a tool the model never mentions.
 */
data class ToolPermission(
    val key: String,
    val requiresApproval: Boolean,
    val granted: Boolean,
    val description: String = "",
)

/**
 * Builds the permission schema for the tools that exist.
 *
 * Deliberately not a list of names. The registry that holds the tools is in
 * `:agent`, which `:domain` and `:ui` cannot see, so the schema is derived from
 * the [Tool] values themselves: `Tool` is a domain interface, so a multibinding
 * set of them reaches a ViewModel without pulling the implementations along.
 */
object ToolPermissions {

    /**
     * One entry per tool, name-ordered.
     *
     * Ordering matters for a sheet: the list is read as a list, and an order
     * that changes between runs makes the surface feel broken in a way nobody
     * can report. Sorted by [Tool.name] for the same reason [dev.drosh.agent.tool.ToolRegistry]
     * sorts by it — the declaration order of a multibinding set is Hilt's, not
     * anyone's, and it is not stable across launches.
     */
    fun schema(tools: List<Tool>, scope: AgentSessionScope): List<ToolPermission> =
        tools
            .sortedBy { it.name }
            .map { tool ->
                ToolPermission(
                    key = tool.name,
                    requiresApproval = tool.requiresApproval,
                    granted = scope.isGranted(tool.name),
                    description = tool.description,
                )
            }

    /**
     * A scope over [tools] that has nothing revoked.
     *
     * The shape a fresh session starts in: every tool in the registry is
     * available, and the approval layer decides which of them asks first.
     */
    fun openScope(tools: List<Tool>, workingDirectory: String): AgentSessionScope =
        AgentSessionScope(workingDirectory = workingDirectory)

    /**
     * Drop revoked keys that no longer exist.
     *
     * A session whose scope was scoped to a tool that has since been taken out
     * would carry that key forever, and a scope that only ever grows is a scope
     * nobody can reason about. Called when the tool set is re-read, so the
     * revocation list tracks the tools rather than the history of the build.
     */
    fun prune(scope: AgentSessionScope, tools: List<Tool>): AgentSessionScope {
        if (scope.revoked.isEmpty()) return scope
        val live = tools.mapTo(HashSet()) { it.name }
        return scope.copy(revoked = scope.revoked.filterTo(mutableSetOf()) { it in live })
    }
}
