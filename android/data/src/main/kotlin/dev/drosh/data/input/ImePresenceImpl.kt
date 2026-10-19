package dev.drosh.data.input

import android.content.Context
import android.provider.Settings
import dev.drosh.domain.input.ImePresence
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads `Settings.Secure.DEFAULT_INPUT_METHOD`, whose value has the form
 * `package/service` (e.g. `dev.drosh.ime/dev.drosh.ime.latin.LatinIME`),
 * and matches the Drosh Keyboard package.
 *
 * UNTESTED — verify on device.
 */
@Singleton
class ImePresenceImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ImePresence {

    private val state = MutableStateFlow(queryActiveIme())

    override val droshKeyboardActive: Flow<Boolean> = state.asStateFlow()

    override fun refresh() {
        state.value = queryActiveIme()
    }

    private fun queryActiveIme(): Boolean {
        val current = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
            )
        } catch (_: Exception) {
            null
        } ?: return false
        // The id is "package/service". Accept the release package and the
        // debug variant (applicationIdSuffix ".debug") plus any future
        // suffix build — never a different top-level package.
        val pkg = current.substringBefore('/')
        return pkg == DROSH_KEYBOARD_PACKAGE || pkg.startsWith("$DROSH_KEYBOARD_PACKAGE.")
    }

    private companion object {
        const val DROSH_KEYBOARD_PACKAGE = "dev.drosh.ime"
    }
}
