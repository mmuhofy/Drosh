package dev.drosh.ui.overlay

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.drosh.domain.terminal.PaneSlot
import dev.drosh.domain.terminal.SystemOverlayController
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The [SystemOverlayController] that starts and stops [FloatingPaneOverlayService].
 *
 * Lives in `:app` because the service is declared in this module's manifest. `:ui`
 * only ever sees [SystemOverlayController], so nothing in the split screen has to
 * know a service exists.
 *
 * @Singleton because the attachment is a property of the window rather than of
 * whoever asked for it: two callers observing the same overlay must get the same
 * answer, and a per-injection copy would report an attachment the real window
 * disagrees with.
 */
@Singleton
class ServiceSystemOverlayController @Inject constructor(
    private val context: Context,
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
        if (!canDrawOverlays()) return false
        if (_isAttached.value) return false

        return runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, FloatingPaneOverlayService::class.java)
                    .putExtra(FloatingPaneOverlayView.EXTRA_SLOT, slot.name),
            )
            _isAttached.value = true
            // Explicit: an assignment is Unit, so letting the block end on one
            // would make runCatching<Unit> and getOrElse unreachable.
            true
        }.getOrElse {
            // startForegroundService throws when the app is in the background
            // without an exemption, and no window ever appears. Claiming success
            // would strand the pane: laid out as an overlay, with nothing to show
            // it and no way to reach it.
            _isAttached.value = false
            false
        }
    }

    override fun detach(): Boolean {
        if (!_isAttached.value) return false
        _isAttached.value = false
        // stopService rather than the notification's stop action: the pane has to
        // come back into this app's window, and that is driven by the layout
        // changing rather than by the service noticing it was dismissed.
        runCatching {
            context.stopService(Intent(context, FloatingPaneOverlayService::class.java))
        }
        return true
    }
}
