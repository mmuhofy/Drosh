package dev.drosh.ui.session

/**
 * Program → brand icon.
 *
 * A session's glyph is chosen from the program the kernel says is running in
 * the foreground — `/proc/<pgid>/comm`, the same source the process sheet reads
 * — not from a title the program may never set. `OSC 1` is honoured first when
 * a program sets it, because that is exactly what the sequence means, but a
 * shell that sets nothing still gets the right mark: `python3` is Python,
 * `nvim` is NeoVim and `claude` is Claude, whatever their titles say.
 *
 * The table is the runtime mirror of [tools/icons/icons.txt]; the script that
 * fetches the drawables reads the same names, so a program added there has its
 * picture waiting here.
 *
 * Matching is by whole program name, never by substring: `vi` inside `virtual`
 * is not a request for the editor's mark. Names arrive from a kernel field that
 * truncates at fifteen characters, so `python3.12` and `node` are both already
 * the process's own name; the normalization only strips what a shell or a path
 * adds around it.
 */
object ProgramGlyphs {

    /** Every known program, lowercased, mapped to its Simple Icons slug. */
    private val aliases: Map<String, String> = mapOf(
        "python" to "python",
        "python3" to "python",
        "pip" to "python",
        "pip3" to "python",
        "ipython" to "python",
        "node" to "nodedotjs",
        "nodejs" to "nodedotjs",
        "npm" to "npm",
        "pnpm" to "pnpm",
        "yarn" to "yarn",
        "deno" to "deno",
        "bun" to "bun",
        "vim" to "vim",
        "nvim" to "neovim",
        "htop" to "htop",
        "top" to "htop",
        "btop" to "htop",
        "bash" to "gnubash",
        "sh" to "gnubash",
        "zsh" to "zsh",
        "fish" to "gnubash",
        "git" to "git",
        "gh" to "github",
        "tmux" to "tmux",
        "docker" to "docker",
        "podman" to "podman",
        "kubectl" to "kubernetes",
        "helm" to "helm",
        "cargo" to "rust",
        "rustc" to "rust",
        "rustup" to "rust",
        "go" to "go",
        "ruby" to "ruby",
        "irb" to "ruby",
        "php" to "php",
        "lua" to "lua",
        "perl" to "perl",
        "java" to "openjdk",
        "javac" to "openjdk",
        "claude" to "claude",
        "claudecode" to "claudecode",
        "gemini" to "googlegemini",
        "ollama" to "ollama",
        "debian" to "debian",
        "ubuntu" to "ubuntu",
        "arch" to "archlinux",
        "fedora" to "fedora",
    )

    /**
     * The slug for a program name, or null when it is not one we know.
     *
     * Accepts the shapes `/proc` and a shell produce: a full path
     * (`/usr/bin/python3`), a login shell's leading dash (`-bash`), a version
     * suffix (`python3.12`, `nvim-0.9`) and an `.exe` tail.
     */
    fun slugFor(program: String?): String? {
        val name = normalize(program) ?: return null
        return aliases[name]
            // `python3`, `python3.12` and `rustc-1.70` all reduce to the
            // program: the digits after the name are a version, not a
            // different program.
            ?: aliases[name.trimEnd { it.isDigit() || it == '.' || it == '-' || it == '_' }]
    }

    /**
     * The slug a card should wear: the program first, the title second.
     *
     * The title is prose — `vim` puts the file name in it, `ssh` a
     * `user@host` — so every word is tried, not just the first. Nothing
     * matches means no slug, and the card falls back to its own glyph set
     * rather than inventing a brand.
     */
    fun resolve(program: String?, title: String?): String? {
        slugFor(program)?.let { return it }
        if (title.isNullOrBlank()) return null
        return title
            .lowercase()
            .split(NON_WORD)
            .firstNotNullOfOrNull { slugFor(it) }
    }

    /** The program's own name, without the path or the decoration around it. */
    private fun normalize(program: String?): String? {
        if (program.isNullOrBlank()) return null
        val last = program.trim().substringAfterLast('/').substringAfterLast('\\')
        val bare = last.removePrefix("-").removeSuffix(".exe")
        return bare.lowercase().takeIf { it.isNotEmpty() }
    }

    /** What separates the words of a title: whitespace and punctuation. */
    private val NON_WORD = Regex("[^a-z0-9+]+")
}
