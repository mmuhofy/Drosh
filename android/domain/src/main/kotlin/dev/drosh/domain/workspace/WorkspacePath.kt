package dev.drosh.domain.workspace

/**
 * Guest-path normalisation for [Workspace.rootPath].
 *
 * This exists because the path is typed by hand on a phone keyboard, and because
 * it is then compared and displayed everywhere. `myapp`, `/myapp`, `/myapp/`,
 * `//myapp//` and `/home/x/./myapp` are one directory, and storing them as five
 * different strings would produce five workspaces that all look the same and
 * none of them match a directory the user meant.
 *
 * Pure and dependency-free so the rule can be tested directly rather than
 * inferred from a screen.
 */
object WorkspacePath {

    /**
     * The guest home directory, which is where `ProotRunner` sets HOME.
     *
     * The default when the field is left blank: a project with no directory is
     * still a valid group, and inventing one would be worse than saying nothing.
     */
    const val DEFAULT_ROOT: String = "/home"

    /**
     * Canonical form of [raw]: absolute, no duplicate separators, no `.`
     * segments, no trailing separator except for the root itself.
     *
     * `..` is **kept**. Resolving it needs the filesystem, and a workspace path
     * is not required to exist — so a path that walks above its own start is
     * stored as written and left for the shell to resolve when the user actually
     * enters it. Silently dropping the segment instead would change what the
     * label says the directory is.
     */
    fun normalise(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return DEFAULT_ROOT

        val segments = trimmed.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return "/"
        return "/" + segments.joinToString("/")
    }
}