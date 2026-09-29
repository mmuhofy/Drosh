package dev.drosh.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.drosh.core.localizedContext
import java.util.Locale

/**
 * Language selection that takes effect immediately.
 *
 * The previous approach called `Activity.recreate()`, which is the standard
 * trick and also a full teardown: every screen loses its scroll, every
 * `remember` is thrown away, and the switch takes long enough to read as a
 * stutter. Worse, the language only appeared after the recreate finished, so
 * tapping a language looked like nothing had happened.
 *
 * Here the locale rides in the composition instead. A localized context is
 * derived once per selection and provided through [LocalLocaleContext];
 * `stringResource` and `painterResource` both read the ambient context, so
 * every string on screen re-resolves on the next frame. The activity is not
 * recreated and no state is lost.
 */
/**
 * The hosting activity.
 *
 * Provided separately from [LocalContext] because [ProvideLocale] replaces the
 * context with a localized wrapper, and anything casting the ambient context
 * to an Activity would break on that wrapper.
 */
val LocalDroshActivity = compositionLocalOf<Activity> {
    error("No activity provided; wrap the tree in the activity's setContent.")
}

/**
 * Applies [languageTag] to the subtree. An empty tag means "follow the system",
 * where the activity's own context is already correct and is used unchanged.
 */
@Composable
fun ProvideLocale(languageTag: String, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val localized = remember(base, languageTag) {
        if (languageTag.isBlank()) {
            base
        } else {
            val locale = Locale.forLanguageTag(languageTag)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
            base.createConfigurationContext(config)
        }
    }
    // LocalContext itself is overridden, not a custom local: stringResource and
    // painterResource both read the ambient context, so replacing it is what
    // makes every string on screen re-resolve on the next frame.
    CompositionLocalProvider(LocalContext provides localized, content = content)
}

