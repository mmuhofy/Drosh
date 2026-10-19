package dev.drosh.ui.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.drosh.domain.session.DeviceIdentity
import dev.drosh.domain.session.DeviceIdentityRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The device the app is running on, for the drawer's identity row.
 *
 * Resolved off the composition and never on the critical path: the row
 * renders a monogram until this has an answer, and a slow or failing lookup
 * leaves it that way rather than holding the drawer open.
 */
@HiltViewModel
class DeviceIdentityViewModel @Inject constructor(
    private val repository: DeviceIdentityRepository,
) : ViewModel() {

    private val _identity = MutableStateFlow<DeviceIdentity?>(null)
    val identity: StateFlow<DeviceIdentity?> = _identity.asStateFlow()

    init {
        viewModelScope.launch {
            val name = repository.resolveName()
            // The visual is asked for once and cached on disk by the repository,
            // so this is a no-op on every launch after the first.
            val visual = repository.resolveVisual(name)
            _identity.value = DeviceIdentity(
                marketingName = name,
                manufacturer = manufacturerOf(),
                model = modelOf(),
                visualUrl = visual,
            )
        }
    }

    private fun manufacturerOf(): String =
        runCatching { android.os.Build.MANUFACTURER }.getOrNull().orEmpty().trim()

    private fun modelOf(): String =
        runCatching { android.os.Build.MODEL }.getOrNull().orEmpty().trim()
}
