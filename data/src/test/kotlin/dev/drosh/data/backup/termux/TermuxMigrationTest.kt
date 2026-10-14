package dev.drosh.data.backup.termux

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Termux migration, exercised at the data layer: the dpkg status parse,
 * the package classification, the dotfile rewrite, and the plan that ties
 * them together.
 *
 * Everything runs on the JVM against temp directories — no Android API, no
 * rootfs, no shell. The invariant under test throughout is the one the plan
 * states for every migration path: **nothing is silently dropped**. Every
 * package name in, every package name out, in exactly one of the three
 * buckets; every dotfile line in, every dotfile line out, kept or counted.
 */
class TermuxMigrationTest {

    private val importer = TermuxMigrationImporter()
    private val temporaries = mutableListOf<File>()

    @After
    fun cleanUp() {
        temporaries.forEach { it.deleteRecursively() }
    }

    // ---- helpers ----------------------------------------------------------

    /** A Termux `$HOME` stand-in, empty. */
    private fun tempHome(): File {
        val dir = File.createTempFile("termux-home", null).also { temporaries += it }
        check(dir.delete() && dir.mkdir()) { "could not create a temp home" }
        return dir
    }

    private fun tempFile(name: String, content: String): File =
        File.createTempFile(name, null)
            .also { temporaries += it }
            .apply { writeText(content) }

    // ---- dpkg status parsing ----------------------------------------------

    @Test
    fun `parses every package out of a multi stanza status file`() {
        val status = """
            Package: apt
            Status: install ok installed
            Priority: important
            Section: admin
            Installed-Size: 4112
            Maintainer: Ubuntu Developers <ubuntu-devel-discuss@lists.ubuntu.com>
            Architecture: aarch64
            Multi-Arch: foreign
            Depends: adduser, gpgv, libapt-pkg6.0t64
            Description: commandline package manager
             This is the second line of a description, prefixed with a space —
             it is a continuation, not a field, and must not be parsed as one.

            Package: bash
            Status: install ok installed
            Priority: required
            Section: shells
            Architecture: aarch64

            Package: zsh
            Status: install ok installed
            Priority: optional
            Section: shells
            Architecture: aarch64
        """.trimIndent()

        assertEquals(
            listOf("apt", "bash", "zsh"),
            importer.parseDpkgStatus(tempFile("status", status)),
        )
    }

    @Test
    fun `a new package field is the boundary even without a blank line`() {
        val status = """
            Package: git
            Status: install ok installed
            Package: vim
            Status: install ok installed
        """.trimIndent()

        assertEquals(listOf("git", "vim"), importer.parseDpkgStatus(tempFile("status", status)))
    }

    @Test
    fun `one package listed by two stanzas is one package`() {
        // The multi-arch shape: same package name, two architectures.
        val status = """
            Package: libffi
            Architecture: aarch64
            Version: 3.4.4

            Package: libffi
            Architecture: x86_64
            Version: 3.4.4
        """.trimIndent()

        assertEquals(listOf("libffi"), importer.parseDpkgStatus(tempFile("status", status)))
    }

    @Test
    fun `an indented package line inside a description cannot inject a name`() {
        val status = """
            Package: git
            Description: a revision control system
             Package: totally-made-up
        """.trimIndent()

        assertEquals(listOf("git"), importer.parseDpkgStatus(tempFile("status", status)))
    }

    @Test
    fun `a status file that is not there parses to nothing`() {
        assertTrue(importer.parseDpkgStatus(File(tempHome(), "missing")).isEmpty())
    }

    // ---- package classification -------------------------------------------

    @Test
    fun `the known divergences get their ubuntu names`() {
        assertEquals("python3", TermuxPackageMap.toUbuntu("python"))
        // Ubuntu 24.04 ships no Python 2 at all — the identity is deliberate
        // and documented, so apt fails loudly instead of the user's package
        // being silently erased here.
        assertEquals("python2", TermuxPackageMap.toUbuntu("python2"))
        assertEquals("rustc", TermuxPackageMap.toUbuntu("rust"))
        assertEquals("nodejs", TermuxPackageMap.toUbuntu("nodejs-lts"))
        assertEquals("openssh-client", TermuxPackageMap.toUbuntu("openssh"))
        assertEquals("sqlite3", TermuxPackageMap.toUbuntu("sqlite"))
        assertEquals("mariadb-server", TermuxPackageMap.toUbuntu("mariadb"))
        assertEquals("man-db", TermuxPackageMap.toUbuntu("man"))
        assertEquals("fd-find", TermuxPackageMap.toUbuntu("fd"))
    }

    @Test
    fun `every curated identity entry maps to itself`() {
        val identity = listOf(
            "clang", "nodejs", "golang", "ruby", "perl", "php", "gdb",
            "coreutils", "util-linux", "dash", "grep", "sed", "gawk",
            "findutils", "tar", "gzip", "bzip2", "xz-utils", "zip", "unzip",
            "rsync", "make", "cmake", "jq", "tree", "htop", "less", "bat", "ed",
            "vim", "neovim", "nano", "emacs", "zsh", "fish", "tmux", "git",
            "wget", "curl", "fzf", "ripgrep", "lazygit", "neofetch",
            "postgresql", "nginx", "apache2", "ffmpeg", "imagemagick", "openssl",
        )
        identity.forEach { name ->
            assertEquals(name, TermuxPackageMap.toUbuntu(name))
        }
    }

    @Test
    fun `termux-only packages map to null rather than to a stolen name`() {
        val dropped = listOf(
            "busybox", "valgrind", "tsu", "proot", "proot-distro", "termux-tools",
            "termux-api", "command-not-found", "libandroid-support",
            "game-repo", "science-repo", "root-repo", "x11-repo",
        )
        dropped.forEach { name ->
            assertNull(name, TermuxPackageMap.toUbuntu(name))
        }
    }

    @Test
    fun `the drop sentinel is unreachable as a package name`() {
        // A NUL-prefixed sentinel cannot collide with anything apt knows.
        assertEquals("\u0000drop", TermuxPackageMap.DROP)
        val classification = TermuxPackageMap.classify(
            listOf("busybox", "git", "pkg", "termux-ponytail"),
        )
        assertFalse(TermuxPackageMap.DROP in classification.mapped)
        assertFalse(classification.mapped.any { it.contains("\u0000") })
    }

    @Test
    fun `diagnostic dump of what the map and classify produce`() {
        val sb = StringBuilder()
        sb.append("DROP=[${TermuxPackageMap.DROP.replace("\u0000", "NUL")}] ")
        sb.append("ov_pkg=[${TermuxPackageMap.OVERRIDES["pkg"]}] ")
        sb.append("ov_tools=[${TermuxPackageMap.OVERRIDES["termux-tools"]}] ")
        sb.append("ov_api=[${TermuxPackageMap.OVERRIDES["termux-api"]}] ")
        sb.append("ov_repo=[${TermuxPackageMap.OVERRIDES["science-repo"]}] ")
        sb.append("ov_ponytail=[${TermuxPackageMap.OVERRIDES["termux-ponytail"]}] ")
        val c = TermuxPackageMap.classify(
            listOf(
                "python", "git", "python2", "termux-tools", "pkg",
                "termux-ponytail", "science-repo", "ripgrep",
            ),
        )
        sb.append("mapped=${c.mapped} ")
        sb.append("dropped=${c.dropped} ")
        sb.append("unmapped=${c.unmapped}")
        throw AssertionError(sb.toString())
    }

    @Test
    fun `classify sorts a list into mapped, dropped and unmapped`() {
        val classification = TermuxPackageMap.classify(
            listOf(
                "python", "git", "python2", "termux-tools", "pkg",
                "termux-ponytail", "science-repo", "ripgrep",
            ),
        )

        assertEquals(
            listOf("python3", "git", "python2", "ripgrep"),
            classification.mapped,
        )
        assertEquals(listOf("termux-tools", "science-repo"), classification.dropped)
        assertEquals(listOf("pkg", "termux-ponytail"), classification.unmapped)
    }

    @Test
    fun `classify keeps order and collapses repeats`() {
        val classification = TermuxPackageMap.classify(
            listOf("wget", "python", "wget", "nodejs-lts", "nodejs", "python"),
        )

        // Deduplication, not a drop: apt would otherwise be handed the same
        // package three times, and the leftovers file would show no loss.
        assertEquals(listOf("wget", "python3", "nodejs"), classification.mapped)
    }

    @Test
    fun `nothing is silently dropped along the package path`() {
        val names = listOf(
            "python", "git", "vim", "python2", "busybox", "termux-tools",
            "pkg", "termux-cloud-sync", "science-repo", "ripgrep", "wget",
            "some-package-nobody-has-heard-of",
        )
        val classification = TermuxPackageMap.classify(names)

        names.forEach { name ->
            val ubuntu = TermuxPackageMap.toUbuntu(name)
            val where = listOf(
                "apt" to (ubuntu != null && ubuntu in classification.mapped),
                "dropped" to (name in classification.dropped),
                "unmapped" to (name in classification.unmapped),
            ).filter { it.second }
            assertEquals("$name must be reported exactly once", 1, where.size)
        }
    }

    @Test
    fun `an unmapped name carries a reason, and a dropped one does not need to`() {
        assertEquals(
            "Termux-specific: no Ubuntu counterpart",
            TermuxPackageMap.noteFor("pkg"),
        )
        assertTrue(TermuxPackageMap.noteFor("termux-cloud-sync")!!.contains("Termux"))
        assertTrue(TermuxPackageMap.noteFor("my-repo")!!.contains("repository"))
        // Deliberately dropped and already documented in the table.
        assertNull(TermuxPackageMap.noteFor("busybox"))
        assertNull(TermuxPackageMap.noteFor("git"))
    }

    // ---- dotfile adaptation ----------------------------------------------

    @Test
    fun `a clean shell file survives untouched`() {
        val lines = listOf(
            "# ~/.zshrc",
            "export EDITOR=vim",
            "alias ls='ls --color=auto'",
            "HISTFILE=\$HOME/.zsh_history",
            "PS1='%n@%m %1~ %# '",
        )

        val result = DotfileAdapter.adapt(".zshrc", lines)

        assertEquals(0, result.droppedLineCount)
        assertEquals(lines, result.rewrittenLines)
    }

    @Test
    fun `a realistic termux zshrc keeps its aliases and loses its prefix`() {
        val lines = listOf(
            "# ~/.zshrc — Termux",
            "export PREFIX=/data/data/com.termux/files/usr",
            "export PATH=\"\$PREFIX/bin:\$PATH\"",
            "export LD_LIBRARY_PATH=\"\$PREFIX/lib\"",
            "alias ls='ls --color=auto'",
            "alias ll='ls -lah'",
            "alias pkg='pkg install'",
            "export EDITOR=vim",
            "HISTFILE=\$HOME/.zsh_history",
            "HISTSIZE=5000",
            "SAVEHIST=5000",
            "PS1='%n@%m %1~ %# '",
            "termux-open-url https://example.com",
        )

        val result = DotfileAdapter.adapt(".zshrc", lines)

        assertEquals(5, result.droppedLineCount)
        assertEquals(
            listOf(
                "# ~/.zshrc — Termux",
                "alias ls='ls --color=auto'",
                "alias ll='ls -lah'",
                "export EDITOR=vim",
                "HISTFILE=\$HOME/.zsh_history",
                "HISTSIZE=5000",
                "SAVEHIST=5000",
                // The prompt keeps its own `#`: a rewrite that stripped
                // comments naively would have eaten the `%#` here.
                "PS1='%n@%m %1~ %# '",
            ),
            result.rewrittenLines,
        )
    }

    @Test
    fun `comments are inert even when they quote the prefix`() {
        val result = DotfileAdapter.adapt(
            ".zshrc",
            listOf(
                "# the prefix used to be \$PREFIX, exported by termux-wake-lock",
                "export EDITOR=vim",
            ),
        )

        assertEquals(0, result.droppedLineCount)
        assertEquals(2, result.rewrittenLines.size)
    }

    @Test
    fun `pkg-config is not mistaken for the termux pkg front-end`() {
        val result = DotfileAdapter.adapt(
            ".zshrc",
            listOf(
                "export PKG_CONFIG_PATH=/usr/lib/pkgconfig",
                "alias pkgcfg='pkg-config --version'",
            ),
        )

        assertEquals(0, result.droppedLineCount)
    }

    @Test
    fun `TERM survives while the TERMUX_ environment goes`() {
        val result = DotfileAdapter.adapt(
            ".zshrc",
            listOf(
                "export TERM=xterm-256color", // every tty needs this one
                "export TERMUX_STORAGE=/storage/emulated/0",
                "TERMUX_X=1",
                "echo \"\${TERMUX_VERSION}\"",
            ),
        )

        assertEquals(3, result.droppedLineCount)
        assertEquals(listOf("export TERM=xterm-256color"), result.rewrittenLines)
    }

    @Test
    fun `an if block with a termux condition loses its whole body`() {
        // The shape of Termux's very own default bashrc: source the prefix's
        // own rc, or nothing. Half a construct is a syntax error, not a
        // rewrite — the condition and the body it guards go together, and any
        // `else` branch goes with them.
        val lines = listOf(
            "if [ -f /data/data/com.termux/files/usr/etc/bash.bashrc ]; then",
            "    . /data/data/com.termux/files/usr/etc/bash.bashrc",
            "fi",
            "export EDITOR=vi",
        )

        val result = DotfileAdapter.adapt(".bashrc", lines)

        assertEquals(3, result.droppedLineCount)
        assertEquals(listOf("export EDITOR=vi"), result.rewrittenLines)
    }

    @Test
    fun `a clean construct is kept including its else branch`() {
        val lines = listOf(
            "if [ -f \$HOME/.zsh_local ]; then",
            "    source \$HOME/.zsh_local",
            "else",
            "    export EDITOR=vim",
            "fi",
        )

        val result = DotfileAdapter.adapt(".zshrc", lines)

        assertEquals(0, result.droppedLineCount)
        assertEquals(lines, result.rewrittenLines)
    }

    @Test
    fun `a loop whose body installs packages goes as one construct`() {
        val lines = listOf(
            "for p in vim git; do",
            "    pkg install \$p",
            "done",
        )

        val result = DotfileAdapter.adapt(".bashrc", lines)

        assertEquals(3, result.droppedLineCount)
        assertTrue(result.rewrittenLines.isEmpty())
    }

    @Test
    fun `constructs nest, and each terminator closes the one it belongs to`() {
        val lines = listOf(
            "for dir in \$HOME/bin /usr/local/bin; do",
            "    if [ -d \"\$dir\" ]; then",
            "        export PATH=\"\$dir:\$PATH\"",
            "    fi",
            "done",
            "pkg install ripgrep",
            "export EDITOR=vim",
        )

        val result = DotfileAdapter.adapt(".zshrc", lines)

        assertEquals(1, result.droppedLineCount)
        assertEquals(6, result.rewrittenLines.size)
    }

    @Test
    fun `a heredoc quoting the prefix is dropped with its body`() {
        val lines = listOf(
            "cat <<'EOF' > \$HOME/.config/drosh",
            "export PATH=\$PREFIX/bin:\$PATH",
            "EOF",
            "alias ll='ls -lah'",
        )

        val result = DotfileAdapter.adapt(".bashrc", lines)

        assertEquals(3, result.droppedLineCount)
        assertEquals(listOf("alias ll='ls -lah'"), result.rewrittenLines)
    }

    @Test
    fun `a clean heredoc survives with its body intact`() {
        val lines = listOf(
            "cat <<'EOF' > \$HOME/.config/drosh",
            "export EDITOR=vim",
            "EOF",
            "alias ll='ls -lah'",
        )

        val result = DotfileAdapter.adapt(".bashrc", lines)

        assertEquals(0, result.droppedLineCount)
        assertEquals(lines, result.rewrittenLines)
    }

    // ---- plan --------------------------------------------------------------

    private fun realisticHome(): File {
        val home = tempHome()
        File(home, ".zshrc").writeText(
            """
            # ~/.zshrc — Termux
            export PREFIX=/data/data/com.termux/files/usr
            export PATH="${'$'}PREFIX/bin:${'$'}PATH"
            export LD_LIBRARY_PATH="${'$'}PREFIX/lib"
            export EDITOR=vim
            alias ls='ls --color=auto'
            alias pkg='pkg install'
            HISTFILE=${'$'}HOME/.zsh_history
            PS1='%n@%m %1~ %# '
            termux-open-url https://example.com
            """.trimIndent(),
        )
        File(home, ".bashrc").writeText(
            """
            # ~/.bashrc
            export EDITOR=vi
            alias ll='ls -lah'
            PS1='\h:\w\${'$'} '
            """.trimIndent(),
        )
        File(home, ".inputrc").writeText(
            """
            set completion-ignore-case on
            "\e[A": history-search-backward
            """.trimIndent(),
        )
        return home
    }

    private fun realisticStatus(): File = tempFile(
        "status",
        """
        Package: git
        Status: install ok installed
        Architecture: aarch64
        Description: fast, scalable, distributed revision control system

        Package: python
        Status: install ok installed
        Architecture: aarch64

        Package: wget
        Status: install ok installed
        Architecture: aarch64

        Package: termux-api
        Status: install ok installed
        Architecture: aarch64

        Package: proot-distro
        Status: install ok installed
        Architecture: aarch64

        Package: pkg
        Status: install ok installed
        Architecture: aarch64

        Package: termux-cloud-sync
        Status: install ok installed
        Architecture: aarch64
        """.trimIndent(),
    )

    @Test
    fun `plan assembles dotfiles and packages out of a termux home`() {
        val plan = importer.plan(realisticHome(), realisticStatus())

        // .profile is absent, so it is not planned; the three that exist are
        // planned in the zsh-first order the Termux user would expect.
        assertEquals(
            listOf(".zshrc", ".bashrc", ".inputrc"),
            plan.dotFiles.map { it.name },
        )

        val zshrc = plan.dotFiles.single { it.name == ".zshrc" }
        assertEquals(5, zshrc.droppedLineCount)
        assertEquals(
            listOf(
                "# ~/.zshrc — Termux",
                "export EDITOR=vim",
                "alias ls='ls --color=auto'",
                "HISTFILE=\$HOME/.zsh_history",
                "PS1='%n@%m %1~ %# '",
            ),
            zshrc.rewrittenLines,
        )

        val bashrc = plan.dotFiles.single { it.name == ".bashrc" }
        assertEquals(0, bashrc.droppedLineCount)
        assertEquals(4, bashrc.rewrittenLines.size)

        assertEquals(2, plan.dotFiles.single { it.name == ".inputrc" }.rewrittenLines.size)

        assertEquals(listOf("git", "python3", "wget"), plan.aptPackages)
        assertEquals(listOf("pkg", "termux-cloud-sync"), plan.unmappedPackages)
        assertEquals(listOf("termux-api", "proot-distro"), plan.droppedPackages)
    }

    @Test
    fun `plan reports every package from the status file exactly once`() {
        val statusPackages =
            listOf("git", "python", "wget", "termux-api", "proot-distro", "pkg", "termux-cloud-sync")
        val plan = importer.plan(realisticHome(), realisticStatus())

        statusPackages.forEach { name ->
            val ubuntu = TermuxPackageMap.toUbuntu(name)
            val where = listOf(
                "apt" to (ubuntu != null && ubuntu in plan.aptPackages),
                "dropped" to (name in plan.droppedPackages),
                "unmapped" to (name in plan.unmappedPackages),
            ).filter { it.second }
            assertEquals("$name must be reported exactly once", 1, where.size)
        }
    }

    @Test
    fun `plan without a status file still migrates the dotfiles`() {
        val plan = importer.plan(realisticHome(), null)

        assertTrue(plan.aptPackages.isEmpty())
        assertTrue(plan.unmappedPackages.isEmpty())
        assertTrue(plan.droppedPackages.isEmpty())
        assertEquals(3, plan.dotFiles.size)
    }

    @Test
    fun `a status file that is not there is not a failure`() {
        val plan = importer.plan(realisticHome(), File(tempHome(), "no-status-here"))

        assertTrue(plan.aptPackages.isEmpty())
        assertEquals(3, plan.dotFiles.size)
    }

    @Test
    fun `an empty home plans an empty dotfile list`() {
        val plan = importer.plan(tempHome(), null)

        assertTrue(plan.dotFiles.isEmpty())
    }

    @Test
    fun `the leftovers location is where unmapped packages are documented`() {
        assertEquals("imported-from-termux", TermuxMigrationImporter.LEFTOVERS_DIR_NAME)
        assertEquals("packages-not-matched.txt", TermuxMigrationImporter.PACKAGES_FILE_NAME)
    }
}
