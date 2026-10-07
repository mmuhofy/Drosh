package dev.drosh.ui.ssh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.drosh.domain.ssh.AuthMethod
import dev.drosh.domain.ssh.SshCredentialVault
import dev.drosh.domain.ssh.SshHost
import dev.drosh.domain.ssh.SshHostRepository
import dev.drosh.domain.ssh.SshSessionLauncher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * State for the SSH home screen: saved hosts, add-host form, connection state.
 */
@HiltViewModel
class SshHomeViewModel @Inject constructor(
    private val hosts: SshHostRepository,
    private val vault: SshCredentialVault,
    private val launcher: SshSessionLauncher,
) : ViewModel() {

    val hostList = hosts.observeAll()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _connecting = MutableStateFlow<String?>(null)
    val connecting = _connecting.asStateFlow()

    fun connect(hostId: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _connecting.value = hostId
            _message.value = null
            try {
                launcher.openSession(hostId)
                onSuccess()
            } catch (t: Throwable) {
                _message.value = t.message ?: "Connection failed"
            } finally {
                _connecting.value = null
            }
        }
    }

    fun addHost(
        name: String,
        hostname: String,
        port: Int,
        username: String,
        authMethod: AuthMethod,
        password: String?,
        privateKeyPem: String?,
        passphrase: String?,
    ) {
        viewModelScope.launch {
            val hostId = UUID.randomUUID().toString()
            val keyId = if (authMethod != AuthMethod.PASSWORD) UUID.randomUUID().toString() else null
            when (authMethod) {
                AuthMethod.PASSWORD -> password?.let { vault.setHostPassword(hostId, it) }
                AuthMethod.KEY, AuthMethod.KEY_WITH_PASSPHRASE -> privateKeyPem?.let {
                    vault.setPrivateKey(keyId!!, it, passphrase)
                }
            }
            hosts.add(
                SshHost(
                    id = hostId,
                    name = name,
                    hostname = hostname,
                    port = port,
                    username = username,
                    authMethod = authMethod,
                    keyId = keyId,
                    createdAtMs = System.currentTimeMillis(),
                    lastUsedAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun deleteHost(hostId: String) {
        viewModelScope.launch { hosts.delete(hostId) }
    }
}
