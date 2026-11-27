package dev.drosh.domain.font

import kotlinx.coroutines.flow.StateFlow

/**
 * A terminal typeface the store offers.
 *
 * ## Why the catalog is here and not in a JSON file
 *
 * The store's shelf changes with the fonts, not with the app: the catalog is a
 * dozen lines of data, and a download of those dozen lines before the first
 * screen can be drawn would be a network round trip in front of a list that is
 * already known. The *files* are remote — a 2 MB TTF has no business in an
 * APK — but the list of them is part of the app.
 *
 * ## Why these fonts
 *
 * Every one of them is a **Nerd Font** build: the family plus the private-use
 * glyphs a prompt draws with — the Powerline separators, the branch and folder
 * icons, the box-drawing set. A terminal that swaps its font for a plain one
 * loses those, and a prompt built from them dissolves into missing-glyph boxes
 * on the first line the user sees. So the shelf is patched fonts only.
 *
 * The licence is per font and travels with it: OFL and MIT both require the
 * text to be redistributed with the file, which is why the store shows it and
 * why [licenseUrl] points at the family's own copy rather than at a note
 * written here.
 */
data class StoreFont(
    /** Stable id: the file name on disk and the settings value. */
    val id: String,
    /** What the store row says, the way the family is known. */
    val name: String,
    val family: String,
    val description: String,
    /** The TTF, served by the Nerd Fonts repository at a pinned release. */
    val url: String,
    val license: String,
    val licenseUrl: String,
    val sizeBytes: Long,
    val ligatures: Boolean,
    val tags: List<String>,
)

/** A font that is on this device, downloaded from [StoreFont.url]. */
data class InstalledFont(
    val id: String,
    val name: String,
    /** Absolute path of the TTF the renderer opens. */
    val path: String,
    val license: String,
    val licenseUrl: String,
)

/**
 * The terminal's font shelf and what it has put on this device.
 *
 * ## Where the active font is recorded
 *
 * Twice, on purpose. The *setting* — `[appearance] terminal_font` in
 * `settings.toml` — is the user's answer, is what the screen reads, and is what
 * a shell can change. The *marker* — [FontStore.ACTIVE_MARKER] under the app's
 * files directory — is what the renderer reads, because the renderer runs on a
 * draw thread that must not parse a TOML document to find out what face to
 * rasterise with. Writing the setting writes the marker; nothing else reads
 * or writes it.
 */
interface FontRepository {

    /** Fonts on disk, built-in face excluded. Alphabetical. */
    val installed: StateFlow<List<InstalledFont>>

    /** The active font's id; `""` is the bundled JetBrains Mono. */
    val active: StateFlow<String>

    /** The shelf: what can be downloaded. */
    fun store(): List<StoreFont>

    /** Downloads [font] and leaves it installed but not active. */
    suspend fun download(font: StoreFont): Result<InstalledFont>

    /**
     * Makes [id] the terminal's face, writing the marker the renderer reads.
     *
     * `""` returns to the bundled font. The caller owns the settings write; this
     * is only the device's half, so the two cannot disagree about a font that
     * was never downloaded.
     */
    fun activate(id: String)

    /** Deletes an installed font. The active one is refused by the caller. */
    suspend fun delete(id: String): Result<Unit>

    /** One installed font, or null. */
    fun installedFont(id: String): InstalledFont?
}

/** Where the renderer looks for the active font, relative to `filesDir`. */
object FontStore {
    /** The marker file: one line, the active font's id. */
    const val ACTIVE_MARKER = "terminal-fonts/active"

    /** The directory the TTFs are written to, relative to `filesDir`. */
    const val FONTS_DIR = "terminal-fonts"
}

/**
 * The fonts the store ships with a URL for.
 *
 * Pinned to the Nerd Fonts release rather than to `main`: a font that changes
 * under a user's feet is a cell width that changes, and a terminal laid out
 * around the old one. Bumping the release is a deliberate edit to this list.
 */
object TerminalFontCatalog {

    private const val BASE =
        "https://raw.githubusercontent.com/ryanoasis/nerd-fonts/v3.2.1/patched-fonts"

    private fun license(family: String) = "$BASE/$family/LICENSE.md"

    val fonts: List<StoreFont> = listOf(
        StoreFont(
            id = "jetbrains-mono",
            name = "JetBrains Mono",
            family = "JetBrainsMono",
            description = "JetBrains' coding face. Tall x-height, clear 0/O, ligatures.",
            url = "$BASE/JetBrainsMono/Ligatures/Regular/JetBrainsMonoNerdFont-Regular.ttf",
            license = "OFL-1.1",
            licenseUrl = license("JetBrainsMono"),
            sizeBytes = 2_285_836,
            ligatures = true,
            tags = listOf("ligatures", "coding"),
        ),
        StoreFont(
            id = "fira-code",
            name = "Fira Code",
            family = "FiraCode",
            description = "The ligature font: arrows and operators join as they are typed.",
            url = "$BASE/FiraCode/Regular/FiraCodeNerdFont-Regular.ttf",
            license = "OFL-1.1",
            licenseUrl = license("FiraCode"),
            sizeBytes = 2_425_644,
            ligatures = true,
            tags = listOf("ligatures", "coding"),
        ),
        StoreFont(
            id = "hack",
            name = "Hack",
            family = "Hack",
            description = "Designed for source code: no ligatures, unmistakable punctuation.",
            url = "$BASE/Hack/Regular/HackNerdFont-Regular.ttf",
            license = "MIT",
            licenseUrl = license("Hack"),
            sizeBytes = 2_445_204,
            ligatures = false,
            tags = listOf("plain", "coding"),
        ),
        StoreFont(
            id = "cascadia-code",
            name = "Cascadia Code",
            family = "CascadiaCode",
            description = "Microsoft's terminal face, drawn beside the Windows Terminal.",
            url = "$BASE/CascadiaCode/Regular/CaskaydiaCoveNerdFont-Regular.ttf",
            license = "OFL-1.1",
            licenseUrl = license("CascadiaCode"),
            sizeBytes = 2_577_088,
            ligatures = true,
            tags = listOf("ligatures", "coding"),
        ),
        StoreFont(
            id = "source-code-pro",
            name = "Source Code Pro",
            family = "SourceCodePro",
            description = "Adobe's monospace: wide, calm, readable at small sizes.",
            url = "$BASE/SourceCodePro/SauceCodeProNerdFont-Regular.ttf",
            license = "OFL-1.1",
            licenseUrl = license("SourceCodePro"),
            sizeBytes = 2_186_992,
            ligatures = false,
            tags = listOf("plain", "coding"),
        ),
        StoreFont(
            id = "ibm-plex-mono",
            name = "IBM Plex Mono",
            family = "IBMPlexMono",
            description = "IBM's grotesque monospace, with a serif's rhythm in the strokes.",
            url = "$BASE/IBMPlexMono/Mono/BlexMonoNerdFont-Regular.ttf",
            license = "OFL-1.1",
            licenseUrl = license("IBMPlexMono"),
            sizeBytes = 2_167_816,
            ligatures = false,
            tags = listOf("plain", "coding"),
        ),
        StoreFont(
            id = "victor-mono",
            name = "Victor Mono",
            family = "VictorMono",
            description = "Cursive italics and strong shapes; the one font that is not shy.",
            url = "$BASE/VictorMono/Regular/VictorMonoNerdFont-Regular.ttf",
            license = "OFL-1.1",
            licenseUrl = license("VictorMono"),
            sizeBytes = 2_245_848,
            ligatures = true,
            tags = listOf("ligatures", "italic"),
        ),
        StoreFont(
            id = "mononoki",
            name = "mononoki",
            family = "Mononoki",
            description = "Small, tall, made for code review on a laptop screen.",
            url = "$BASE/Mononoki/Regular/MononokiNerdFont-Regular.ttf",
            license = "OFL-1.1",
            licenseUrl = license("Mononoki"),
            sizeBytes = 2_116_656,
            ligatures = false,
            tags = listOf("plain", "compact"),
        ),
    )

    /** The shelf entry for [id], or null when the font is not one of ours. */
    fun byId(id: String): StoreFont? = fonts.firstOrNull { it.id == id }
}
