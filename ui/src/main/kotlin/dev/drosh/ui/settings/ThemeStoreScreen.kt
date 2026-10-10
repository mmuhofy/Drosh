package dev.drosh.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.domain.settings.DroshTheme
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshSurfaceVariant
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshOutline
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import dev.drosh.domain.settings.ThemeIndexEntry
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R
import kotlinx.coroutines.launch

/**
 * The theme store.
 *
 * One screen with three sources: what is installed, what the store lists,
 * and what a user pastes a URL for. A theme's logo is its palette drawn as
 * a grid, because a theme is its colours and a screenshot is a lie about
 * how it will look in *your* terminal.
 */
@Composable
fun ThemeStoreScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ThemeStoreViewModel = hiltViewModel(),
) {
    val installed by viewModel.installed.collectAsStateWithLifecycle()
    val storeIndex by viewModel.storeIndex.collectAsStateWithLifecycle()
    val activeId by viewModel.activeThemeId.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    // The store is the one screen that shows what a theme does, so its own
    // background is the active theme's — animated, because a palette swap
    // that snaps reads as a mistake rather than a choice.
    val activeBackground by animateColorAsState(
        targetValue = parseHexColor(
            installed.firstOrNull { it.id == activeId }?.palette?.background ?: "#000000",
        ),
        animationSpec = tween(450),
        label = "themeBackground",
    )

    SettingsCategoryScreen(
        title = stringResource(R.string.settings_theme_store),
        onBack = onBack,
        modifier = modifier.background(activeBackground),
    ) {
        // ── the active theme, large ───────────────────────────────────────────
        val active = installed.firstOrNull { it.id == activeId }
        if (active != null) {
            SettingsGroupColumn(label = stringResource(R.string.settings_theme_active), items = 1) { _, cap ->
                SettingsTile(
                    cap = cap,
                    title = active.name,
                    icon = DroshIcons.Palette,
                    supporting = active.description.ifBlank {
                        stringResource(R.string.settings_theme_no_description)
                    },
                    leading = {
                        ThemeGridLogo(theme = active, modifier = Modifier.size(46.dp))
                    },
                )
            }
            Spacer(Modifier.height(GROUP_GAP))
        }

        // ── installed ─────────────────────────────────────────────────────────
        SettingsGroupColumn(label = stringResource(R.string.settings_theme_installed), items = installed.size) { index, cap ->
            val theme = installed[index]
            SettingsTile(
                cap = cap,
                title = theme.name,
                icon = DroshIcons.Palette,
                supporting = if (theme.id == activeId) {
                    stringResource(R.string.settings_theme_in_use)
                } else {
                    theme.author.ifBlank { stringResource(R.string.settings_theme_no_description) }
                },
                leading = {
                    ThemeGridLogo(theme = theme, modifier = Modifier.size(38.dp))
                },
                onClick = { viewModel.apply(theme.id) },
            ) {
                if (theme.id == activeId) SettingsCheck() else SettingsChevron()
            }
        }

        Spacer(Modifier.height(GROUP_GAP))

        // ── the store ─────────────────────────────────────────────────────────
        SettingsGroupColumn(label = stringResource(R.string.settings_theme_store_section), items = 1) { _, cap ->
            SettingsTile(
                cap = cap,
                title = stringResource(R.string.settings_theme_refresh),
                icon = DroshIcons.RotateCw,
                supporting = if (busy) {
                    stringResource(R.string.settings_theme_loading)
                } else {
                    stringResource(R.string.settings_theme_store_desc)
                },
                onClick = { viewModel.refreshIndex() },
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    SettingsChevron()
                }
            }
        }

        if (storeIndex.isNotEmpty()) {
            SettingsGroupColumn(
                label = stringResource(R.string.settings_theme_available),
                items = storeIndex.size,
            ) { index, cap ->
                val entry = storeIndex[index]
                val alreadyInstalled = installed.any { it.id == entry.id }
                SettingsTile(
                    cap = cap,
                    title = entry.name,
                    icon = DroshIcons.Download,
                    supporting = entry.description.ifBlank { entry.author },
                    onClick = { viewModel.install(entry) },
                ) {
                    if (alreadyInstalled) SettingsCheck() else SettingsChevron()
                }
            }
        }

        Spacer(Modifier.height(GROUP_GAP))

        // ── from a URL ────────────────────────────────────────────────────────
        SettingsGroupColumn(label = stringResource(R.string.settings_section_custom), items = 1) { _, cap ->
            SettingsTile(
                cap = cap,
                title = stringResource(R.string.settings_theme_from_url),
                icon = DroshIcons.ExternalLink,
                supporting = stringResource(R.string.settings_theme_from_url_desc),
                onClick = { viewModel.startUrlInstall() },
            ) { SettingsChevron() }
        }

        SettingsGroupColumn(label = stringResource(R.string.settings_section_custom), items = 1) { _, cap ->
            SettingsTile(
                cap = cap,
                title = stringResource(R.string.settings_theme_create),
                icon = DroshIcons.Pencil,
                supporting = stringResource(R.string.settings_theme_create_desc),
                onClick = { viewModel.startCreate() },
            ) { SettingsChevron() }
        }

        Spacer(Modifier.height(32.dp))
    }

    val urlDialogOpen by viewModel.urlDialogOpen.collectAsStateWithLifecycle()
    val createFrom by viewModel.createFrom.collectAsStateWithLifecycle()

    if (urlDialogOpen) {
        SettingsTextInputDialog(
            title = stringResource(R.string.settings_theme_from_url),
            initial = "",
            placeholder = "https://example.com/theme.toml",
            onDismiss = { viewModel.dismissUrlInstall() },
            onConfirm = { url ->
                viewModel.dismissUrlInstall()
                viewModel.installFromUrl(url)
            },
        )
    }

    // A theme arriving: its grid draws itself once, full size, over the
    // screen. Six hundred milliseconds of a fact that has already happened
    // — the palette is live before this finishes.
    val applyingId by viewModel.applying.collectAsStateWithLifecycle()
    if (applyingId != null) {
        val applyingTheme = installed.firstOrNull { it.id == applyingId }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(parseHexColor(applyingTheme?.palette?.background ?: "#000000").copy(alpha = 0.72f)),
            contentAlignment = Alignment.Center,
        ) {
            val logoScale by animateFloatAsState(
                targetValue = 1f,
                animationSpec = tween(500),
                label = "themeLogoScale",
            )
            ThemeGridLogo(
                theme = applyingTheme ?: DroshTheme.DEFAULT,
                modifier = Modifier
                    .size((140 * logoScale).dp)
                    .clip(RoundedCornerShape(20.dp)),
            )
        }
    }

    message?.let { text ->
        SettingsMessageDialog(text = text, onDismiss = { viewModel.dismissMessage() })
    }

    // ── the creator ───────────────────────────────────────────────────────────
    // Starts from a theme that is already installed, because a palette is
    // not something anyone wants to build twenty-two colours at a time: pick
    // the closest, name it, save, and then edit the file itself — it is a
    // plain TOML document in the rootfs, which is where it belongs.
    if (createFrom != null) {
        ThemeCreatorDialog(
            base = installed.firstOrNull { it.id == createFrom },
            options = installed,
            onPickBase = { id -> viewModel.createFrom(id) },
            onDismiss = { viewModel.cancelCreate() },
            onSave = { name, author, description -> viewModel.saveCustom(name, author, description) },
        )
    }
}

/**
 * A theme's logo: its palette as a grid.
 *
 * Nine cells — the background edge, then the eight colours a theme is
 * judged by — because that is the smallest honest picture of a palette.
 * The text on each cell picks its side by luminance, so a name stays
 * readable on a pale swatch.
 */
@Composable
fun ThemeGridLogo(
    theme: DroshTheme,
    modifier: Modifier = Modifier,
) {
    val palette = theme.palette
    val swatches = remember(palette) {
        listOf(
            palette.background,
            palette.black,
            palette.red,
            palette.green,
            palette.yellow,
            palette.blue,
            palette.magenta,
            palette.cyan,
            palette.white,
        )
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, DroshOutline, RoundedCornerShape(10.dp)),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        swatches.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                row.forEach { hex ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .background(parseHexColor(hex)),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsMessageDialog(text: String, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DroshSurfaceVariant,
        title = { Text(stringResource(R.string.settings_theme_store), color = DroshText) },
        text = { Text(text, color = DroshTextSecondary) },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_dialog_save), color = DroshPrimary)
            }
        },
    )
}

/** "#RRGGBB" as a colour, black when it is not one. */
internal fun parseHexColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Black)

/** True for the pale swatches, where text has to be dark to be read. */
internal fun Color.isLight(): Boolean = luminance() > 0.5f

/**
 * The creator: pick a base, name it, save it.
 *
 * Not a colour editor. A palette is twenty-two values and a phone keyboard
 * is the worst place to type hex into twenty-two fields — the honest flow is
 * to start from the closest installed theme and then edit the file, which is
 * one line per colour and sits in the rootfs where the user already works.
 */
@Composable
private fun ThemeCreatorDialog(
    base: DroshTheme?,
    options: List<DroshTheme>,
    onPickBase: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var author by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DroshSurfaceVariant,
        title = { Text(stringResource(R.string.settings_theme_create), color = DroshText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CreatorField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.settings_theme_name),
                )
                CreatorField(
                    value = author,
                    onValueChange = { author = it },
                    label = stringResource(R.string.settings_theme_author),
                )
                CreatorField(
                    value = description,
                    onValueChange = { description = it },
                    label = stringResource(R.string.settings_theme_description),
                )
                if (options.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_theme_base),
                        color = DroshTextSecondary,
                        fontSize = 13.sp,
                    )
                    LazyColumn(modifier = Modifier.height(140.dp)) {
                        items(options) { theme ->
                            val selected = theme.id == base?.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPickBase(theme.id) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                ThemeGridLogo(theme = theme, modifier = Modifier.size(28.dp))
                                Text(
                                    text = theme.name,
                                    color = if (selected) DroshPrimary else DroshText,
                                    fontSize = 14.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onSave(name, author, description) },
            ) {
                Text(stringResource(R.string.settings_dialog_save), color = DroshPrimary)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_dialog_cancel), color = DroshTextSecondary)
            }
        },
    )
}

@Composable
private fun CreatorField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = TextStyle(color = DroshText, fontSize = 14.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = DroshPrimary,
            unfocusedBorderColor = DroshOutline,
            cursorColor = DroshPrimary,
            focusedTextColor = DroshText,
            unfocusedTextColor = DroshText,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
