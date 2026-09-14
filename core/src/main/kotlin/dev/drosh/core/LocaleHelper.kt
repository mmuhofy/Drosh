package dev.drosh.core

import android.content.Context
import androidx.core.os.ConfigurationCompat
import java.util.Locale

object LocaleHelper {
    fun applyLocale(context: Context, language: String): Context {
        if (language.isBlank()) return context
        val locale = Locale.forLanguageTag(language)
        Locale.setDefault(locale)
        val config = context.resources.configuration
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    val Context.currentLanguageTag: String
        get() = ConfigurationCompat.getLocales(resources.configuration)
            .takeUnless { it.isEmpty }
            ?.get(0)
            ?.toLanguageTag() ?: ""
}
