package dev.drosh.domain.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The theme store: what is installed, what the store lists, and the moves
 * between them.
 *
 * Themes live as files inside the Linux environment — `<rootfs>/.drosh/themes/`
 * — because a theme is a small user-authored document and that directory is
 * where the user's documents are. The app part is the store: an index, a
 * download, an install, and a save.
 */
interface ThemeRepository {

    /** Themes on disk, including the built-in. Id-ordered. */
    val installed: StateFlow<List<DroshTheme>>

    /**
     * The store's index. [force] re-fetches; otherwise a cached copy younger
     * than a day is answered from disk. A failure answers the cached copy
     * when there is one and an error when there is not.
     */
    suspend fun index(force: Boolean = false): Result<ThemeIndex>

    /** Downloads [entry]'s theme file and installs it. */
    suspend fun install(entry: ThemeIndexEntry): Result<DroshTheme>

    /**
     * Installs a theme from a URL — its own repo, a gist, anywhere the file
     * is served. The id comes from the file's metadata, so a URL of a theme
     * that is already installed updates it rather than duplicating it.
     */
    suspend fun installFromUrl(url: String): Result<DroshTheme>

    /** Writes [theme] as the user's own, overwriting an id that is already theirs. */
    suspend fun saveCustom(theme: DroshTheme): Result<DroshTheme>

    /** Removes an installed theme. The active one is refused by the caller. */
    suspend fun delete(id: String): Result<Unit>

    /** One installed theme, or null. */
    fun theme(id: String): DroshTheme?
}
