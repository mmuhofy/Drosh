package dev.drosh.ui.updates

import android.app.Application
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

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
    val context = LocalContext.current
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
    val latest by viewModel.latest.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val pendingRestart by viewModel.pendingRestart.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.check() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Updates & Version Management", color = Color.White)
        Text(text = status, color = Color(0xFFB0FFB0), fontSize = 12.sp)

        if (pendingRestart) {
            AlertDialog(
                onDismissRequest = { viewModel.clearPendingRestart() },
                title = { Text("Restart needed") },
                text = { Text("The VT engine was updated. Restart the app now to use it, or apply it on the next start.") },
                confirmButton = {
                    Button(onClick = { android.os.Process.killProcess(android.os.Process.myPid()) }) {
                        Text("Restart now")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.clearPendingRestart() }) { Text("Later") }
                },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { viewModel.setChannel(UpdateChannel.RELEASE); viewModel.check() }) {
                Text("release", color = if (channel == UpdateChannel.RELEASE) Color(0xFFFFD070) else Color.White)
            }
            TextButton(onClick = { viewModel.setChannel(UpdateChannel.NIGHTLY); viewModel.check() }) {
                Text("nightly", color = if (channel == UpdateChannel.NIGHTLY) Color(0xFFFFD070) else Color.White)
            }
        }

        ComponentId.entries.forEach { id ->
            val release = latest[id]
            val installed = viewModel.installed(id)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1A1A))
                    .padding(12.dp),
            ) {
                Text(text = id.key, color = Color.White)
                Text(
                    text = "installed: ${installed?.version ?: "bundled"} " +
                        "(${installed?.source?.name?.lowercase() ?: "bundled"})",
                    color = Color(0xFFB0FFB0),
                    fontSize = 11.sp,
                )
                Text(
                    text = "available: ${release?.version ?: "none"}",
                    color = Color(0xFFB0FFB0),
                    fontSize = 11.sp,
                )
                if (release != null && release.version != installed?.version) {
                    Button(
                        onClick = { viewModel.downloadAndInstall(id) },
                        enabled = busy == null,
                    ) { Text(if (busy == id) "working…" else "download & install") }
                }
                if (installed != null && installed.source == InstallSource.DOWNLOADED) {
                    OutlinedButton(
                        onClick = { viewModel.rollback(id) },
                        enabled = busy == null,
                    ) { Text("rollback to bundled") }
                }
            }
        }

        TextButton(onClick = onBack) { Text("back", color = Color.White) }
    }
}
