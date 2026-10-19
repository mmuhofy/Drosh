package dev.drosh.ui.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.core.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The name Android's save dialog offers for a new archive. */
private const val BACKUP_FILE_NAME = "drosh-backup.tar.gz"

/** A gzipped tar, which is what the repository writes. */
private const val BACKUP_MIME = "application/gzip"

/** Every type: a file manager calls a .tar.gz half a dozen different things. */
private val ANY_MIME = arrayOf("*/*")

/** Where the picked archive is copied to, under `cacheDir`. */
private const val BACKUP_CACHE_DIR = "restore"

/** The name the picked archive is given in the cache. */
private const val PICKED_FILE_NAME = "picked-backup.tar.gz"

private const val SAVE_FAILED = "Yedek kaydedilemedi."
private const val READ_FAILED = "Dosya okunamadı."

/**
 * The backup screen, with the two system pickers it needs.
 *
 * ## Why the pickers are here and not in the screen
 *
 * A backup is written to a file the repository owns in app cache — a place
 * Android is free to clear. The only way it becomes the user's is through the
 * Storage Access Framework: a `CreateDocument` that hands a `Uri` the app may
 * write once. Symmetrically, a restore starts from a `Uri` the app may read
 * once, which has to be copied into the cache before anything can open it as a
 * `File`. Both of those are permissions to do a thing, and a permission
 * granted to a launcher registered in a composition is lost the moment the
 * composition is. Keeping them here, above the screen, is what lets the screen
 * be recomposed, rotated or replaced without a job losing its file.
 *
 * ## What the route knows and does not
 *
 * It knows where bytes go and nothing about backup. It does not decide tiers,
 * read manifests or interpret progress: it moves a file and tells the view
 * model what it did. Everything above this line is `BackupRestoreScreen`
 * rendering state; everything here is two callbacks and a coroutine.
 */
@Composable
fun BackupRestoreRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupRestoreViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // The file the save sheet is writing, held because its answer arrives in a
    // callback the composition does not re-run for — reading the state there
    // would be reading whatever the screen happens to be showing now.
    var savingPath by remember { mutableStateOf<String?>(null) }

    // Which archive the save sheet has already been offered, saved across a
    // rotation. Without it, turning the phone while the sheet is up would
    // offer the same file again the moment the composition came back.
    var offeredPath by rememberSaveable { mutableStateOf<String?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(BACKUP_MIME),
    ) { uri ->
        // The in-memory path, or the view model's: after a process death the
        // sheet comes back and `remember` does not, but the archive is still
        // waiting in the state it was left in.
        val source = (savingPath ?: state.pendingSave)?.let(::File)
        savingPath = null
        // A dismissed sheet is not a failure and is not reported: the archive
        // is still in cache and the button on the screen opens the sheet again.
        // Only a write that failed is worth saying.
        if (uri == null || source == null) return@rememberLauncherForActivityResult
        scope.launch {
            val written = writeArchive(context.contentResolver, source, uri)
            if (written) {
                // The user's own answer is where the bytes went. The path in
                // app cache is of no use to them and is dropped here.
                viewModel.onBackupFileReady(uri.toString())
            } else {
                context.toast(SAVE_FAILED)
            }
        }
    }

    val pickLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val copied = copyToCache(context, uri)
            if (copied == null) {
                context.toast(READ_FAILED)
            } else {
                viewModel.inspect(copied)
            }
        }
    }

    // The archive is ready and has nowhere to live yet, so the save sheet is
    // offered once for it. The screen's own button stays for the rest of the
    // archive's life — a dismissed sheet is a user who changed their mind, not
    // a user who is done.
    LaunchedEffect(state.pendingSave) {
        val pending = state.pendingSave ?: return@LaunchedEffect
        if (offeredPath == pending) return@LaunchedEffect
        offeredPath = pending
        savingPath = pending
        saveLauncher.launch(BACKUP_FILE_NAME)
    }

    BackupRestoreScreen(
        onBack = onBack,
        modifier = modifier,
        viewModel = viewModel,
        onSaveBackup = { path ->
            savingPath = path
            saveLauncher.launch(BACKUP_FILE_NAME)
        },
        onPickArchive = { pickLauncher.launch(ANY_MIME) },
    )
}

/**
 * Copies the produced archive into the file the user chose.
 *
 * Streamed rather than read whole: a rootfs backup is hundreds of megabytes and
 * a phone has neither the heap nor a reason to hold one. The output stream is
 * truncated by `openOutputStream`, so a file that already exists is overwritten
 * rather than appended to.
 *
 * @return false when either side could not be opened, which is the whole
 *   failure mode of a write the platform has already approved.
 */
private suspend fun writeArchive(
    resolver: ContentResolver,
    source: File,
    target: Uri,
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        resolver.openOutputStream(target)?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        } != null
    }.getOrDefault(false)
}

/**
 * Copies the picked archive into this app's cache, under a fixed name.
 *
 * The read permission a picker grants lasts as long as the process, and
 * [BackupRestoreViewModel.inspect] takes a `File` — the repository has no
 * `Uri` variant, because nothing else in the app reads through one. So the
 * bytes are copied first and the copy is what gets inspected; a second pick of
 * the same archive finds the same cache file and overwrites it, which is why
 * the file is never left behind between picks.
 *
 * @return the copy, or null when the content provider refused the read.
 */
private suspend fun copyToCache(context: Context, source: Uri): File? =
    withContext(Dispatchers.IO) {
        runCatching {
            val cache = File(context.cacheDir, BACKUP_CACHE_DIR).apply { mkdirs() }
            val target = File(cache, PICKED_FILE_NAME)
            context.contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching null
            target
        }.getOrNull()
    }
