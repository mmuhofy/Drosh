package dev.drosh.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.domain.settings.DroshTheme
import dev.drosh.domain.settings.ThemeIndexEntry
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.luminance
import dev.drosh.theme.DroshBackground
import dev.drosh.theme.DroshOutline
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.theme.DroshTile
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

/**
 * The Drosh store: a shelf, not a list.
 *
 * Two rooms behind one bar. The **store** is the catalogue — a grid of
 * every theme with its palette drawn as its cover, so two hundred dark
 * schemes can be told apart at a glance. The **library** is what is
 * installed: apply it, or take it off the shelf.
 *
 * It used to be a settings list — the same rows, the same chevrons, the
 * catalogue a scrolling column of one-line entries — which is a strange
 * way to sell someone a colour scheme. A store shows you the thing.
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

    var room by rememberSaveable { mutableStateOf(StoreRoom.Store) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    /**
     * The store's own scaffold, not the settings one.
     *
     * [SettingsCategoryScreen] wraps its content in a scrolling column, which is
     * right for a page of rows and wrong for a grid: a lazy grid inside a
     * vertical scroll is measured with an infinite height and refuses to build.
     * The grid scrolls itself here, with only the bar above it fixed.
     */
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = storeStatusBarInset()),
        ) {
            SettingsTopBar(
                title = stringResource(R.string.settings_theme_store),
                onBack = onBack,
            )

            StoreRoomBar(
                room = room,
                installedCount = installed.size,
                onRoomChange = { room = it },
            )

            when (room) {
                StoreRoom.Store -> StoreShelf(
                    entries = storeIndex,
                    installedIds = installed.map { it.id }.toSet(),
                    busy = busy,
                    onInstall = viewModel::install,
                    onRefresh = viewModel::refreshIndex,
                    modifier = Modifier.weight(1f),
                )

                StoreRoom.Library -> ThemeLibrary(
                    installed = installed,
                    activeId = activeId,
                    onApply = { id -> viewModel.apply(id) },
                    onRemove = { id -> pendingDelete = id },
                    onInstallFromUrl = viewModel::startUrlInstall,
                    onCreate = viewModel::startCreate,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    // ── the overlays ─────────────────────────────────────────────────────────
    val urlDialogOpen by viewModel.urlDialogOpen.collectAsStateWithLifecycle()
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

    pendingDelete?.let { id ->
        val theme = installed.firstOrNull { it.id == id }
        SettingsConfirmDialog(
            title = stringResource(R.string.settings_theme_remove_title, theme?.name ?: id),
            body = stringResource(R.string.settings_theme_remove_body),
            confirmLabel = stringResource(R.string.settings_theme_remove_confirm),
            onConfirm = {
                pendingDelete = null
                viewModel.remove(id)
            },
            onDismiss = { pendingDelete = null },
        )
    }

    val createFrom by viewModel.createFrom.collectAsStateWithLifecycle()
    if (createFrom != null) {
        ThemeCreatorDialog(
            base = installed.firstOrNull { it.id == createFrom },
            options = installed,
            onPickBase = { id -> viewModel.createFrom(id) },
            onDismiss = { viewModel.cancelCreate() },
            onSave = { name, author, description -> viewModel.saveCustom(name, author, description) },
        )
    }

    message?.let { text ->
        SettingsMessageDialog(text = text, onDismiss = { viewModel.dismissMessage() })
    }
}

private enum class StoreRoom { Store, Library }

/** The two rooms, as a segmented bar rather than as two screens. */
@Composable
private fun StoreRoomBar(
    room: StoreRoom,
    installedCount: Int,
    onRoomChange: (StoreRoom) -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DroshSurfaceVariant),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StoreRoom.entries.forEach { entry ->
            val selected = entry == room
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) DroshPrimary else Color.Transparent)
                    .clickable { onRoomChange(entry) }
                    .padding(vertical = 9.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        if (entry == StoreRoom.Store) R.string.settings_theme_room_store
                        else R.string.settings_theme_room_library,
                    ),
                    color = if (selected) DroshTile else DroshTextSecondary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (entry == StoreRoom.Library) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "$installedCount",
                        color = if (selected) DroshTile.copy(alpha = 0.8f) else DroshTextMuted,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/** The catalogue: a grid of covers, and a way to reload it. */
@Composable
private fun StoreShelf(
    entries: List<ThemeIndexEntry>,
    installedIds: Set<String>,
    busy: Boolean,
    onInstall: (ThemeIndexEntry) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
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
                onClick = onRefresh,
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    SettingsChevron()
                }
            }
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(entries, key = { it.id }) { entry ->
            ThemeStoreCard(
                entry = entry,
                installed = entry.id in installedIds,
                busy = busy,
                onClick = { onInstall(entry) },
            )
        }
    }
}

/** One theme on the shelf: its palette is its cover. */
@Composable
private fun ThemeStoreCard(
    entry: ThemeIndexEntry,
    installed: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(DroshSurfaceVariant)
            .clickable(enabled = !installed && !busy, onClick = onClick)
            .padding(10.dp),
    ) {
        // The palette as a band of colour: the index carries it, so a cover
        // costs a draw and not a request.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, DroshOutline, RoundedCornerShape(10.dp)),
        ) {
            entry.preview.take(9).forEach { hex ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .background(parseHexColor(hex)),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = entry.name,
            color = DroshText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = entry.author.ifBlank { stringResource(R.string.settings_theme_no_description) },
            color = DroshTextMuted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (installed) {
                Icon(
                    imageVector = DroshIcons.Check,
                    contentDescription = null,
                    tint = DroshPrimary,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.settings_theme_installed_tag),
                    color = DroshPrimary,
                    fontSize = 12.sp,
                )
            } else {
                Icon(
                    imageVector = DroshIcons.Download,
                    contentDescription = null,
                    tint = DroshTextMuted,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (busy) {
                        stringResource(R.string.settings_theme_loading)
                    } else {
                        stringResource(R.string.settings_theme_install)
                    },
                    color = DroshTextMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** The library: what is installed, and what can be done to it. */
@Composable
private fun ThemeLibrary(
    installed: List<DroshTheme>,
    activeId: String,
    onApply: (String) -> Unit,
    onRemove: (String) -> Unit,
    onInstallFromUrl: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(installed.size, key = { installed[it].id }) { index ->
            val theme = installed[index]
            val active = theme.id == activeId
            SettingsTile(
                cap = when {
                    installed.size == 1 -> TileCap.Only
                    index == 0 -> TileCap.First
                    index == installed.size - 1 -> TileCap.Last
                    else -> TileCap.Middle
                },
                title = theme.name,
                icon = DroshIcons.Palette,
                supporting = if (active) {
                    stringResource(R.string.settings_theme_in_use)
                } else {
                    theme.author.ifBlank { stringResource(R.string.settings_theme_no_description) }
                },
                leading = {
                    ThemeGridLogo(theme = theme, modifier = Modifier.size(38.dp))
                },
                onClick = { onApply(theme.id) },
                // Long press takes it off the shelf. A row's second action has
                // lived behind a long press in this app since the sidebar, and
                // a delete button on every row is a row that deletes by accident.
                onLongClick = { onRemove(theme.id) },
            ) {
                if (active) SettingsCheck() else SettingsChevron()
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
        }

        item {
            SettingsGroupColumn(label = stringResource(R.string.settings_section_custom), items = 2) { index, cap ->
                if (index == 0) {
                    SettingsTile(
                        cap = cap,
                        title = stringResource(R.string.settings_theme_from_url),
                        icon = DroshIcons.ExternalLink,
                        supporting = stringResource(R.string.settings_theme_from_url_desc),
                        onClick = onInstallFromUrl,
                    ) { SettingsChevron() }
                } else {
                    SettingsTile(
                        cap = cap,
                        title = stringResource(R.string.settings_theme_create),
                        icon = DroshIcons.Pencil,
                        supporting = stringResource(R.string.settings_theme_create_desc),
                        onClick = onCreate,
                    ) { SettingsChevron() }
                }
            }
        }
    }
}

/**
 * A theme's logo: its palette as a grid.
 *
 * Nine cells — the background edge, then the eight colours a theme is judged
 * by — because that is the smallest honest picture of a palette.
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

/** One line of news, from an install or a removal. */
@Composable
private fun SettingsMessageDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DroshSurfaceVariant,
        title = { Text(stringResource(R.string.settings_theme_store), color = DroshText) },
        text = { Text(text, color = DroshTextSecondary) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_dialog_save), color = DroshPrimary)
            }
        },
    )
}

/** Content clearance for the system bar, without insetting the background. */
@Composable
internal fun storeStatusBarInset(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/** "#RRGGBB" as a colour, black when it is not one. */
internal fun parseHexColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Black)

/** True for the pale swatches, where text has to be dark to be read. */
internal fun Color.isLight(): Boolean = luminance() > 0.5f

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
                        // Indexed, not the list overload: this file also brings
                        // the grid's `items` into scope, and two of the same
                        // name is one too many for the compiler to choose.
                        itemsIndexed(options) { _, theme ->
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
