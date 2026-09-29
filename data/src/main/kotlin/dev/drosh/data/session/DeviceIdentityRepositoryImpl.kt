package dev.drosh.data.session

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import de.boehrsi.devicemarketingnames.DeviceMarketingNames
import dev.drosh.domain.session.DeviceIdentity
import dev.drosh.domain.session.DeviceIdentityRepository
import javax.inject.Inject
import javax.inject.Singleton
import android.os.Build

/**
 * Resolves the device's real name, offline.
 *
 * Three sources, best first:
 *
 *  1. `Settings.Global.DEVICE_NAME` — whatever the user typed into
 *     Settings › About phone › Device name. Free and needs no permission.
 *     Worth trying first, but it is often not what we want: AOSP's own
 *     `DeviceNamePreferenceController` falls back to `Build.MODEL` when the
 *     user never set one, so on an untouched device this returns the model and
 *     tells us nothing extra. Hence the check against `Build.MODEL` below.
 *
 *  2. An offline codename table, which turns `SM-S928B` into
 *     `Galaxy S24 Ultra`. No network, so it works in airplane mode and does
 *     not report which hardware the user is running to anyone.
 *
 *  3. Manufacturer and model, which is all the system ever had.
 *
 * No source is authoritative over the others in a way that matters for display,
 * so the result is cached by the caller rather than persisted here.
 */
@Singleton
class DeviceIdentityRepositoryImpl @Inject constructor(
    private val context: Context,
    private val visualRepository: WikidataDeviceVisualRepository,
) : DeviceIdentityRepository {

    private val applicationContext: Context get() = context.applicationContext

    private val manufacturer: String
        get() = runCatching { Build.MANUFACTURER }.getOrNull().orEmpty().trim()

    private val model: String
        get() = runCatching { Build.MODEL }.getOrNull().orEmpty().trim()

    private val userSetName: String
        get() = runCatching {
            Settings.Global.getString(
                applicationContext.contentResolver,
                Settings.Global.DEVICE_NAME,
            )
        }.getOrNull().orEmpty().trim()

    override suspend fun resolveName(): String {
        // A user-chosen name wins, unless it is just the model echoed back.
        val chosen = userSetName
        if (chosen.isNotEmpty() && !chosen.equals(model, ignoreCase = true)) {
            return chosen
        }

        val market = runCatching { DeviceMarketingNames.getSingleName() }
            .getOrNull()
            .orEmpty()
            .trim()

        if (market.isNotEmpty() && !market.equals(model, ignoreCase = true)) {
            return market
        }

        return listOf(manufacturer, model)
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
            .ifEmpty { "This device" }
    }

    override suspend fun resolveVisual(marketingName: String): String? =
        visualRepository.imageUrlFor(marketingName)

    /** The raw system values, for the secondary line under the name. */
    fun systemLabel(): String = listOf(manufacturer, model)
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .ifEmpty { "Unknown device" }

    fun toIdentity(marketingName: String, visualUrl: String?): DeviceIdentity = DeviceIdentity(
        marketingName = marketingName,
        manufacturer = manufacturer,
        model = model,
        visualUrl = visualUrl,
    )
}
