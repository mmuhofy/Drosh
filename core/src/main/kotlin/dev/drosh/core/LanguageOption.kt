package dev.drosh.core

data class LanguageOption(
    val tag: String,
    val displayName: String,
    val nativeName: String,
)

object LanguageCatalog {
    val options: List<LanguageOption> = listOf(
        LanguageOption(tag = "", displayName = "System", nativeName = "System"),
        LanguageOption(tag = "en", displayName = "English", nativeName = "English"),
        LanguageOption(tag = "tr", displayName = "Turkish", nativeName = "Türkçe"),
        LanguageOption(tag = "de", displayName = "German", nativeName = "Deutsch"),
        LanguageOption(tag = "es", displayName = "Spanish", nativeName = "Español"),
        LanguageOption(tag = "fr", displayName = "French", nativeName = "Français"),
        LanguageOption(tag = "pt", displayName = "Portuguese", nativeName = "Português"),
        LanguageOption(tag = "ru", displayName = "Russian", nativeName = "Русский"),
        LanguageOption(tag = "zh", displayName = "Chinese", nativeName = "中文"),
        LanguageOption(tag = "ja", displayName = "Japanese", nativeName = "日本語"),
        LanguageOption(tag = "ar", displayName = "Arabic", nativeName = "العربية"),
        LanguageOption(tag = "fil", displayName = "Filipino", nativeName = "Filipino"),
    )
}
