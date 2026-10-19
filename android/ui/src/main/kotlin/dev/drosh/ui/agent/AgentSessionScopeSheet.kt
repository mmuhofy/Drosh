package dev.drosh.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import dev.drosh.ui.settings.SettingsSwitch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
/**
 * One tool the agent can be allowed to run without asking.
 *
 * ## Why the wire name and not the class
 *
 * `:ui` cannot see `:agent` — the dependency arrows in AGENT.md §139 do not
 * allow it — so a sheet that lists what the agent may do cannot enumerate
 * `ToolRegistry`. The wire name is the one thing both sides already agree on:
 * it is what the model sends, what the transcript row shows, and what a
 * permission has to key off when the domain type that holds it lands. The
 * labels live here, next to the names, because a switch that says
 * `update_todo` is a switch for nobody.
 *
 * ## Why each carries a sentence
 *
 * A permission the user cannot read is a permission the user cannot revoke.
 * The row says what turning it on *does* rather than what the tool is called,
 * because the name is an implementation detail and the consequence is not.
 */
enum class SessionTool(
    /** The name the model calls, and what `Tool.NAME` returns. */
    val wireName: String,
    private val labelRes: Int,
    private val descRes: Int,
) {
    Shell("shell", R.string.agent_scope_perm_shell, R.string.agent_scope_perm_shell_desc),
    Todo("update_todo", R.string.agent_scope_perm_todo, R.string.agent_scope_perm_todo_desc),
    Write("write_file", R.string.agent_scope_perm_write, R.string.agent_scope_perm_write_desc),
    Read("read_file", R.string.agent_scope_perm_read, R.string.agent_scope_perm_read_desc),
    Move("move_file", R.string.agent_scope_perm_move, R.string.agent_scope_perm_move_desc),
    Grep("grep", R.string.agent_scope_perm_grep, R.string.agent_scope_perm_grep_desc),
    WebSearch("web_search", R.string.agent_scope_perm_web, R.string.agent_scope_perm_web_desc),
    Ask("ask_user", R.string.agent_scope_perm_ask, R.string.agent_scope_perm_ask_desc),
    ;

    val label: String
        @Composable get() = stringResource(labelRes)

    val description: String
        @Composable get() = stringResource(descRes)

    /** The row's glyph, from the shared set rather than drawn here. */
    val glyph: ImageVector
        @Composable get() = when (this) {
            SessionTool.Shell -> DroshIcons.SquareTerminal
            SessionTool.Todo -> DroshIcons.ListChecks
            SessionTool.Write -> DroshIcons.Pencil
            SessionTool.Read -> DroshIcons.File
            SessionTool.Move -> DroshIcons.ArrowRight
            SessionTool.Grep -> DroshIcons.Search
            SessionTool.WebSearch -> DroshIcons.Globe
            SessionTool.Ask -> DroshIcons.Info
        }

    companion object {

        /** Every tool, in the order the sheet lists them. */
        val all: List<SessionTool> = entries

        /** Every wire name, for a caller that only has the strings. */
        val wireNames: Set<String> = entries.mapTo(mutableSetOf()) { it.wireName }

        /** The tool behind [name], or null when it is not in the list. */
        fun of(name: String): SessionTool? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * One project this session can be pointed at.
 *
 * A path and a name rather than a session: the working directory is where the
 * agent's file tools resolve relative paths, and a project is what a user
 * calls the thing that lives there.
 */
data class AgentWorkdirChoice(
    val id: String,
    val name: String,
    /** Absolute path inside the guest filesystem. */
    val path: String,
)

/**
 * What this one session is allowed to do.
 *
 * ## Why it is its own value and not a field on the settings
 *
 * The app-wide trust level says how much the agent may do before it asks. This
 * says what a *conversation* may do, which is a different question: a chat
 * about one file can run with almost nothing enabled, and a chat that is
 * driving a build cannot. Both live on the same screen, so they cannot be one
 * number.
 *
 * ## Why the tools are a set of wire names
 *
 * The set is compared against what the loop actually registered, so a tool
 * that is not compiled into this build is simply absent rather than a switch
 * that turns on nothing. A map keyed by `SessionTool` would have to name all
 * eight, and the day a ninth lands the map would be built without it.
 */
data class AgentSessionScopeState(
    /** Absolute path this session works in; "" is the app default. */
    val workdir: String = "",
    /** Wire names of the tools that may run without asking. */
    val allowedTools: Set<String> = SessionTool.wireNames,
    /** The projects the working directory can be set to. */
    val projects: List<AgentWorkdirChoice> = emptyList(),
) {
    companion object {

        /** Everything on, which is what the trust level already decides. */
        val DEFAULT = AgentSessionScopeState()

        /**
         * Whether [tool] may run without asking.
         *
         * The empty set reads as "all of them": a permission list that has
         * never been through this sheet has never been scoped, and a chat that
         * has not been scoped is a chat the app-wide level still governs.
         */
        fun isAllowed(state: AgentSessionScopeState, tool: SessionTool): Boolean =
            tool.wireName in state.allowedTools

        /** The project behind [path], or null when it is the default. */
        fun projectOf(state: AgentSessionScopeState, path: String): AgentWorkdirChoice? =
            state.projects.firstOrNull { it.path == path }
    }
}

/**
 * Where the session scope lives.
 *
 * ## Why an interface and not a ViewModel parameter
 *
 * The domain type that holds a per-chat working directory and a per-chat
 * permission set does not exist yet — another agent is writing it. Declaring
 * the interface here means this sheet compiles and runs today against
 * [InMemoryAgentSessionScopeStore], and the day the store lands the caller
 * swaps one constructor for another without this file changing at all.
 *
 * ## Why a StateFlow
 *
 * The sheet recomposes on every switch, and the value has to be what is in
 * effect right now — including a change made a moment ago from somewhere else.
 * A snapshot passed as a parameter is a number the user can be wrong about.
 */
interface AgentSessionScopeStore {

    /** The scope in effect, and the projects it can be pointed at. */
    val state: StateFlow<AgentSessionScopeState>

    /** Point this session at [path]; "" is the app default. */
    fun setWorkdir(path: String)

    /** Allow or refuse [tool] for this session alone. */
    fun setToolAllowed(tool: SessionTool, allowed: Boolean)
}

/**
 * The store, until the domain has one.
 *
 * Held in a `remember`, so it lives exactly as long as the sheet editing it: a
 * scope that outlived the sheet would be a preference nobody asked to persist,
 * and one that died with the sheet would be a preference nobody can rely on.
 * Both are worse than the honest middle — this is the wiring placeholder, not
 * the storage.
 */
class InMemoryAgentSessionScopeStore(
    initial: AgentSessionScopeState = AgentSessionScopeState.DEFAULT,
) : AgentSessionScopeStore {

    private val flow = MutableStateFlow(initial)

    override val state: StateFlow<AgentSessionScopeState> = flow.asStateFlow()

    override fun setWorkdir(path: String) {
        flow.value = flow.value.copy(workdir = path)
    }

    override fun setToolAllowed(tool: SessionTool, allowed: Boolean) {
        val current = flow.value.allowedTools
        val next = if (allowed) current + tool.wireName else current - tool.wireName
        flow.value = flow.value.copy(allowedTools = next)
    }
}

/**
 * This session's working directory and what its tools may do.
 *
 * ## Why both live in one sheet
 *
 * They answer the same question from two directions. A working directory says
 * *where* the agent works and a permission says *what* it may do there, and
 * neither means much without the other: a chat scoped to one project with
 * every tool enabled is a chat that can write anywhere in it, and a chat with
 * every tool off in the right directory is a chat that cannot act at all.
 * Splitting them would be two trips through a drawer to describe one setup.
 *
 * ## Why it is not on the project list screen
 *
 * A path chosen from the project list is a path chosen once, globally, for
 * every session that happens to open next. This is a per-chat choice, made
 * from inside the chat it applies to, and it is offered nowhere else: two ways
 * to set one value is two values to fall out of step.
 *
 * ## What "off" means
 *
 * Refused rather than asked for nothing. A tool that is off is one the agent
 * cannot call here at all, so it plans without it instead of trying it and
 * being turned down — which is the difference between a model that works
 * around a limit and one that spends a turn discovering it.
 */
@Composable
fun AgentSessionScopeSheet(
    store: AgentSessionScopeStore,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by store.state.collectAsState()

    AgentSheet(
        modifier = modifier,
        title = stringResource(R.string.agent_scope_title),
        subtitle = stringResource(R.string.agent_scope_permissions_desc),
        icon = DroshIcons.ShieldCheck,
        heightFraction = 0.8f,
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SheetSectionLabel(stringResource(R.string.agent_scope_workdir))
            SheetCaption(stringResource(R.string.agent_scope_workdir_desc))

            val project = AgentSessionScopeState.projectOf(state, state.workdir)
            SheetOptionRow(
                title = project?.name ?: stringResource(R.string.agent_scope_workdir_default),
                subtitle = project?.path ?: stringResource(R.string.agent_scope_workdir_desc),
                icon = DroshIcons.Folder,
                monospaceSubtitle = true,
                selected = state.workdir.isBlank(),
                onClick = { store.setWorkdir("") },
            )

            if (state.projects.isEmpty()) {
                SheetCaption(stringResource(R.string.agent_scope_projects_empty))
            } else {
                state.projects.forEach { choice ->
                    SheetOptionRow(
                        title = choice.name,
                        subtitle = choice.path,
                        icon = DroshIcons.Folder,
                        monospaceSubtitle = true,
                        selected = choice.path == state.workdir,
                        onClick = { store.setWorkdir(choice.path) },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            SheetSectionLabel(stringResource(R.string.agent_scope_permissions))

            SessionTool.all.forEach { tool ->
                val allowed = AgentSessionScopeState.isAllowed(state, tool)
                SheetOptionRow(
                    title = tool.label,
                    subtitle = tool.description,
                    icon = tool.glyph,
                    selected = allowed,
                    // The switch says what is on, so the tick would be the
                    // same fact twice in one row.
                    showTick = false,
                    onClick = { store.setToolAllowed(tool, !allowed) },
                    trailing = {
                        SettingsSwitch(
                            checked = allowed,
                            onCheckedChange = { on -> store.setToolAllowed(tool, on) },
                        )
                    },
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}
