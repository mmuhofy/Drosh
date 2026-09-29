package dev.drosh.ui.session

import android.content.res.Configuration
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.drosh.ui.DroshIcons

/**
 * Who and what the user is holding, for the drawer's identity row.
 *
 * Android has no API for a device's marketing name. `Build.MODEL` is the
 * internal codename — a Galaxy S24 Ultra reports `SM-S928B`, not
 * "Galaxy S24 Ultra" — and there is no lookup table bundled with the
 * platform. So this shows what the system actually knows, tidied up, and
 * leaves the visual as a form-factor glyph.
 *
 * The [visual] slot is where a real product photo would go. That needs a
 * marketing-name to image service, network access and a cache, none of
 * which this app has today; wiring one in later means replacing
 * [deviceVisualFor] and leaving everything else alone.
 */
data class DeviceIdentity(
    val manufacturer: String,
    val model: String,
    val formFactor: FormFactor,
) {
    /** What the row shows next to the glyph. */
    val label: String
        get() = when {
            manufacturer.isBlank() -> model
            model.isBlank() -> manufacturer
            else -> "$manufacturer · $model"
        }

    enum class FormFactor { Phone, Tablet, Other }
}

/**
 * Reads the identity. Cheap and stable, so it is computed once per
 * composition rather than on every recomposition.
 */
@Composable
fun rememberDeviceIdentity(): DeviceIdentity {
    val config = LocalConfiguration.current
    return remember(config) { readDeviceIdentity(config) }
}

private fun readDeviceIdentity(config: Configuration): DeviceIdentity {
    // Tapping Build from a non-main thread is fine; this runs on the main one
    // anyway, but the fields are read defensively so a device that has not
    // finished initialising cannot crash the drawer.
    val manufacturer = runCatching { Build.MANUFACTURER }.getOrNull()
        .orEmpty()
        .trim()
        .replaceFirstChar { it.uppercase() }

    val model = runCatching { Build.MODEL }.getOrNull().orEmpty().trim()

    val factor = when {
        config.smallestScreenWidthDp >= 600 -> DeviceIdentity.FormFactor.Tablet
        else -> DeviceIdentity.FormFactor.Phone
    }

    return DeviceIdentity(manufacturer, model, factor)
}

@Composable
fun deviceVisualFor(factor: DeviceIdentity.FormFactor): ImageVector = when (factor) {
    DeviceIdentity.FormFactor.Phone -> DroshIcons.Smartphone
    DeviceIdentity.FormFactor.Tablet -> DroshIcons.Tablet
    DeviceIdentity.FormFactor.Other -> DroshIcons.Monitor
}
