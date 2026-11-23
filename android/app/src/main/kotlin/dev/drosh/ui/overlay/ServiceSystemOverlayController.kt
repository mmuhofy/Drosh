package dev.drosh.ui.overlay

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.drosh.domain.terminal.PaneSlot
import dev.drosh.domain.terminal.SystemOverlayController
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The [SystemOverlayController] for the in-app floating pane.
 *
 * The system overlay service was removed when engine panes became in-app only;
 * [attach] therefore always returns false. This binding stays because `:ui`
 * depends only on [SystemOverlayController] and must not learn that a service
 * ever existed.
 *
 * @Singleton because the attachment is a property of the window rather than of
 * whoever asked for it: two callers observing the same overlay must get the same
 * answer, and a per-injection copy would report an attachment the real window
 * disagrees with.
 */
@Singleton
class ServiceSystemOverlayController @Inject constructor(
    // Qualified because the unqualified Context binding only exists in the
    // component of an Activity or Service. This one is in the SingletonComponent,
    // so without the qualifier Dagger reports no binding — and the failure appears
    // at Hilt's Java compile step, a long way from this constructor.
    @ApplicationContext private val context: Context,
) : SystemOverlayController {

    private val _isAttached = MutableStateFlow(false)

    override val isAttached: StateFlow<Boolean> = _isAttached.asStateFlow()

    /**
     * Checked per call rather than cached from startup: the user can revoke
     * "display over other apps" while the app is in the background, and a value
     * cached at startup would be wrong for the rest of the process's life.
     */
    override fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    override fun attach(slot: PaneSlot): Boolean {
        // Floating pane overlay removed — engine panes are in-app only.
        return false
    }

    override fun detach(): Boolean {
        if (!_isAttached.value) return false
        _isAttached.value = false
        return true
    }
}
