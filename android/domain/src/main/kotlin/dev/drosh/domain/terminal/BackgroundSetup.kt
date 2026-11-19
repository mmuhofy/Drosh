package dev.drosh.domain.terminal

/**
 * Names shared by the setup trigger in `:data` and the foreground service in
 * `:app`. Kept here so neither layer has to depend on the other, and so an
 * explicit cancel is the only thing that stops a background download.
 */
object BackgroundSetup {
    const val SERVICE_CLASS = "dev.drosh.SetupForegroundService"
    const val ACTION_START = "dev.drosh.action.SETUP_START"
    const val ACTION_CANCEL = "dev.drosh.action.SETUP_CANCEL"
}
