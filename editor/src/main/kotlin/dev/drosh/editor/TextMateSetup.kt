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
import org.eclipse.tm4e.core.registry.IThemeSource
import timber.log.Timber
import java.nio.charset.StandardCharsets

/**
 * Loads the bundled TextMate grammars and Drosh's colour theme.
 *
 * ### Once per process
 *
 * Both registries are process-wide singletons and parsing eleven grammars costs
 * real time, so this runs once and later calls are free. Without the guard, every
 * time the editor screen opened it would re-parse the whole set.
 *
 * ### Total on purpose
 *
 * Returns false rather than throwing. A grammar that fails to parse should cost
 * one file its colours, not the user their editor — an unhighlighted file that
 * saves correctly beats a crash. `Throwable` and not `Exception` because tm4e
 * parses generated code and a malformed grammar surfaces as
 * `NoClassDefFoundError` or an index exception depending on where it gives up.
 *
 * ### Bundled grammars
 *
 * TextMate definitions from the MIT-licensed microsoft/vscode at a pinned
 * commit, plus Kotlin from the MIT-licensed fwcd/vscode-kotlin. Provenance and
 * licences: `assets/textmate/PROVENANCE.md`.
 */
object TextMateSetup {

    private const val GRAMMAR_INDEX = "textmate/languages.json"
    private const val THEME_ASSET = "textmate/drosh-dark.json"
    private const val THEME_NAME = "drosh-dark"

    /** True once the grammars and theme are in the registries. */
    @Volatile
    var isReady: Boolean = false
        private set

    /**
     * Loads grammars and theme, once per process.
     *
     * @return true when highlighting is available; false tells the caller to
     *   fall back to [droshEditorColorScheme] and no language.
     */
    @Synchronized
    fun ensure(context: Context): Boolean {
        if (isReady) return true

        // The application context, not the screen's: the resolver is held in a
        // singleton that outlives any context it is handed.
        val assets = context.applicationContext.assets

        return try {
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))

            val stream = FileProviderRegistry.getInstance().tryGetInputStream(THEME_ASSET)
                ?: throw java.io.FileNotFoundException(THEME_ASSET)

            ThemeRegistry.getInstance().loadTheme(
                ThemeModel(
                    IThemeSource.fromInputStream(stream, THEME_ASSET, StandardCharsets.UTF_8),
                    THEME_NAME,
                ),
            )
            // setTheme, not only loadTheme: TextMate resolves span colours from
            // whichever theme is *selected*. A loaded-but-unselected theme leaves
            // every span transparent, which looks like a broken editor rather than
            // a missing one.
            if (!ThemeRegistry.getInstance().setTheme(THEME_NAME)) {
                Timber.w("textmate: theme '$THEME_NAME' did not become current")
            }

            GrammarRegistry.getInstance().loadGrammars(GRAMMAR_INDEX)

            isReady = true
            true
        } catch (e: Throwable) {
            Timber.e(e, "textmate: load failed; the editor stays plain text")
            false
        }
    }
}