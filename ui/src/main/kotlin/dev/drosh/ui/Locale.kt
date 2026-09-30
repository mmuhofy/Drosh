package dev.drosh.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.LocalResources
import androidx.compose.ui.platform.LocalContext
import dev.drosh.core.localizedContext
import java.util.Locale

/**
 * Language selection that takes effect immediately.
 *
 * The previous approach called `Activity.recreate()`: a full teardown, so
 * every screen loses its scroll, every `remember` is discarded, and the new
 * language only appeared once it finished — tapping a language looked like
 * nothing had happened.
 *
 * The locale now rides in the composition. [LocalResources] is overridden
 * with the localized resources, which is what `stringResource` resolves
 * against, so every string re-resolves on the next frame with no state lost.
 *
 * [LocalContext] is deliberately left alone. An earlier version replaced it
 * with the localized context instead, which reads well and crashes on the
 * first `hiltViewModel()` in the tree: `createConfigurationContext` returns
 * a plain ContextImpl, and Hilt needs an Activity to build a ViewModel
 * factory. Resources carry the language; the context stays the activity.
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
    // LocalResources, not LocalContext: this is what stringResource reads, and
    // replacing the context instead breaks every hiltViewModel() below.
    CompositionLocalProvider(
        LocalResources provides localized.resources,
        LocalConfiguration provides localized.resources.configuration,
        content = content,
    )
}

