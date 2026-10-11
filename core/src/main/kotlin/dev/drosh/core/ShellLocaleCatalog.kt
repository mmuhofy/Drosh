package dev.drosh.core

/**
 * The locales the shell can be asked to speak.
 *
 * A short list on purpose: a terminal user can set anything they like
 * themselves, and the picker exists for the common cases — the system
 * default, plain C, and the locales Drosh's own UI ships. A generated
 * list of every glibc locale would be a thousand entries nobody
 * scrolls.
 */
data class ShellLocale(
    val tag: String,
    val displayName: String,
)

object ShellLocaleCatalog {

    /** No LANG/LC_ALL at all — the image's own default. */
    val SYSTEM = ShellLocale("", "System")

    val options: List<ShellLocale> = listOf(
        SYSTEM,
        ShellLocale("C", "C (POSIX)"),
        ShellLocale("C.UTF-8", "C.UTF-8"),
        ShellLocale("en_US.UTF-8", "English (US)"),
        ShellLocale("en_GB.UTF-8", "English (UK)"),
        ShellLocale("tr_TR.UTF-8", "Türkçe (TR)"),
        ShellLocale("de_DE.UTF-8", "Deutsch (DE)"),
        ShellLocale("es_ES.UTF-8", "Español (ES)"),
        ShellLocale("pt_BR.UTF-8", "Português (BR)"),
        ShellLocale("ru_RU.UTF-8", "Русский (RU)"),
        ShellLocale("zh_CN.UTF-8", "中文 (CN)"),
        ShellLocale("ja_JP.UTF-8", "日本語 (JP)"),
        ShellLocale("ar_SA.UTF-8", "العربية (SA)"),
        ShellLocale("fil_PH.UTF-8", "Filipino (PH)"),
    )

    fun displayName(tag: String): String =
        options.firstOrNull { it.tag == tag }?.displayName ?: tag
}
