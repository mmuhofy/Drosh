package dev.drosh.ui.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.drosh.domain.backup.BackupManifest
import dev.drosh.domain.backup.BackupOptions
import dev.drosh.domain.backup.BackupProgress
import dev.drosh.domain.backup.BackupRepository
import dev.drosh.domain.backup.RestoreSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Backup and restore, as far as the screen is concerned.
 *
 * ## What lives here and what does not
 *
 * Everything about *what to do* lives here: which tiers the user picked, which
 * archive is under inspection, what the repository said while it worked. Nothing
 * about *where files come from or go* lives here — the archive is written to a
 * file the repository owns in app cache, and moving it to the user's Documents
 * is the system picker's job (`BackupRestoreRoute`, via SAF). This state holder
 * never sees a `Uri` and never holds a permission for one, which is why the
 * screen can be recomposed, rotated or recreated without losing a running
 * backup: the work is in [viewModelScope], and the picker is a separate
 * gesture the user repeats.
 *
 * ## Why one flow of state and not five
 *
 * A backup and a restore are two halves of one screen with one mutually
 * exclusive "something is running" flag. Five state flows collected separately
 * would let the screen render a tier row as disabled while a manifest is being
 * read, or a "Geri yükle" button as enabled during a restore. One value means
 * one consistent frame.
 *
 * ## Why `stateIn` is not used here
 *
 * The repository's flows are one-shot jobs — [BackupRepository.createBackup]
 * and [BackupRepository.restore] each run to a terminal event and finish — not
 * observables that keep emitting. There is nothing to share, so the collector
 * writes into a [MutableStateFlow] and the screen reads that.
 *
 * ## The tiers are three rows over five switches
 *
 * `BackupOptions` carries a boolean per archive section, which is the
 * contract's own granularity. A user choosing what to back up thinks in the
 * three tiers of the plan — settings, app data, rootfs — so [BackupTier] is
 * the unit the screen offers and the mapping to the five booleans is done
 * here, once, rather than in every row.
 */

/** The tiers the screen offers, in the order the rows are drawn. */
enum class BackupTier {
    /** T0 — the settings file, themes, snippets. Never destructive to change. */
    Settings,

    /** T1 — DataStore and the Room snapshot: sessions, workspaces, chats. */
    AppData,

    /** T3/T4 — the Linux environment the shell actually runs in. */
    Rootfs,
}

/** Which long job the repository is running, for [onEvent] to read. */
private enum class BackupJob { Create, Restore }

/**
 * Everything the screen draws, in one value.
 *
 * @property options the tiers the next backup will include.
 * @property progress the repository's last report: a step while working, and
 *   the terminal event — [BackupProgress.Completed] or [BackupProgress.Failed] —
 *   kept until the next run so the completion panel and its error have
 *   something to say after the work has stopped.
 * @property inspected the archive whose manifest has been read, with the
 *   sections the user wants back. Null before a file is picked and while one
 *   is being read.
 * @property busy true while the repository is doing something. One flag for
 *   backup, restore and inspection, because all three disable the same rows.
 * @property inspectFailed the file the user picked is not a Drosh archive.
 *   Not a [BackupProgress.Failed]: reading a manifest is a read, not a job.
 * @property pendingSave the archive just produced, waiting for the route to
 *   hand it to the system's save dialog. The bytes already exist in app cache
 *   at this point — the user has simply not said where they should live.
 * @property savedPath where the produced bytes ended up, once they have been
 *   written. Kept so the screen can say so instead of going quiet.
 * @property restartRequested a restore has landed and the app has to be
 *   started again for anything to read its new state.
 */
data class BackupRestoreUiState(
    val options: BackupOptions = BackupOptions(),
    val progress: BackupProgress? = null,
    val inspected: InspectedArchive? = null,
    val busy: Boolean = false,
    val inspectFailed: Boolean = false,
    val pendingSave: String? = null,
    val savedPath: String? = null,
    val restartRequested: Boolean = false,
) {
    /** True while a run is reporting steps rather than sitting finished. */
    val running: Boolean
        get() = busy && (progress is BackupProgress.Started || progress is BackupProgress.Step)

    /** The archive that was just produced, when one was. */
    val completed: BackupProgress.Completed?
        get() = progress as? BackupProgress.Completed

    /** Why the last run failed, when it did. */
    val failure: String?
        get() = (progress as? BackupProgress.Failed)?.reason

    /** True when at least one tier is on, which is the only backup worth making. */
    val hasTiers: Boolean
        get() = options.includeSettings ||
            options.includeDataStore ||
            options.includeDatabase ||
            options.includeThemesAndSnippets ||
            options.includeRootfs
}

/**
 * An archive whose manifest has been read out of it.
 *
 * The file is kept next to the manifest because restore needs both: the
 * manifest is what the preview card shows, and the file is what
 * [BackupRepository.restore] reads. Keeping only the manifest would leave the
 * screen to find the file again by a path it was told not to keep.
 *
 * @property selected ids to restore, or null for every section. Null is the
 *   answer of "everything", not of "nothing" — [RestoreSelection] reads it the
 *   same way.
 */
data class InspectedArchive(
    val file: File,
    val manifest: BackupManifest,
    val selected: Set<String>? = null,
) {
    /** Every section, the way the repository wrote it. */
    val allIds: Set<String> get() = manifest.sections.map { it.id }.toSet()
}

/**
 * The backup screen's state holder.
 *
 * Injected as the domain interface, not the implementation: the repository is
 * one door the UI walks through, and the parts behind it (app data in `:data`,
 * the rootfs engine in `:terminal`) are free to change as long as the contract
 * holds.
 */
@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    private val repository: BackupRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupRestoreUiState())
    val state: StateFlow<BackupRestoreUiState> = _state.asStateFlow()

    /**
     * One tier on or off.
     *
     * The two rows that cover more than one archive section toggle their
     * sections together because a tier is one decision: a DataStore file with
     * no Room snapshot restores preferences over an untouched session list,
     * which is not a state anyone asked for, and the settings file without its
     * themes restores the words and loses the colours.
     *
     * Ignored while a job is running: a checkpoint that the archive being
     * written has already passed is a lie about what that archive contains.
     */
    fun toggle(tier: BackupTier) {
        if (_state.value.busy) return
        _state.update { current ->
            val options = current.options
            current.copy(
                options = when (tier) {
                    BackupTier.Settings -> options.copy(
                        includeSettings = !options.includeSettings,
                        includeThemesAndSnippets = !options.includeThemesAndSnippets,
                    )

                    BackupTier.AppData -> {
                        val on = !(options.includeDataStore && options.includeDatabase)
                        options.copy(includeDataStore = on, includeDatabase = on)
                    }

                    BackupTier.Rootfs -> options.copy(includeRootfs = !options.includeRootfs)
                },
            )
        }
    }

    /**
     * Writes an archive of the picked tiers into app cache.
     *
     * The result is a path, not a file the user has: the route takes
     * [BackupRestoreUiState.pendingSave] and opens the system's save dialog
     * there, so nothing is written outside the sandbox until the user chooses
     * a destination. A failed run keeps its reason on screen rather than
     * clearing it — the one thing a user needs after a backup that stopped
     * halfway is why.
     */
    fun startBackup() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    progress = null,
                    pendingSave = null,
                    savedPath = null,
                    inspectFailed = false,
                    restartRequested = false,
                )
            }
            repository.createBackup(_state.value.options)
                .collect { event -> onEvent(event, BackupJob.Create) }
            // The contract promises a terminal event, and every terminal event
            // clears the flag. This is the belt for the case that does not.
            _state.update { it.copy(busy = false) }
        }
    }

    /**
     * Reads a picked archive's manifest, extracting nothing.
     *
     * Off the main thread deliberately: the file was just copied out of a
     * content provider, and a manifest read is I/O that a user has already
     * waited for once.
     *
     * A null answer is a normal outcome, not an error — it is how the
     * repository says "this is not one of ours" — so it is recorded as
     * [BackupRestoreUiState.inspectFailed] and the screen says the same.
     */
    fun inspect(file: File) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    progress = null,
                    inspected = null,
                    inspectFailed = false,
                    restartRequested = false,
                )
            }
            val manifest = withContext(Dispatchers.IO) {
                runCatching { repository.inspectArchive(file) }.getOrNull()
            }
            _state.update {
                it.copy(
                    busy = false,
                    inspected = manifest?.let { read -> InspectedArchive(file, read) },
                    inspectFailed = manifest == null,
                )
            }
        }
    }

    /**
     * Restores the picked archive, all of it or the sections still selected.
     *
     * The selection is read at call time rather than held: what the user chose
     * in the preview card is what [InspectedArchive.selected] says, and passing
     * it again here would let the two disagree.
     *
     * Ends with [BackupRestoreUiState.restartRequested] rather than with a
     * restart. Restarting the process is a decision with consequences the
     * screen is in a better position to explain, and a repository that reached
     * out and did it would be a component tearing down the app it is running
     * inside.
     */
    fun startRestore(file: File, selection: RestoreSelection = RestoreSelection()) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    progress = null,
                    savedPath = null,
                    pendingSave = null,
                    inspectFailed = false,
                    restartRequested = false,
                )
            }
            repository.restore(file, selection)
                .collect { event -> onEvent(event, BackupJob.Restore) }
            _state.update { it.copy(busy = false) }
        }
    }

    /**
     * One section of the inspected archive, in or out of the restore.
     *
     * The last section stays on. An empty selection would restore nothing and
     * the row would show a switch that refuses to be turned off, which reads as
     * a broken control; unchecking the whole archive is what cancelling the
     * button is for.
     */
    fun toggleRestoreSection(id: String) {
        _state.update { current ->
            val inspected = current.inspected ?: return@update current
            val everything = inspected.allIds
            val chosen = inspected.selected ?: everything
            val next = if (id in chosen) chosen - id else chosen + id
            if (next.isEmpty()) return@update current
            current.copy(
                inspected = inspected.copy(
                    // Back to every section reads as "nothing was narrowed",
                    // which is also what the repository means by null.
                    selected = next.takeIf { it != everything },
                ),
            )
        }
    }

    /**
     * The archive has been written where the user asked for it.
     *
     * Called by the route after the bytes have landed, which is the only place
     * that knows: the save dialog is a system window and its answer arrives
     * outside the screen. Clearing [BackupRestoreUiState.pendingSave] is what
     * stops the route opening the same dialog again on the next rotation.
     */
    fun onBackupFileReady(path: String) {
        _state.update {
            it.copy(
                pendingSave = null,
                savedPath = path.takeIf { value -> value.isNotBlank() },
            )
        }
    }

    /**
     * Closes the restart prompt without restarting.
     *
     * The restore has already landed; this only dismisses the offer. The user
     * may have a shell they want to finish first, and the prompt will not
     * appear again until the next restore.
     */
    fun dismissRestartPrompt() {
        _state.update { it.copy(restartRequested = false) }
    }

    /**
     * One report from the repository, folded into the state.
     *
     * Terminal events stop the job and stay on screen — the completion panel
     * is built out of [BackupProgress.Completed], and its path is what the
     * route is waiting for. A restore that completes is also what raises the
     * restart offer.
     */
    private fun onEvent(event: BackupProgress, job: BackupJob) {
        _state.update { current ->
            when (event) {
                is BackupProgress.Failed -> current.copy(
                    busy = false,
                    progress = event,
                    pendingSave = null,
                    restartRequested = false,
                )

                is BackupProgress.Completed -> current.copy(
                    busy = false,
                    progress = event,
                    pendingSave = event.outputPath?.takeIf { it.isNotBlank() },
                    restartRequested = job == BackupJob.Restore,
                )

                is BackupProgress.Started,
                is BackupProgress.Step,
                -> current.copy(busy = true, progress = event)
            }
        }
    }
}
