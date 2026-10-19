package dev.drosh.ui.backup

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSuccess
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.DroshTrack
import dev.drosh.design.system.LocalFontSet
import dev.drosh.domain.backup.BackupOptions
import dev.drosh.domain.backup.BackupProgress
import dev.drosh.domain.backup.BackupSectionKind
import dev.drosh.domain.backup.RestoreSelection
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.agent.components.ActionButton
import dev.drosh.ui.settings.GROUP_GAP
import dev.drosh.ui.settings.SettingsCategoryScreen
import dev.drosh.ui.settings.SettingsGroupColumn
import dev.drosh.ui.settings.SettingsSegmented
import dev.drosh.ui.settings.SettingsSwitch
import dev.drosh.ui.settings.SettingsTile
import dev.drosh.ui.settings.TileCap
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which half of the screen is showing. The two never share a row. */
private enum class BackupSegment { Backup, Restore }

/**
 * The steps the repository reports, as something a person reads.
 *
 * The contract asks the repository for "short, stable" step names and says the
 * UI may map them to a phrase — the phrase is what the progress row shows.
 * Known names are mapped; anything unknown falls through unchanged, so a step
 * the repository learns later still reaches the screen rather than being
 * swallowed as blank.
 */
private fun stepLabel(step: String): String = when (step) {
    "settings" -> STEP_SETTINGS
    "app data" -> STEP_APP_DATA
    "database" -> STEP_DATABASE
    "themes" -> STEP_THEMES
    "snippets" -> STEP_SNIPPETS
    "input preferences" -> STEP_INPUT_PREFS
    "rootfs" -> STEP_ROOTFS
    "manifest" -> STEP_MANIFEST
    "verifying" -> STEP_VERIFYING
    else -> step
}

/**
 * Backup and restore, in one screen with two halves.
 *
 * ## Why one screen and not two routes
 *
 * A backup and a restore are the two directions of the same thing, and the
 * question that leads here — "where do I keep my phone's Linux machine?" — is
 * one question. Two routes would mean two entries in Settings, two back
 * presses to leave, and a user who came to restore discovering that the app
 * considers that a different place. One screen, one entry, and a control at
 * the top that names both directions.
 *
 * ## Why the two halves are not the same shape
 *
 * Backing up is choosing: tiers, then a button. Restoring is inspecting: a
 * file, then what is inside it, then a button. Rendering one as the other
 * would mean a "Dosya seç" button on a screen that has nothing to pick from
 * yet, or three tier switches on a screen whose contents are already decided.
 *
 * ## Why the file picker is not here
 *
 * Both halves move bytes through the system picker, and both launchers live
 * in `BackupRestoreRoute`. The screen asks for a save or a pick through a
 * callback and never sees a `Uri`: a picker answer that has to survive a
 * recomposition is a picker answer the app can lose to a rotation. The route
 * holds the launcher, copies the bytes, and tells the view model the result.
 *
 * ## Drawn in the settings language
 *
 * The same tiles, the same corner radius, the same 24dp/4dp rhythm as the
 * category screens, and the same large top bar with the agent's back pill.
 * This screen is reached from Settings and should not announce itself as a
 * different app.
 */
@Composable
fun BackupRestoreScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupRestoreViewModel = hiltViewModel(),
    /** Saves the archive at this path through the system's save dialog. */
    onSaveBackup: (String) -> Unit = {},
    /** Opens the system's file picker; the manifest is read from what returns. */
    onPickArchive: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var segment by rememberSaveable { mutableStateOf(BackupSegment.Backup) }

    // Cast rather than assumed: the locale provider wraps the context in
    // something that is not an Activity, and the wrapper is what a composition
    // under `ProvideLocale` actually sees.
    val activity = LocalContext.current as? ComponentActivity

    SettingsCategoryScreen(title = SCREEN_TITLE, onBack = onBack, modifier = modifier) {
        // ── which half ──────────────────────────────────────────────────────
        SettingsGroupColumn(label = null, items = 1) { _, cap ->
            SettingsTile(
                cap = cap,
                stacked = true,
                title = TAB_TITLE,
                supporting = TAB_SUPPORTING,
            ) {
                SettingsSegmented(
                    options = listOf(
                        BackupSegment.Backup to TAB_BACKUP,
                        BackupSegment.Restore to TAB_RESTORE,
                    ),
                    selected = segment,
                    onSelect = { segment = it },
                )
            }
        }

        Spacer(Modifier.height(GROUP_GAP))

        when (segment) {
            BackupSegment.Backup -> BackupSegmentContent(
                state = state,
                onToggle = viewModel::toggle,
                onStart = viewModel::startBackup,
                onSave = onSaveBackup,
            )

            BackupSegment.Restore -> RestoreSegmentContent(
                state = state,
                onPick = onPickArchive,
                onToggleSection = viewModel::toggleRestoreSection,
                onRestore = viewModel::startRestore,
            )
        }
    }

    // A restore has landed and nothing has read its new state yet. A dialog
    // rather than an automatic recreate, because a user with a shell they are
    // in the middle of has to be the one who decides when.
    if (state.restartRequested) {
        RestartPrompt(
            onConfirm = { activity?.let { ActivityCompat.recreate(it) } },
            onDismiss = viewModel::dismissRestartPrompt,
        )
    }
}

// ── the backup half ───────────────────────────────────────────────────────

/**
 * Yedekle: pick the tiers, press the button, wait, keep the file.
 *
 * The tiers come first because they are the only decision, and the button
 * comes after them because a button that starts a job the screen is still
 * describing is a button the user reads past.
 *
 * The result panel is the last thing on the half and stays there until the
 * next run: a path that vanishes the moment it appears is a path nobody gets
 * to press "Kaydet" on.
 */
@Composable
private fun BackupSegmentContent(
    state: BackupRestoreUiState,
    onToggle: (BackupTier) -> Unit,
    onStart: () -> Unit,
    onSave: (String) -> Unit,
) {
    val tiers = BackupTier.entries

    SettingsGroupColumn(label = SECTION_TIERS, items = tiers.size) { index, cap ->
        val tier = tiers[index]
        TierRow(
            cap = cap,
            tier = tier,
            on = tierOn(state.options, tier),
            onToggle = { onToggle(tier) },
        )
    }

    BackupNote(text = NOTE_BACKUP)

    Spacer(Modifier.height(GROUP_GAP))

    ActionButton(
        text = ACTION_BACKUP,
        onClick = onStart,
        // Nothing ticked means nothing to write, and a button that produces an
        // empty archive is worse than no button at all.
        enabled = !state.busy && state.hasTiers,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
    )

    Spacer(Modifier.height(GROUP_GAP))
    RunStatus(state = state, failurePrefix = NOTE_BACKUP_FAILED)

    val completed = state.completed
    if (completed != null) {
        val path = completed.outputPath
        SettingsGroupColumn(label = SECTION_RESULT, items = 1) { _, cap ->
            SettingsTile(
                cap = cap,
                icon = DroshIcons.Check,
                title = RESULT_BACKUP_TITLE,
                supporting = path ?: RESULT_NO_PATH,
            )
        }
        if (path != null && state.pendingSave != null) {
            // The button is the way back after a dismissed save sheet: the
            // archive is still in app cache, and this is what offers it again.
            // It goes away once the bytes have landed somewhere of the user's.
            Spacer(Modifier.height(GROUP_GAP))
            ActionButton(
                text = ACTION_SAVE,
                onClick = { onSave(path) },
                enabled = !state.busy,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
            )
        }
        state.savedPath?.let { saved ->
            BackupNote(text = "$NOTE_SAVED $saved", color = DroshSuccess)
        }
    }
}

/**
 * One tier: what it holds, and whether it is going into the archive.
 *
 * A switch rather than a checkbox, because a switch is what every other on/off
 * row in this app wears, and a user who has learned one of them should not be
 * asked to learn the other here.
 */
@Composable
private fun TierRow(
    cap: TileCap,
    tier: BackupTier,
    on: Boolean,
    onToggle: () -> Unit,
) {
    SettingsTile(
        cap = cap,
        icon = tierIcon(tier),
        title = tierTitle(tier),
        supporting = tierDetail(tier),
    ) {
        SettingsSwitch(checked = on, onCheckedChange = { onToggle() })
    }
}

// ── the restore half ──────────────────────────────────────────────────────

/**
 * Geri yükle: pick a file, read what is inside it, put it back.
 *
 * The picker button is always there, so the first run of this half has
 * somewhere to go from. The preview group takes over the moment a manifest
 * comes back — that is when the second decision (which sections) exists.
 */
@Composable
private fun RestoreSegmentContent(
    state: BackupRestoreUiState,
    onPick: () -> Unit,
    onToggleSection: (String) -> Unit,
    onRestore: (File, RestoreSelection) -> Unit,
) {
    // The file was read and is not ours. Said on the screen rather than as a
    // toast: a toast disappears, and this is the answer to the question the
    // user just asked by picking that file.
    if (state.inspectFailed) {
        BackupNote(text = NOT_A_BACKUP, color = DroshError)
    } else if (state.inspected == null) {
        BackupNote(text = NOTE_RESTORE)
    }

    Spacer(Modifier.height(GROUP_GAP))

    ActionButton(
        text = ACTION_PICK,
        onClick = onPick,
        enabled = !state.busy,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
    )

    val inspected = state.inspected
    if (inspected != null) {
        Spacer(Modifier.height(GROUP_GAP))
        ArchiveManifestGroup(
            inspected = inspected,
            onToggleSection = onToggleSection,
        )

        Spacer(Modifier.height(GROUP_GAP))
        ActionButton(
            text = ACTION_RESTORE,
            onClick = { onRestore(inspected.file, RestoreSelection(inspected.selected)) },
            enabled = !state.busy,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(GROUP_GAP))
    RunStatus(state = state, failurePrefix = NOTE_RESTORE_FAILED)
}

/**
 * What the archive says about itself: when it was made, on which build,
 * whether the rootfs is in it, and what each section weighs.
 *
 * Read out of the manifest and nothing else — the preview never opens the
 * archive, because a preview that extracts is a preview that can fail on a
 * phone with no space left. The sizes are the manifest's own byte counts, which
 * is also why a truncated archive shows a size it does not have.
 *
 * The section rows carry switches because a restore does not have to be all of
 * it: a settings-only restore is the difference between unwinding one bad
 * theme and unwinding the whole phone.
 */
@Composable
private fun ArchiveManifestGroup(
    inspected: InspectedArchive,
    onToggleSection: (String) -> Unit,
) {
    val manifest = inspected.manifest
    val rows = 3 + manifest.sections.size

    SettingsGroupColumn(label = SECTION_ARCHIVE, items = rows) { index, cap ->
        when (index) {
            0 -> SettingsTile(
                cap = cap,
                icon = DroshIcons.Clock,
                title = ARCHIVE_DATE,
                supporting = formatTimestamp(manifest.createdAtEpochMs),
            )

            1 -> SettingsTile(
                cap = cap,
                icon = DroshIcons.Info,
                title = ARCHIVE_VERSION,
                supporting = "${manifest.appVersionName} (${manifest.appVersionCode})",
            )

            2 -> SettingsTile(
                cap = cap,
                icon = DroshIcons.SquareTerminal,
                title = ARCHIVE_ROOTFS,
                supporting = if (manifest.rootfsIncluded) INCLUDED else NOT_INCLUDED,
            )

            else -> {
                val section = manifest.sections[index - 3]
                // Unchecked is "not chosen", so an explicit selection that
                // lacks this id means off and an all-or-nothing one means on.
                val chosen = inspected.selected?.contains(section.id) != false
                SettingsTile(
                    cap = cap,
                    icon = DroshIcons.Package,
                    title = sectionLabel(section.kind),
                    supporting = formatBytes(section.byteSize),
                ) {
                    SettingsSwitch(
                        checked = chosen,
                        onCheckedChange = { onToggleSection(section.id) },
                    )
                }
            }
        }
    }
}

// ── what the job is doing ─────────────────────────────────────────────────

/**
 * The live half of a run: the step it is on, and how far along it is.
 *
 * The label names the step the repository is on, through [stepLabel]: a user
 * watching "Oturumlar ve sohbetler" knows the job is inside the part that
 * matters, and one watching "Lütfen bekleyin" learns nothing and checks again.
 *
 * Before the first step — [BackupProgress.Started] — there is no fraction to
 * show, so the bar is indeterminate rather than sitting at zero and reading as
 * a job that has not begun.
 *
 * Nothing here is drawn once the job is over: a completed backup's panel and a
 * failed one's reason are the halves' own business, and a row saying
 * "Hazırlanıyor…" next to a finished result is the one thing this screen must
 * never show.
 */
@Composable
private fun RunStatus(
    state: BackupRestoreUiState,
    failurePrefix: String,
) {
    // Read once, so the label and the bar cannot disagree about the event they
    // are both drawn from.
    val step = state.progress as? BackupProgress.Step

    if (state.running) {
        SettingsGroupColumn(label = SECTION_PROGRESS, items = 1) { _, cap ->
            SettingsTile(
                cap = cap,
                stacked = true,
                title = step?.let { stepLabel(it.step) } ?: PROGRESS_WORKING,
                supporting = step?.let { progress ->
                    "${(progress.fraction * 100).toInt().coerceIn(0, 100)}%"
                },
            ) {
                if (step != null) {
                    LinearProgressIndicator(
                        progress = { step.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = DroshPrimary,
                        trackColor = DroshTrack,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = DroshPrimary,
                        trackColor = DroshTrack,
                    )
                }
            }
        }
    }

    // The repository's reason, unedited: a rewritten error is a guess about
    // what went wrong, and the guess is what the user cannot act on.
    state.failure?.let { reason ->
        BackupNote(text = "$failurePrefix $reason", color = DroshError)
    }
}

/** A line of explanation beside a group, in the voice of the tiles. */
@Composable
private fun BackupNote(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = DroshTextMuted,
) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFamily = LocalFontSet.current.sans,
        modifier = modifier.padding(horizontal = 22.dp, vertical = 4.dp),
    )
}

/**
 * "Start Drosh again", and nothing else.
 *
 * The body says what a restart is *for*: the files are already back, and what a
 * restart does is make the running app read them. Without that sentence the
 * button reads as a fix for something that is broken.
 *
 * Confirming calls [ActivityCompat.recreate] on the activity this screen is in
 * — the same call the platform makes on a configuration change, so the process
 * and every singleton in it survive. What does not survive is whatever any
 * screen cached before the restore.
 */
@Composable
private fun RestartPrompt(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DroshSurfaceVariant,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = RESTART_TITLE,
                color = DroshText,
                fontFamily = LocalFontSet.current.sans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        },
        text = {
            Text(
                text = RESTART_BODY,
                color = DroshTextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontFamily = LocalFontSet.current.sans,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = RESTART_CONFIRM,
                    color = DroshPrimary,
                    fontFamily = LocalFontSet.current.sans,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = RESTART_DISMISS,
                    color = DroshTextSecondary,
                    fontFamily = LocalFontSet.current.sans,
                )
            }
        },
    )
}

// ── what the rows describe ────────────────────────────────────────────────

/** Whether a tier is going into the archive. */
private fun tierOn(options: BackupOptions, tier: BackupTier): Boolean = when (tier) {
    BackupTier.Settings -> options.includeSettings && options.includeThemesAndSnippets
    BackupTier.AppData -> options.includeDataStore && options.includeDatabase
    BackupTier.Rootfs -> options.includeRootfs
}

private fun tierTitle(tier: BackupTier): String = when (tier) {
    BackupTier.Settings -> TIER_SETTINGS
    BackupTier.AppData -> TIER_APP_DATA
    BackupTier.Rootfs -> TIER_ROOTFS
}

/**
 * What the tier holds, in the words the archive is made of.
 *
 * One sentence each, and the sentence names the thing rather than the tier:
 * "oturumlar, çalışma alanları, sohbetler" tells a user whether losing it would
 * hurt, and "uygulama verisi" does not.
 */
private fun tierDetail(tier: BackupTier): String = when (tier) {
    BackupTier.Settings -> TIER_SETTINGS_DETAIL
    BackupTier.AppData -> TIER_APP_DATA_DETAIL
    BackupTier.Rootfs -> TIER_ROOTFS_DETAIL
}

private fun tierIcon(tier: BackupTier): ImageVector = when (tier) {
    BackupTier.Settings -> DroshIcons.Settings
    BackupTier.AppData -> DroshIcons.Package
    BackupTier.Rootfs -> DroshIcons.SquareTerminal
}

/**
 * What a manifest section is, from the archive's own kind.
 *
 * Named for what a user would look for rather than for the file behind it: the
 * Room snapshot is a file, but what it carries is sessions and chats.
 */
private fun sectionLabel(kind: BackupSectionKind): String = when (kind) {
    BackupSectionKind.SETTINGS -> SECTION_SETTINGS
    BackupSectionKind.DATASTORE -> SECTION_DATASTORE
    BackupSectionKind.ROOM_DATABASE -> SECTION_DATABASE
    BackupSectionKind.THEMES -> SECTION_THEMES
    BackupSectionKind.SNIPPETS -> SECTION_SNIPPETS
    BackupSectionKind.INPUT_PREFS -> SECTION_INPUT_PREFS
    BackupSectionKind.ROOTFS -> SECTION_ROOTFS
}

/**
 * A byte count as something a person reads.
 *
 * Binary units, because a file's size on disk is what the user is about to
 * spend: `ls -lh` and the file manager both divide by 1024 and call the result
 * KB, and a backup screen claiming a different number from the rest of the
 * phone is a number nobody trusts.
 */
private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

/** When the archive was made, on the device it is being read on. */
private fun formatTimestamp(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

// ── the words on this screen ──────────────────────────────────────────────

/**
 * This screen's user-facing text, as constants beside it.
 *
 * Inline rather than in `drosh_strings.xml` because this slice owns no
 * resource file: the strings live with the composables that draw them, and
 * moving them would mean touching a file this change does not own. Everything
 * here is a name a translation pass would lift into the shared table whole —
 * one review of "Geri yükleme durdu: %s" rather than eight.
 */

private const val SCREEN_TITLE = "Yedekle ve geri yükle"

private const val TAB_TITLE = "İşlem"
private const val TAB_SUPPORTING = "Yedek al veya bir yedekten geri dön"
private const val TAB_BACKUP = "Yedekle"
private const val TAB_RESTORE = "Geri yükle"

private const val SECTION_TIERS = "Ne yedeklensin"
private const val SECTION_ARCHIVE = "Yedek içeriği"
private const val SECTION_PROGRESS = "İlerleme"
private const val SECTION_RESULT = "Sonuç"

private const val TIER_SETTINGS = "Ayarlar"
private const val TIER_SETTINGS_DETAIL = "Ayarlar dosyası, temalar ve kod parçaları"
private const val TIER_APP_DATA = "Uygulama verisi"
private const val TIER_APP_DATA_DETAIL = "Oturumlar, çalışma alanları, sohbetler ve SSH kayıtları"
private const val TIER_ROOTFS = "Rootfs (tam)"
private const val TIER_ROOTFS_DETAIL = "Linux dizininin tamamı — yedek büyür"

private const val NOTE_BACKUP = "Yedek hazır olduğunda dosyayı telefonuna kaydedebilirsin."
private const val NOTE_BACKUP_FAILED = "Yedekleme durdu:"
private const val NOTE_RESTORE = "Geri yükleme, seçtiğin bölümleri yedeğin içeriğiyle değiştirir."
private const val NOTE_RESTORE_FAILED = "Geri yükleme durdu:"
private const val NOTE_SAVED = "Kaydedildi:"

private const val NOT_A_BACKUP = "Bu dosya bir Drosh yedeği değil."

private const val ACTION_BACKUP = "Yedekle"
private const val ACTION_PICK = "Dosya seç"
private const val ACTION_RESTORE = "Geri yükle"
private const val ACTION_SAVE = "Kaydet/Paylaş"

private const val RESULT_BACKUP_TITLE = "Yedek hazır"
private const val RESULT_NO_PATH = "Arşiv hazır, dosya yolu okunamadı."

private const val ARCHIVE_DATE = "Oluşturma"
private const val ARCHIVE_VERSION = "Uygulama sürümü"
private const val ARCHIVE_ROOTFS = "Rootfs"
private const val INCLUDED = "Dahil"
private const val NOT_INCLUDED = "Yok"

private const val SECTION_SETTINGS = "Ayarlar"
private const val SECTION_DATASTORE = "Tercihler"
private const val SECTION_DATABASE = "Oturumlar ve sohbetler"
private const val SECTION_THEMES = "Temalar"
private const val SECTION_SNIPPETS = "Kod parçaları"
private const val SECTION_INPUT_PREFS = "Klavye tercihleri"
private const val SECTION_ROOTFS = "Rootfs"

private const val PROGRESS_WORKING = "Hazırlanıyor…"

private const val STEP_SETTINGS = "Ayarlar"
private const val STEP_APP_DATA = "Uygulama verisi"
private const val STEP_DATABASE = "Oturumlar ve sohbetler"
private const val STEP_THEMES = "Temalar"
private const val STEP_SNIPPETS = "Kod parçaları"
private const val STEP_INPUT_PREFS = "Klavye tercihleri"
private const val STEP_ROOTFS = "Linux dizini"
private const val STEP_MANIFEST = "Arşiv içeriği"
private const val STEP_VERIFYING = "Doğrulanıyor"

private const val RESTART_TITLE = "Drosh'u yeniden başlat"
private const val RESTART_BODY =
    "Geri yükleme tamamlandı. Değişikliklerin görülmesi için Drosh'u yeniden başlat."
private const val RESTART_CONFIRM = "Yeniden başlat"
private const val RESTART_DISMISS = "Sonra"
