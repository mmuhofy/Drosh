/*
 * Copyright (C) 2026 Drosh contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package dev.drosh.editor

import android.content.Context
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import timber.log.Timber

/**
 * Loads the TextMate grammars and Drosh's theme.
 *
 * ### Idempotent, and load-once by design
 *
 * Both registries are process-wide singletons, so this runs once per process and
 * every later call is a no-op. That matters: parsing eleven grammars costs
 * hundreds of milliseconds and would be paid again on every editor screen if
 * this were per-instance.
 *
 * ### Total, by design
 *
 * Everything returns a null/empty result instead of throwing. A grammar that
 * fails to parse costs one file its colours, not the editor its text — the user
 * would rather edit an unhighlighted file than not edit it. tm4e's registries
 * are also known to throw on a malformed grammar, so the whole load is wrapped.
 *
 * ### What is bundled, and under what licence
 *
 * Grammars are TextMate definitions taken from the MIT-licensed microsoft/vscode
 * repository at a pinned commit (except Kotlin, from the MIT-licensed
 * fwcd/vscode-kotlin). See `textmate/PROVENANCE.md`.
 */
object TextMateSetup {

    private const val GRAMMAR_INDEX = "textmate/languages.json"
    private const val THEME = "textmate/drosh-dark.json"
    private const val THEME_NAME = "drosh-dark"

    @Volatile
    private var loaded = false

    /** True once the grammars and theme are in the registries. */
    val isReady: Boolean get() = loaded

    /**
     * Loads grammars and theme, once.
     *
     * @return true when highlighting is available, false when the editor should
     *   fall back to plain text.
     */
    @Synchronized
    fun ensure(context: Context): Boolean {
        if (loaded) return true

        val appContext = context.applicationContext
        return try {
            // AssetsFileResolver needs the application context: it is stored in a
            // singleton that outlives any screen.
            FileProviderRegistry.getInstance().addFileProvider(
                AssetsFileResolver(appContext.assets),
            )

            ThemeRegistry.getInstance().loadTheme(
                ThemeModel(
                    IThemeSourceOf(appContext, THEME),
                    THEME_NAME,
                ),
            )
            // setTheme, not just loadTheme: TextMate colors come from whichever
            // theme is *selected*, and a loaded-but-unselected theme leaves
            // every span transparent — the editor looks like plain white-on-black
            // with no error to show for it.
            ThemeRegistry.getInstance().setTheme(THEME_NAME)

            GrammarRegistry.getInstance().loadGrammars(GRAMMAR_INDEX)

            loaded = true
            true
        } catch (e: Throwable) {
            // Throwable, not Exception: tm4e reaches into generated parsing code
            // and a grammar defect surfaces as NoClassDefFoundError or
            // ArrayIndexOutOfBoundsException depending on the input.
            Timber.e(e, "textmate: load failed; the editor will stay plain text")
            false
        }
    }

    /**
     * Wraps an asset as a theme source.
     *
     * A named class rather than inlined, because the constructor takes an
     * InputStream and the stream has to stay open until [ThemeModel.load]
     * reads it — the call order inside [ensure] depends on that.
     */
    private class IThemeSourceOf(context: Context, path: String) :
        org.eclipse.tm4e.core.registry.IThemeSource {

        private val resolver = FileProviderRegistry.getInstance()
        private val path = path

        init {
            // Touching the stream early surfaces a missing asset here rather
            // than as a null theme later.
            resolver.tryGetInputStream(path)?.close()
        }

        override fun getInputStream(): java.io.InputStream =
            FileProviderRegistry.getInstance().tryGetInputStream(path)
                ?: throw java.io.FileNotFoundException(path)

        override fun getFileReference(): String? = path
    }
}