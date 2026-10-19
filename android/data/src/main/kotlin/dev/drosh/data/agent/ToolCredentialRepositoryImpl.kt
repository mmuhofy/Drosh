package dev.drosh.data.agent

import android.content.SharedPreferences
import dev.drosh.data.di.SecurityModule.SecretPref
import dev.drosh.domain.agent.ToolCredentialRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tool service credentials, in the same encrypted store as the model provider key.
 *
 * Prefixes each key with `tool_` rather than sharing the provider namespace, so
 * a provider called `exa` and a tool service called `exa` cannot collide, and so
 * `clearAll`-style sweeps can address one without the other.
 *
 * Nothing is read in the constructor: `EncryptedSharedPreferences` decrypts on
 * first access, and that cost would land on whichever thread built the singleton.
 */
@Singleton
class ToolCredentialRepositoryImpl @Inject constructor(
    @SecretPref private val prefs: SharedPreferences,
) : ToolCredentialRepository {

    private val configured = MutableStateFlow(readConfigured())

    override fun observeConfigured(): Flow<Set<String>> = configured.asStateFlow()

    override suspend fun credential(serviceId: String): String? = withContext(Dispatchers.IO) {
        prefs.getString(keyFor(serviceId), null)?.takeIf { it.isNotBlank() }
    }

    override suspend fun setCredential(serviceId: String, apiKey: String) =
        withContext(Dispatchers.IO) {
            val trimmed = apiKey.trim()
            if (trimmed.isEmpty()) {
                // Blank clears rather than storing "". A stored empty string reads
                // as "configured" in one place and "missing" in another, and the
                // settings screen's own indicator is derived from this.
                prefs.edit().remove(keyFor(serviceId)).apply()
            } else {
                prefs.edit().putString(keyFor(serviceId), trimmed).apply()
            }
            configured.value = readConfigured()
        }

    override suspend fun clearCredential(serviceId: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(keyFor(serviceId)).apply()
        configured.value = readConfigured()
    }

    /**
     * Every `tool_` key currently stored.
     *
     * Enumerated by prefix rather than kept as a hardcoded list, so a service
     * added later does not need a second edit here to show up as configured.
     */
    private fun readConfigured(): Set<String> = prefs.all.keys
        .filter { it.startsWith(PREFIX) }
        .map { it.removePrefix(PREFIX) }
        .toSet()

    private fun keyFor(serviceId: String) = "$PREFIX$serviceId"

    private companion object {
        const val PREFIX = "tool_"
    }
}
