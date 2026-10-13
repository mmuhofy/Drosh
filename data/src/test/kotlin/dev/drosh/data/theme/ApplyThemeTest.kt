package dev.drosh.data.theme

import dev.drosh.domain.settings.DroshSettings
import dev.drosh.domain.settings.DroshTheme
import dev.drosh.domain.settings.SettingsStore
import dev.drosh.domain.settings.ThemeIndex
import dev.drosh.domain.settings.ThemeIndexEntry
import dev.drosh.domain.settings.ThemeRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The order of the two writes that make a theme real.
 *
 * There are two of them — the theme file, then the palette in `settings.toml` —
 * and the order between them is the whole contract. A settings file that names
 * a theme whose file is not on disk is the one state this app cannot describe:
 * the store shows the id as in use, the palette draws, and nothing anywhere
 * offers a way back. So the file is written first, and the test below holds that
 * line by recording both calls on one clock and comparing the sequence — not by
 * asserting a message.
 *
 * The rootfs being absent is what makes this class exist, and what makes it
 * testable at all: neither fake needs a filesystem, so the chain can be checked
 * in a JVM test rather than only by installing Ubuntu on a phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApplyThemeTest {

    @Test
    fun `applying a starter writes the theme file before the settings file`() = runTest {
        val writes = CallRecorder()
        val themes = FakeThemeRepository(recorder = writes)
        val applyTheme = ApplyTheme(themes, FakeSettingsStore(writes), backgroundScope)

        val result = applyTheme.apply("dracula")

        assertTrue(result.isSuccess)
        assertEquals(
            "the file has to exist before anything points at it",
            listOf("save:dracula", "write:settings.toml"),
            writes.calls,
        )
        assertEquals(listOf("dracula"), themes.saved.map { it.id })
    }

    @Test
    fun `applying puts the palette and the id in the settings file`() = runTest {
        val store = FakeSettingsStore(CallRecorder())
        val applyTheme = ApplyTheme(FakeThemeRepository(), store, backgroundScope)
        val dracula = DroshTheme.find("dracula", applyTheme.choices.value)!!

        applyTheme.apply("dracula")

        assertEquals("dracula", store.current.terminal.themeId)
        assertEquals(dracula.palette, store.current.terminal.palette)
    }

    @Test
    fun `applying leaves the rest of the settings alone`() = runTest {
        val store = FakeSettingsStore(
            CallRecorder(),
            DroshSettings.DEFAULT.copy(
                appearance = DroshSettings.DEFAULT.appearance.copy(language = "tr"),
                shell = DroshSettings.DEFAULT.shell.copy(locale = "tr_TR.UTF-8"),
            ),
        )
        val applyTheme = ApplyTheme(FakeThemeRepository(), store, backgroundScope)

        applyTheme.apply("nord")

        assertEquals("tr", store.current.appearance.language)
        assertEquals("tr_TR.UTF-8", store.current.shell.locale)
        assertTrue(
            "markCompleted is the other write of this flow, and it must survive",
            store.current.meta.firstLaunchCompleted,
        )
    }

    @Test
    fun `the palette is not written when the theme file cannot be`() = runTest {
        val writes = CallRecorder()
        val applyTheme = ApplyTheme(
            FakeThemeRepository(failSave = true),
            FakeSettingsStore(writes),
            backgroundScope,
        )

        val result = applyTheme.apply("nord")

        assertTrue("the failure has to reach the caller", result.isFailure)
        assertEquals(
            "a settings file naming a theme with no file behind it is the broken state",
            listOf("save:nord"),
            writes.calls,
        )
    }

    @Test
    fun `an unknown id fails and writes nothing`() = runTest {
        val writes = CallRecorder()
        val applyTheme = ApplyTheme(FakeThemeRepository(), FakeSettingsStore(writes), backgroundScope)

        val result = applyTheme.apply("no-such-theme")

        assertTrue(result.isFailure)
        assertEquals(emptyList<String>(), writes.calls)
    }

    @Test
    fun `an installed theme of the same id is not overwritten`() = runTest {
        val mine = DroshTheme(
            id = "nord",
            name = "Nord (edited)",
            palette = DroshTheme.DEFAULT.palette.copy(background = "#010203"),
        )
        val themes = FakeThemeRepository(installed = listOf(mine))
        val store = FakeSettingsStore(CallRecorder())
        val applyTheme = ApplyTheme(themes, store, backgroundScope)

        applyTheme.apply("nord")

        assertEquals(
            "the user edited this one; the bundled copy must not win",
            emptyList<String>(),
            themes.saved.map { it.id },
        )
        assertEquals("#010203", store.current.terminal.palette.background)
    }

    @Test
    fun `choices offer the built-ins before anything on disk`() = runTest {
        val themes = FakeThemeRepository(
            installed = listOf(DroshTheme(id = "mine", name = "Mine")),
        )
        val applyTheme = ApplyTheme(themes, FakeSettingsStore(CallRecorder()), backgroundScope)

        val choices = applyTheme.choices.value

        assertEquals("drosh", choices.first().id)
        assertEquals("mine", choices.last().id)
    }

    @Test
    fun `isApplied is true only for the theme the file actually carries`() = runTest {
        val store = FakeSettingsStore(CallRecorder())
        val applyTheme = ApplyTheme(FakeThemeRepository(), store, backgroundScope)

        assertFalse(
            "a fresh install carries no theme id at all",
            applyTheme.isApplied("dracula"),
        )

        applyTheme.apply("dracula")

        assertTrue(applyTheme.isApplied("dracula"))
        assertFalse("another palette is not the one in the file", applyTheme.isApplied("nord"))
    }

    // ── fakes ─────────────────────────────────────────────────────────────────

    /**
     * The store, with an ordered record of what was asked of it.
     *
     * The theme writes and the settings writes share one recorder so the
     * assertion can be "save, then write" rather than two separate facts that
     * could each hold while the other did not.
     */
    private class CallRecorder {
        val calls = mutableListOf<String>()

        fun record(call: String) {
            calls += call
        }
    }

    private class FakeThemeRepository(
        installed: List<DroshTheme> = emptyList(),
        private val failSave: Boolean = false,
        private val recorder: CallRecorder = CallRecorder(),
    ) : ThemeRepository {

        private val _installed = MutableStateFlow(installed)
        override val installed: StateFlow<List<DroshTheme>> = _installed.asStateFlow()

        /** The themes written to disk, in order. */
        val saved = mutableListOf<DroshTheme>()

        override suspend fun index(force: Boolean): Result<ThemeIndex> =
            Result.success(ThemeIndex())

        override suspend fun install(entry: ThemeIndexEntry): Result<DroshTheme> =
            Result.failure(IOException("not used"))

        override suspend fun installFromUrl(url: String): Result<DroshTheme> =
            Result.failure(IOException("not used"))

        override suspend fun saveCustom(theme: DroshTheme): Result<DroshTheme> {
            recorder.record("save:${theme.id}")
            if (failSave) return Result.failure(IOException("disk full"))
            saved += theme
            _installed.value = _installed.value + theme
            return Result.success(theme)
        }

        override suspend fun delete(id: String): Result<Unit> {
            _installed.value = _installed.value.filterNot { it.id == id }
            return Result.success(Unit)
        }

        override fun theme(id: String): DroshTheme? = _installed.value.firstOrNull { it.id == id }
    }

    private class FakeSettingsStore(
        private val recorder: CallRecorder,
        initial: DroshSettings = DroshSettings.DEFAULT.copy(
            meta = DroshSettings.DEFAULT.meta.copy(firstLaunchCompleted = true),
        ),
    ) : SettingsStore {

        private val _settings = MutableStateFlow(initial)
        override val settings: StateFlow<DroshSettings> = _settings.asStateFlow()

        private val _changedExternally = MutableStateFlow(false)
        override val changedExternally: StateFlow<Boolean> = _changedExternally.asStateFlow()

        val current: DroshSettings get() = _settings.value

        override suspend fun update(transform: (DroshSettings) -> DroshSettings) {
            recorder.record("write:$FILE")
            _settings.value = transform(_settings.value)
        }

        override suspend fun reload() = Unit

        override fun acknowledgeExternalChange() {
            _changedExternally.value = false
        }

        private companion object {
            const val FILE = "settings.toml"
        }
    }
}