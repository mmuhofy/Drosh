package dev.drosh.domain.workspace

import kotlinx.coroutines.flow.Flow

/**
 * A workspace: a named group of sessions with a guest directory attached.
 *
 * ## What a workspace is, and is not
 *
 * A workspace is **grouping plus metadata**. That is the whole of it in v1.0:
 *
 * - a name
 * - the guest directory the project lives in ([rootPath])
 * - an optional description
 * - a colour seed, so the project is recognisable at a glance in a list
 * - timestamps
 * - the sessions the user filed under it
 *
 * What it deliberately is **not** is a container for state. It does not own a
 * PTY, it does not keep a shell alive, it does not reopen anything. Two
 * consequences follow, and they are the design rather than a limitation:
 *
 * - **Sessions and processes are not persistent through a workspace.** The
 *   workspace row survives a process death; the shell behind a session does
 *   not, exactly as it does not for an ungrouped session. Grouping says
 *   "these three sessions are about the same project", never "these three
 *   shells will be waiting for you".
 * - **A workspace's directory is a label, not a working directory.** It is
 *   what the user typed and what the list shows; it is never `cd`-ed into on
 *   their behalf. A session's shell starts wherever the session's own startup
 *   says it starts, and opening a workspace changes nothing about that.
 *
 * [MEMORYBANK.md](https://github.com/mmuhofy/Drosh/blob/main/docs/MEMORYBANK.md)
 * §9 sketches project-scoped shortcuts and workflows on top of this. Both are
 * deferred; nothing here anticipates their schema.
 */
data class Workspace(
    val id: String,
    val name: String,
    /** Absolute path inside the guest filesystem, normalised on write. */
    val rootPath: String,
    /** Free text. Empty is normal and is not shown. */
    val description: String,
    /** Index into a fixed palette the UI owns; clamped on write. */
    val colorSeed: Int,
    val createdAtMs: Long,
    val lastOpenedAtMs: Long,
    /**
     * Archived workspaces are kept but hidden from the list. Their sessions are
     * not deleted and not reassigned — they simply stop being grouped, because
     * from the user's point of view the group is gone.
     */
    val archived: Boolean,
)

/**
 * The form a workspace is created and edited in.
 *
 * A dedicated type rather than a bag of nullable parameters on `update`: null
 * would have to mean both "leave this alone" and "clear this", and an empty
 * description is a real value the user chooses.
 */
data class WorkspaceEdit(
    val name: String = "",
    val rootPath: String = WorkspacePath.DEFAULT_ROOT,
    val description: String = "",
    val colorSeed: Int = 0,
)

/** How many colour seeds a workspace may carry. The palette lives in the UI. */
const val WORKSPACE_COLOR_SEED_COUNT: Int = 6

/**
 * The edit as it should reach the database: name trimmed, path normalised, seed
 * clamped.
 *
 * Both the create and the update path run this, so a workspace cannot end up
 * holding a path or a colour the rest of the app has to defend against. The name
 * is only trimmed and not rejected — an empty name is a UI-level rule, because
 * it is the UI that can tell the user, and silently storing a nameless
 * workspace instead would hide the mistake.
 */
fun WorkspaceEdit.forStorage(): WorkspaceEdit = copy(
    name = name.trim(),
    rootPath = WorkspacePath.normalise(rootPath),
    colorSeed = colorSeed.coerceIn(0, WORKSPACE_COLOR_SEED_COUNT - 1),
)

interface WorkspaceRepository {

    /** Active workspaces, most recently opened first. Archived ones excluded. */
    fun observeAll(): Flow<List<Workspace>>

    /** Includes archived workspaces, for the archive list. */
    fun observeArchived(): Flow<List<Workspace>>

    fun observe(id: String): Flow<Workspace?>

    suspend fun get(id: String): Workspace?

    /**
     * @param edit name must be non-blank; [WorkspaceEdit.rootPath] is normalised
     *        and [WorkspaceEdit.colorSeed] clamped before the row is written
     */
    suspend fun create(edit: WorkspaceEdit): Workspace

    suspend fun update(id: String, edit: WorkspaceEdit)

    suspend fun setArchived(id: String, archived: Boolean)

    /**
     * Delete the workspace row. Its sessions are ungrouped first.
     *
     * Sessions are never deleted with a workspace. A grouping label is cheap to
     * lose and a session's history is not, and a row that pointed at a workspace
     * which no longer exists would be a dangling reference the UI would have to
     * defend against.
     */
    suspend fun delete(id: String)

    /** Bump `lastOpenedAtMs`. Called when the workspace screen is opened. */
    suspend fun touch(id: String)
}