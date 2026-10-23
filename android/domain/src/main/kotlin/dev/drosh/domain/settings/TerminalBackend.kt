package dev.drosh.domain.settings

/**
 * Which terminal implementation the app runs.
 *
 * `termux` is the vendored Termux emulator the app has always used: a
 * software-drawn `View` on a Java PTY, with the session owned by the app's
 * terminal manager.
 *
 * `engine` is the Rust engine: an OpenGL surface on the engine's own PTY,
 * with the session owned by the view that draws it.
 *
 * The choice is a setting and not a build flag because the two terminals are
 * not interchangeable in every respect — the engine has no scrollback of its
 * own to search, and the app's block engine still follows the vendored
 * sessions — and because the engine has not been through a device yet. The
 * app has to keep working on the path it has, and the user has to be able to
 * go back to it without a rebuild.
 */
enum class TerminalBackend {

    /** The vendored Termux emulator: the path the app has always used. */
    TERMUX,

    /** The Rust engine: the path the app is migrating to. */
    ENGINE,
    ;

    /** Lower-case in the file, so a hand-edit reads like prose. */
    fun tomlName(): String = name.lowercase()

    companion object {
        /** The backend used when nothing was chosen. */
        val DEFAULT = ENGINE

        /**
         * The backend of [name], or null when the name is none.
         *
         * Case-insensitive, like every other enum in the file: an older
         * build's PascalCase value still loads.
         */
        fun fromName(name: String?): TerminalBackend? =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) }
    }
}
