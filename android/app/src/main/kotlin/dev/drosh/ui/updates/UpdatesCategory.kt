package dev.drosh.ui.updates

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.drosh.terminal.engine.update.ApkRepository
import dev.drosh.terminal.engine.update.ChrootNgRepository
import dev.drosh.terminal.engine.update.ComponentId
import dev.drosh.terminal.engine.update.ComponentLoader
import dev.drosh.terminal.engine.update.ComponentRelease
import dev.drosh.terminal.engine.update.ComponentStore
import dev.drosh.terminal.engine.update.InstallSource
import dev.drosh.terminal.engine.update.UpdateChannel
import dev.drosh.terminal.engine.update.UpstreamVtRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class UpdatesViewModel @Inject constructor(
    application: Application,
) : ViewModel() {
    private val context = application.applicationContext
    private val store = ComponentStore(context)
    private val repositories = mapOf(
        ComponentId.GHOSTTY_VT to UpstreamVtRepository(),
        ComponentId.CHROOT_NG to ChrootNgRepository(),
        ComponentId.APK to ApkRepository(),
    )

    private val prefs = context.getSharedPreferences("drosh_updates", Context.MODE_PRIVATE)

    private val _channel = MutableStateFlow(
        UpdateChannel.fromKey(prefs.getString("channel", "release") ?: "release"),
    )
    val channel: StateFlow<UpdateChannel> = _channel.asStateFlow()

    private val _latest = MutableStateFlow<Map<ComponentId, ComponentRelease?>>(emptyMap())
    val latest: StateFlow<Map<ComponentId, ComponentRelease?>> = _latest.asStateFlow()

    private val _busy = MutableStateFlow<ComponentId?>(null)
    val busy: StateFlow<ComponentId?> = _busy.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _pendingRestart = MutableStateFlow(false)
    val pendingRestart: StateFlow<Boolean> = _pendingRestart.asStateFlow()

    fun clearPendingRestart() { _pendingRestart.value = false }

    private val _installApk = MutableSharedFlow<java.io.File>(extraBufferCapacity = 1)
    val installApk: SharedFlow<java.io.File> = _installApk.asSharedFlow()

    fun setChannel(channel: UpdateChannel) {
        _channel.value = channel
        prefs.edit().putString("channel", channel.key).apply()
    }

    fun check() {
        viewModelScope.launch {
            _status.value = "checking…"
            val abi = ComponentLoader.abi()
            val result = withContext(Dispatchers.IO) {
                repositories.mapValues { (_, repo) ->
                    if (!repo.supports(_channel.value)) null else repo.latest(_channel.value, abi)
                }
            }
            _latest.value = result
            _status.value = "done"
        }
    }

    fun downloadAndInstall(id: ComponentId) {
        val release = _latest.value[id] ?: return
        val repo = repositories[id] ?: return
        viewModelScope.launch {
            _busy.value = id
            _status.value = "downloading ${id.key}…"
            val bytes = withContext(Dispatchers.IO) { repo.download(release) }
            if (bytes == null) {
                _status.value = "download failed"
                _busy.value = null
                return@launch
            }
            if (id == ComponentId.APK) {
                // The APK carries Rust and Kotlin together: it is installed by
                // the platform, not staged into a files directory.
                val actual = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes).joinToString("") { "%02x".format(it) }
                if (actual != release.sha256.lowercase()) {
                    _status.value = "checksum mismatch"
                    _busy.value = null
                    return@launch
                }
                val file = withContext(Dispatchers.IO) {
                    val dir = java.io.File(context.cacheDir, "apk")
                    if (!dir.isDirectory) dir.mkdirs()
                    val target = java.io.File(dir, "drosh-update.apk")
                    target.writeBytes(bytes)
                    target
                }
                _status.value = "launching installer…"
                _installApk.tryEmit(file)
                _busy.value = null
                return@launch
            }
            val installed = withContext(Dispatchers.IO) { store.install(release, bytes) }
            if (installed == null) {
                _status.value = "install failed (checksum)"
                _busy.value = null
                return@launch
            }
            val activated = withContext(Dispatchers.IO) { store.activate(id, installed) }
            _status.value = if (activated) "installed ${release.version} — restart to apply" else "activation failed"
            if (activated && id == ComponentId.GHOSTTY_VT) _pendingRestart.value = true
            _busy.value = null
            check()
        }
    }

    fun rollback(id: ComponentId) {
        viewModelScope.launch {
            _busy.value = id
            withContext(Dispatchers.IO) { store.rollback(id) }
            _status.value = "rolled back to bundled"
            _busy.value = null
            check()
        }
    }

    fun installed(id: ComponentId): dev.drosh.terminal.engine.update.InstalledState? =
        store.installed(id)
}

@Composable
fun UpdatesCategory(
    onBack: () -> Unit,
    viewModel: UpdatesViewModel = androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel(),
) {
    val channel by viewModel.channel.collectAsStateWithLifecycle()
    val latest by viewModel.latest.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val pendingRestart by viewModel.pendingRestart.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // An APK cannot be staged the way an engine can: the platform owns the
    // install, so the download leaves through the system's installer.
    LaunchedEffect(Unit) {
        viewModel.installApk.collect { file ->
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file,
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    LaunchedEffect(Unit) { viewModel.check() }

    // The words come from the UI module's resources; the ids stay here, where
    // the engine's own types are.
    val bundled = context.getString(dev.drosh.ui.R.string.updates_bundled)
    val rows = ComponentId.entries.map { id ->
        val installed = viewModel.installed(id)
        val release = latest[id]
        dev.drosh.ui.settings.UpdateUiRow(
            id = id.name,
            title = componentTitle(id),
            installed = installed?.let { "${it.version} · ${it.source.name.lowercase()}" } ?: bundled,
            available = release?.version,
            canInstall = release != null && release.version != installed?.version,
            canRollback = installed?.source == InstallSource.DOWNLOADED,
        )
    }

    dev.drosh.ui.settings.UpdatesScreen(
        onBack = onBack,
        state = dev.drosh.ui.settings.UpdatesUiState(
            channel = channel.key,
            status = status,
            busyId = busy?.name,
            rows = rows,
        ),
        onChannel = { key ->
            viewModel.setChannel(UpdateChannel.fromKey(key))
            viewModel.check()
        },
        onCheck = { viewModel.check() },
        onInstall = { name -> viewModel.downloadAndInstall(ComponentId.valueOf(name)) },
        onRollback = { name -> viewModel.rollback(ComponentId.valueOf(name)) },
        restartPending = pendingRestart,
        onRestart = { android.os.Process.killProcess(android.os.Process.myPid()) },
        onLater = { viewModel.clearPendingRestart() },
    )
}

/** The component's name as a person would say it. */
private fun componentTitle(id: ComponentId): String = when (id) {
    ComponentId.GHOSTTY_VT -> "Ghostty VT engine"
    ComponentId.CHROOT_NG -> "Guest runtime"
    ComponentId.APK -> "Drosh app"
}
