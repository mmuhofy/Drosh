package dev.drosh.data.backup.termux

/**
 * Termux package names → Ubuntu package names.
 *
 * The migration carries a Termux user's *package list*, not their binaries:
 * a bionic-linked `$PREFIX` tree cannot run under the glibc userspace Drosh
 * boots under PRoot, so every package is reinstalled from the Ubuntu 24.04
 * archive by name. Most names are already the same — both worlds descend
 * from Debian — so [OVERRIDES] only records the divergences that a naive
 * copy would get wrong, plus the Termux-only packages that have no Ubuntu
 * counterpart at all.
 *
 * Nothing is dropped silently. A name that cannot be mapped is never
 * quietly skipped: it lands in [PackageClassification.unmapped] (so the
 * caller can write it to the leftovers file the user can still act on) or
 * in [PackageClassification.dropped] (a deliberate decision — see the
 * per-entry comments). A name absent from [OVERRIDES] and not Termux-owned
 * is **identity-mapped**, on the reasoning that a package called `git` in
 * Termux is the `git` apt knows.
 */
object TermuxPackageMap {

    /**
     * Sentinel for names that are Termux-only or cannot map.
     *
     * The NUL prefix makes it un-collidable with any real package name, so a
     * mistyped [OVERRIDES] entry cannot silently become "install a package
     * called \u0000drop". [toUbuntu] translates it back to `null`.
     */
    const val DROP = "\u0000drop"

    /**
     * Termux name → Ubuntu name. Only the KNOWN DIVERGENCES; identity is the
     * default rule.
     *
     * Values are either a real Ubuntu package name or the [DROP] sentinel.
     * Many entries below are *identity* mappings — a package spelled the
     * same on both sides — and they are kept here rather than left implicit
     * because the divergent and the identical names have to be judged in one
     * place: a name that is unchanged but explicitly curated is a name a
     * reviewer can see was considered. Where a mapping is not obvious, the
     * comment beside it says why.
     */
    val OVERRIDES: Map<String, String> = mapOf(
        // ---- interpreters and toolchains ------------------------------------
        // Termux is a Python-3 world but its meta-package is `python`; the
        // same interpreter on Ubuntu is the `python3` meta-package.
        "python" to "python3",
        // Ubuntu 24.04 ships no Python 2 at all, so there is no name to
        // point at and no Ubuntu package that is "close enough" to fake
        // one. Kept identity so it travels as itself and the install step
        // fails loudly, instead of being quietly erased here.
        "python2" to "python2",
        // Rust on Termux is the `rust` package; Ubuntu's is `rustc` (the
        // compiler and std, which is what Termux ships too).
        "rust" to "rustc",
        "clang" to "clang",
        "golang" to "golang",
        "ruby" to "ruby",
        "perl" to "perl",
        "php" to "php",
        "gdb" to "gdb",
        // Ubuntu's Node.js is `nodejs`; Termux additionally tracks an LTS
        // build as `nodejs-lts`, which has no Ubuntu counterpart and
        // collapses into the one package.
        "nodejs" to "nodejs",
        "nodejs-lts" to "nodejs",

        // ---- network ---------------------------------------------------------
        // Termux's OpenSSH is client-only (`openssh`); Ubuntu splits client
        // and server, and only the client half is meaningful here — a guest
        // under PRoot has no init to run sshd from.
        "openssh" to "openssh-client",
        "openssl" to "openssl",

        // ---- core utilities — identity, curated because the divergent set
        // ---- is defined by whatever sits around them -----------------------
        "coreutils" to "coreutils",
        "util-linux" to "util-linux",
        // Deliberately DROPped rather than identity-mapped: Ubuntu's
        // coreutils already cover busybox's role, and a second set of
        // conflicting applets earlier in $PATH is a support problem.
        "busybox" to DROP,
        "dash" to "dash",
        // Ubuntu's man implementation is the `man-db` package; `man` alone
        // is not a package name there.
        "man" to "man-db",
        "grep" to "grep",
        "sed" to "sed",
        "gawk" to "gawk",
        "findutils" to "findutils",
        "tar" to "tar",
        "gzip" to "gzip",
        "bzip2" to "bzip2",
        "xz-utils" to "xz-utils",
        "zip" to "zip",
        "unzip" to "unzip",
        "rsync" to "rsync",
        "make" to "make",
        "cmake" to "cmake",
        // The package is `fd-find`; the `fd` name itself belongs to a
        // different Ubuntu package, so Termux's `fd` must not be copied
        // across as-is.
        "fd" to "fd-find",
        "jq" to "jq",
        "tree" to "tree",
        "htop" to "htop",
        "less" to "less",
        "bat" to "bat",
        "ed" to "ed",
        // valgrind needs ptrace, which does not work under PRoot: carrying
        // it across would install a binary that fails at runtime.
        "valgrind" to DROP,
        // Termux's runtime-support shim for Android; nothing on a glibc
        // userspace needs it.
        "libandroid-support" to DROP,

        // ---- editors, shells, multiplexers -----------------------------------
        "vim" to "vim",
        "neovim" to "neovim",
        "nano" to "nano",
        "emacs" to "emacs",
        "zsh" to "zsh",
        "fish" to "fish",
        "tmux" to "tmux",
        "git" to "git",
        "wget" to "wget",
        "curl" to "curl",
        "fzf" to "fzf",
        "ripgrep" to "ripgrep",
        "lazygit" to "lazygit",
        "neofetch" to "neofetch",

        // ---- servers and databases --------------------------------------------
        "postgresql" to "postgresql",
        // Termux's `mariadb` ships the server; on Ubuntu the server is
        // `mariadb-server`, because bare `mariadb` there is the
        // compatibility library set and installs nothing runnable.
        "mariadb" to "mariadb-server",
        "nginx" to "nginx",
        "apache2" to "apache2",
        // Termux names its SQLite CLI `sqlite`; Ubuntu's is `sqlite3`.
        "sqlite" to "sqlite3",
        "ffmpeg" to "ffmpeg",
        "imagemagick" to "imagemagick",

        // ---- Termux-only: no Ubuntu counterpart, dropped on purpose -----------
        // proot and proot-distro are the *transport*. The Ubuntu side already
        // provides them; the Android builds cannot run here at all.
        "proot" to DROP,
        "proot-distro" to DROP,
        // Termux glue: the tools package and the API bridge to the Termux
        // app itself.
        "termux-tools" to DROP,
        "termux-api" to DROP,
        // The command-not-found hook is per-distro by construction.
        "command-not-found" to DROP,
        // `tsu` is Termux's su-caller; the guest has no root to offer.
        "tsu" to DROP,
        // Repository pseudo-packages — sources.list entries, not software.
        // On Ubuntu 24.04 these are third-party repositories, so there is
        // no package to install under these names.
        "game-repo" to DROP,
        "science-repo" to DROP,
        "root-repo" to DROP,
        "x11-repo" to DROP,
    )

    /**
     * Termux-minted package names that are neither `termux-*` nor `*-repo`
     * yet still have no Ubuntu meaning.
     *
     * This is the escape hatch for the identity rule. The default says "the
     * same name means the same software", which is usually right, but a
     * package Termux created for the Android C library environment installs
     * nothing useful on Ubuntu. Everything here is *reported* to the user
     * (see [PackageClassification.unmapped]) rather than deleted — deciding
     * a user's package no longer matters is not this file's call.
     *
     * The `termux-*` prefix and the `*-repo` suffix are covered by
     * [isTermuxOwned]'s heuristic; this set is for the leftovers that match
     * neither. `pkg` is the apt wrapper Termux ships; the `libandroid-*`
     * family are bionic shims (glob, spawn, shared memory, semaphores —
     * functions Android's libc lacks) that glibc has natively.
     */
    val TERMUX_OWNED_PACKAGES: Set<String> = setOf(
        "pkg",
        "libandroid-glob",
        "libandroid-spawn",
        "libandroid-shmem",
        "libandroid-posix-semaphore",
        "libandroid-wordexp",
    )

    /**
     * Maps a Termux package name to the Ubuntu name to install.
     *
     * Returns `null` when the package is Termux-only or unmappable — the
     * caller's signal to write it to the leftovers file, *not* to skip it.
     * A name absent from [OVERRIDES] and not Termux-owned is returned
     * unchanged (identity).
     */
    fun toUbuntu(termuxName: String): String? {
        val override = OVERRIDES[termuxName]
        if (override != null) {
            return override.takeIf { it != DROP }
        }
        if (isTermuxOwned(termuxName)) return null
        return termuxName
    }

    /**
     * Sorts a Termux package list into what can be installed (as Ubuntu
     * names), what must be reported as unmappable, and what is deliberately
     * dropped.
     *
     * The three lists are disjoint and together they account for the whole
     * input: every name ends up in exactly one of them. Names a status file
     * lists more than once (multi-arch stanzas share a package name) are
     * collapsed, because apt would otherwise be handed the same argument
     * twice — that is deduplication, not a drop. Order is otherwise
     * preserved so a diff against the leftovers file stays meaningful.
     */
    fun classify(names: List<String>): PackageClassification {
        val mapped = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        val unmapped = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        for (name in names) {
            if (!seen.add(name)) continue
            val override = OVERRIDES[name]
            when {
                override == DROP -> dropped += name
                override != null -> mapped += override
                isTermuxOwned(name) -> unmapped += name
                else -> mapped += name
            }
        }
        return PackageClassification(mapped, dropped, unmapped)
    }

    /**
     * True when a package name is Termux-specific by shape rather than by
     * explicit entry: the `termux-*` prefix, the `*-repo` suffix, or a
     * member of [TERMUX_OWNED_PACKAGES].
     *
     * Internal because the heuristic is a rule of [classify], not a second
     * public way to talk about the table.
     */
    internal fun isTermuxOwned(name: String): Boolean =
        name.startsWith("termux-") ||
            name.endsWith("-repo") ||
            name in TERMUX_OWNED_PACKAGES

    /**
     * Why a Termux-minted name was reported as unmappable, or `null` when
     * there is nothing specific to say.
     *
     * The plan's contract is that unmapped packages reach the user *with a
     * warning*, so a "not matched" line in the leftovers file is never a
     * dead end: it says what the name is and, when known, why nothing on
     * Ubuntu answers to it.
     */
    fun noteFor(name: String): String? = when {
        name in TERMUX_OWNED_PACKAGES ->
            "Termux-specific: no Ubuntu counterpart"
        name.startsWith("termux-") ->
            "A Termux app package: it integrates with the Termux app, not a Linux userspace"
        name.endsWith("-repo") ->
            "A Termux repository entry, not software"
        else -> null
    }
}

/**
 * The three-way split of a Termux package list produced by
 * [TermuxPackageMap.classify].
 *
 * @param mapped Ubuntu package names to install with apt.
 * @param unmapped Termux names worth telling the user about — they go to
 *   the leftovers file so nothing is silently lost.
 * @param dropped Termux-only packages with a deliberate, documented reason
 *   for not carrying them.
 */
data class PackageClassification(
    val mapped: List<String>,
    val unmapped: List<String>,
    val dropped: List<String>,
)
