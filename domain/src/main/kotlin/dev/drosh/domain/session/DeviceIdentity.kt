package dev.drosh.domain.session

/**
 * Who and what the user is holding, for the drawer's identity row.
 *
 * Android exposes no marketing name: `Build.MODEL` is the internal codename,
 * so a Galaxy S24 Ultra reports `SM-S928B`. [marketingName] is the best answer
 * the app can give, resolved by the data layer and falling back to
 * manufacturer and model when the device is not in the lookup table.
 *
 * [visualUrl] is a cached link to a product image, or null when there is none
 * yet — either not fetched, or the lookup found nothing. Never a signal that
 * anything is wrong; the row draws a monogram in its place.
 */
data class DeviceIdentity(
    val marketingName: String,
    val manufacturer: String,
    val model: String,
    val visualUrl: String? = null,
) {
    /** The codename as the system reports it, for diagnostics and the model row. */
    val codename: String get() = model
}

/**
 * Resolves a device's marketing name and, on first run only, an image for it.
 *
 * Implementations must never block or throw: this is decorative, and the row is
 * perfectly usable with [DeviceIdentity.visualUrl] null.
 */
interface DeviceIdentityRepository {

    /** Human-readable device name, resolved from cache or the offline table. */
    suspend fun resolveName(): String

    /**
     * An image URL for [marketingName], or null.
     *
     * Looked up at most once per name and then cached permanently — the answer
     * for a model does not change, and re-asking is both rude and pointless.
     */
    suspend fun resolveVisual(marketingName: String): String?
}
