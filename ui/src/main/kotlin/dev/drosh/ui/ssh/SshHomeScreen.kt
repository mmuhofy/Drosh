package dev.drosh.ui.ssh

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.domain.ssh.AuthMethod
import dev.drosh.domain.ssh.SshHost
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.text.font.FontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshHomeScreen(
    onBack: () -> Unit,
    onConnected: () -> Unit,
    viewModel: SshHomeViewModel = hiltViewModel(),
) {
    val hosts by viewModel.hostList.collectAsStateWithLifecycle(initialValue = emptyList())
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "SSH", color = DroshText, fontSize = 20.sp, modifier = Modifier.weight(1f))
            Button(onClick = { showAdd = true }) { Text("Add") }
            Button(onClick = onBack) { Text("Back") }
        }

        message?.let { Text(text = it, color = DroshTextMuted, fontSize = 13.sp) }

        LazyColumn {
            items(hosts.size) { index ->
                val host = hosts[index]
                HostRow(
                    host = host,
                    connecting = connecting == host.id,
                    onClick = { viewModel.connect(host.id, onConnected) },
                    onDelete = { viewModel.deleteHost(host.id) },
                )
            }
        }
    }

    if (showAdd) {
        AddHostDialog(
            onDismiss = { showAdd = false },
            onSave = { name, hostname, port, username, authMethod, password, pem, passphrase ->
                viewModel.addHost(name, hostname, port, username, authMethod, password, pem, passphrase)
                showAdd = false
            },
        )
    }
}

@Composable
private fun HostRow(
    host: SshHost,
    connecting: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !connecting, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = host.name, color = DroshText, fontSize = 15.sp)
            Text(
                text = "${host.username}@${host.hostname}:${host.port}",
                color = DroshTextMuted,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (connecting) {
            Text(text = "…", color = DroshTextMuted)
        } else {
            Text(text = "Remove", color = DroshTextSecondary, modifier = Modifier.clickable { onDelete() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddHostDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, hostname: String, port: Int, username: String, authMethod: AuthMethod, password: String?, privateKeyPem: String?, passphrase: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var hostname by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }
    var authMethod by remember { mutableStateOf(AuthMethod.PASSWORD) }
    var password by remember { mutableStateOf("") }
    var pem by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = {
                onSave(name, hostname, port.toIntOrNull() ?: 22, username, authMethod, password.takeIf { it.isNotBlank() }, pem.takeIf { it.isNotBlank() }, passphrase.takeIf { it.isNotBlank() })
            }) { Text("Save") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add host") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                OutlinedTextField(value = hostname, onValueChange = { hostname = it }, label = { Text("Hostname or IP") })
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") })
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuthMethod.entries.forEach { method ->
                        Button(onClick = { authMethod = method }) { Text(method.name) }
                    }
                }
                when (authMethod) {
                    AuthMethod.PASSWORD -> OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") })
                    AuthMethod.KEY, AuthMethod.KEY_WITH_PASSPHRASE -> {
                        OutlinedTextField(value = pem, onValueChange = { pem = it }, label = { Text("Private key PEM") })
                        OutlinedTextField(value = passphrase, onValueChange = { passphrase = it }, label = { Text("Passphrase (optional)") })
                    }
                }
            }
        },
    )
}
