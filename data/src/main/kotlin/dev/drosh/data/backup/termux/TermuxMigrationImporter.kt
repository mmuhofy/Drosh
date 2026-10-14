package dev.drosh.data.backup.termux

import java.io.File

/**
 * Plans the bionic → glibc half of a Termux migration.
 *
 * A user arriving from Termux has three kinds of luggage: their shell dotfiles,
 * the *list* of packages they installed, and their actual files. This class
 * covers the first two, which are exactly the parts that cannot be copied as
 * bytes — binaries linked against Android's bionic libc cannot execute under
 * the glibc userspace Drosh boots, so the package list is mapped to Ubuntu
 * names and reinstalled (see [TermuxPackageMap]), and the dotfiles are
 * rewritten to drop the paths that do not exist here (see [DotfileAdapter]).
 * Neither step may lose anything on the way: whatever cannot be carried is
 * reported, never silently dropped.
 *
 * The importer is deliberately **plan-only**: it reads a Termux `$HOME` and an
 * optional dpkg status file, and returns a [MigrationPlan]. Writing files into
 * the fresh rootfs — the rewritten dotfiles, the leftovers directory — is the
 * caller's job, because that is where the rootfs paths and the bootstrap
 * script live.
 */
class TermuxMigrationImporter {

    /**
     * Parses a Termux dpkg status file (`$PREFIX/var/lib/dpkg/status`).
     *
     * The format is one stanza per package — `Field: value` lines, a blank
     * line between stanzas — and only the `Package:` field matters here.
     * Everything else is deliberately ignored: `Status`, `Priority`,
     * `Architecture`, `Maintainer`, the multi-line `Description` (whose
     * continuation lines start with a space and must not be mistaken for
     * fields) and `Conffiles` all describe the *Android install*, which is
     * not what is being carried across.
     *
     * Continuation lines are recognised by their leading whitespace and
     * skipped, so a line like ` Package: not-a-field` inside a description
     * cannot inject a package name. A stanza that carries no `Package:`
     * field contributes nothing. A name listed by more than one stanza — the
     * multi-arch shape, or a status file that simply lists a package twice —
     * is returned once.
     *
     * Every stanza's name is returned, including one in a
     * `deinstall ok config-files` state. Filtering those here would quietly
     * delete a name the user may still care about; the classification step
     * is where a name becomes visible, and a wrong install line fails loudly
     * at apt rather than vanishing.
     */
    fun parseDpkgStatus(statusFile: File): List<String> {
        if (!statusFile.isFile) return emptyList()

        val names = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var current: String? = null

        fun flush() {
            current?.let { name ->
                if (name.isNotEmpty() && seen.add(name)) names += name
            }
            current = null
        }

        statusFile.useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) {
                    flush()
                } else if (line.startsWith("Package:")) {
                    // A blank line between stanzas is not guaranteed; a new
                    // `Package:` field is the boundary that always is.
                    flush()
                    current = line.substringAfter(':').trim()
                }
                // The rest is Android-install detail this migration does not
                // carry: Status:, Priority:, Architecture:, Maintainer:,
                // Depends:, and Description: continuation lines (recognised
                // here by their leading whitespace, so a description that
                // quotes `Package: not-a-package` cannot inject a name).
            }
        }
        flush()
        return names
    }

    /**
     * Plans a migration from a Termux `$HOME` directory and an optional
     * dpkg status file.
     *
     * [homeDir] is the *Termux* home — `/data/data/com.termux/files/home` —
     * read-only from here; nothing is written to it. The only files
     * considered are `.zshrc`, `.bashrc`, `.profile` and `.inputrc`, and only
     * when they exist as plain files. When both `.zshrc` and `.bashrc` exist
     * both are planned: which one the Ubuntu side eventually sources is the
     * caller's business, and dropping one to guess would be exactly the
     * silent loss this feature exists to prevent.
     *
     * Each planned dotfile carries its own dropped-line count, so the caller
     * can tell a rewrite (`9 kept, 1 dropped`) from a skeleton
     * (`2 kept, 40 dropped`). In the latter case the better migration is the
     * original file, moved verbatim into the leftovers directory — that
     * judgement needs the rootfs path and the UI, so it stays with the caller.
     *
     * @param dpkgStatusFile the Termux dpkg status file, when the archive
     *   carried one. `null` — or a path that is not a readable file — plans
     *   the dotfiles alone with empty package lists, rather than failing a
     *   restore over one missing member.
     */
    fun plan(homeDir: File, dpkgStatusFile: File?): MigrationPlan {
        val dotFiles = mutableListOf<DotFilePlan>()
        for (name in DOT_FILE_NAMES) {
            val file = File(homeDir, name)
            if (!file.isFile) continue
            val result = DotfileAdapter.adapt(name, file.readLines())
            dotFiles += DotFilePlan(
                name = name,
                rewrittenLines = result.rewrittenLines,
                droppedLineCount = result.droppedLineCount,
            )
        }

        val termuxPackages =
            dpkgStatusFile?.takeIf { it.isFile }?.let { parseDpkgStatus(it) }.orEmpty()
        val classification = TermuxPackageMap.classify(termuxPackages)

        return MigrationPlan(
            dotFiles = dotFiles,
            aptPackages = classification.mapped,
            unmappedPackages = classification.unmapped,
            droppedPackages = classification.dropped,
        )
    }

    /**
     * What the migration will do, before anything is written.
     *
     * @property dotFiles one entry per dotfile found in the Termux home,
     *   with `.zshrc` before `.bashrc` before `.profile` before `.inputrc`.
     * @property aptPackages Ubuntu package names to install — the mapped
     *   half of the Termux list, deduplicated.
     * @property unmappedPackages Termux names with no Ubuntu counterpart,
     *   destined for `~/imported-from-termux/packages-not-matched.txt` so
     *   the user sees them (with a warning, see
     *   [TermuxPackageMap.noteFor]).
     * @property droppedPackages Termux-only packages deliberately not
     *   carried — the reason for each is documented in
     *   [TermuxPackageMap.OVERRIDES].
     */
    data class MigrationPlan(
        val dotFiles: List<DotFilePlan>,
        val aptPackages: List<String>,
        val unmappedPackages: List<String>,
        val droppedPackages: List<String>,
    )

    /**
     * One dotfile, as it should be written on the Ubuntu side.
     *
     * @property name the file name, e.g. `.zshrc`.
     * @property rewrittenLines the lines to write, unchanged from the
     *   Termux original minus the dropped ones.
     * @property droppedLineCount how many lines the adapter removed.
     */
    data class DotFilePlan(
        val name: String,
        val rewrittenLines: List<String>,
        val droppedLineCount: Int,
    )

    companion object {

        /**
         * Where anything the migration could not carry goes, under the
         * user's home: `~/imported-from-termux/`.
         *
         * Named for what it is rather than `termux-backup/`, because the
         * directory is a landing zone the user browses after the fact, not
         * an archive to restore from.
         */
        const val LEFTOVERS_DIR_NAME = "imported-from-termux"

        /** The leftovers file that lists the unmapped package names. */
        const val PACKAGES_FILE_NAME = "packages-not-matched.txt"

        /**
         * The dotfiles planned, in order. zsh first because the Termux user
         * who cares about their rc file runs zsh; `.inputrc` completes it
         * for the rest.
         */
        private val DOT_FILE_NAMES = listOf(".zshrc", ".bashrc", ".profile", ".inputrc")
    }
}
