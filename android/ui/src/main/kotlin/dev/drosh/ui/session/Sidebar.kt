package dev.drosh.ui.session

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.drosh.domain.session.DeviceIdentity
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurface
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.theme.DroshTextSecondary
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.R

/**
 * The push state behind the dashboard drawer.
 *
 * The drawer is a translate, not an overlay: the terminal behind it slides by
 * the same amount, so there is no scrim to dim the thing you are working in.
 * One instance is shared with the terminal through [rememberSidebarPushState].
 */
@Composable
fun rememberSidebarPushState(isOpen: Boolean): SidebarPushState {
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val sidebarWidth = remember(config) {
        val sw = config.screenWidthDp
        if (sw > 0) (sw * 0.78f).coerceIn(260f, 340f).dp else 300.dp
    }
    val sidebarWidthPx = with(density) { sidebarWidth.toPx() }
    val pushProgress by animateFloatAsState(
        targetValue = if (isOpen) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "sidebarPushProgress",
    )
    return remember(sidebarWidth) { SidebarPushState(sidebarWidth, sidebarWidthPx) }
        .also { it.progress = pushProgress }
}

class SidebarPushState internal constructor(
    val width: Dp,
    val widthPx: Float,
) {
    var progress by mutableFloatStateOf(0f)
        internal set
}

/** The translate the terminal applies to stay in step with the drawer. */
fun Modifier.sidebarPush(state: SidebarPushState): Modifier = this.graphicsLayer {
    translationX = state.progress * state.widthPx
}

/**
 * The slide-in drawer: a small feature dashboard with four tiles —
 * [ProcessesSheet], [ResourceMonitorSheet], Drosh Keyboard and Settings — plus
 * the device header and a close control.
 *
 * Session management, navigation and the terminal's keyboard/log controls all
 * left this drawer; session switching lives in its own grid screen. The
 * diagnostics report is built by the terminal screen and reached through
 * [onOpenDiagnostics]. Keyboard and Settings are the two restored entries and
 * are handed straight to the platform through [onOpenKeyboard] and
 * [onOpenSettings].
 */
@Composable
fun Sidebar(
    isOpen: Boolean,
    onClose: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val deviceIdentityViewModel: DeviceIdentityViewModel = hiltViewModel()
    val identity by deviceIdentityViewModel.identity.collectAsStateWithLifecycle()
    val state = rememberSidebarPushState(isOpen)

    var processesOpen by remember { mutableStateOf(false) }
    var resourcesOpen by remember { mutableStateOf(false) }

    // UNTESTED — verify before use: a sheet must not outlive its drawer.
    // Keyed on isOpen rather than the animated push progress, which passes
    // through every value on the way out and would dismiss the sheet early.
    LaunchedEffect(isOpen) {
        if (!isOpen) {
            processesOpen = false
            resourcesOpen = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(state.width)
            .graphicsLayer { translationX = (state.progress - 1f) * state.widthPx }
            .zIndex(1f)
            .background(DroshSurface)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Background runs behind the system bars; content stays clear.
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
                .navigationBarsPadding(),
        ) {
            SidebarHeader(identity = identity, onClose = onClose)

            Text(
                text = stringResource(R.string.sidebar_dashboard_section),
                color = DroshTextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp),
            )

            DashboardTile(
                icon = DroshIcons.Terminal,
                title = stringResource(R.string.sidebar_tile_processes),
                subtitle = stringResource(R.string.sidebar_tile_processes_subtitle),
                onClick = { processesOpen = true },
            )
            Spacer(Modifier.height(10.dp))
            DashboardTile(
                icon = DroshIcons.Gauge,
                title = stringResource(R.string.sidebar_tile_resources),
                subtitle = stringResource(R.string.sidebar_tile_resources_subtitle),
                onClick = { resourcesOpen = true },
            )
            Spacer(Modifier.height(10.dp))
            DashboardTile(
                icon = DroshIcons.Keyboard,
                title = stringResource(R.string.sidebar_tile_keyboard),
                subtitle = stringResource(R.string.sidebar_tile_keyboard_subtitle),
                onClick = onOpenKeyboard,
            )
            Spacer(Modifier.height(10.dp))
            DashboardTile(
                icon = DroshIcons.Settings,
                title = stringResource(R.string.sidebar_tile_settings),
                subtitle = stringResource(R.string.sidebar_tile_settings_subtitle),
                onClick = onOpenSettings,
            )
        }
    }

    // Bottom sheets are separate windows, so they are drawn as siblings of the
    // translated drawer rather than inside its clipped, moved box.
    if (processesOpen) {
        ProcessesSheet(onDismiss = { processesOpen = false })
    }
    if (resourcesOpen) {
        ResourceMonitorSheet(
            onDismiss = { resourcesOpen = false },
            onOpenDiagnostics = onOpenDiagnostics,
        )
    }
}

@Composable
private fun SidebarHeader(
    identity: DeviceIdentity?,
    onClose: () -> Unit,
) {
    val name = identity?.marketingName.orEmpty().ifBlank { stringResource(R.string.sidebar_this_device) }
    val subtitle = identity?.takeIf { it.marketingName != it.model }
        ?.let { listOf(it.manufacturer, it.model).filter { part -> part.isNotBlank() }.joinToString(" ") }
        ?.takeIf { it.isNotBlank() }
        ?: identity?.model.orEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 14.dp)
            .padding(top = 12.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DeviceBadge(
            imageUrl = identity?.visualUrl,
            fallbackLetter = name.firstOrNull()?.uppercase() ?: "?",
            size = 40.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.sidebar_dashboard_brand),
                color = DroshPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
            Text(
                text = name,
                color = DroshText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = DroshTextMuted,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        val surface by animateFloatAsState(
            targetValue = if (pressed) 1f else 0f,
            animationSpec = tween(durationMillis = 120),
            label = "sidebarClose",
        )
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(DroshSurfaceVariant.copy(alpha = 0.75f + 0.25f * surface))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClose,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = DroshIcons.X,
                contentDescription = stringResource(R.string.sidebar_close),
                tint = DroshTextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * One feature entry. The whole band is the target; pressing deepens its
 * surface and nudges it down to 0.97f, because a ripple on an already-dark row
 * reads as nothing at all.
 */
@Composable
private fun DashboardTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val surface by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "dashboardTilePress",
    )
    // UNTESTED — verify before use: the press scale reads the same
    // interactionSource as the surface state; 120ms with FastOutSlowInEasing
    // settles without overshoot, so there is no bounce on release.
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "dashboardTileScale",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(RoundedCornerShape(14.dp))
            .background(DroshSurfaceVariant.copy(alpha = 0.55f + 0.45f * surface))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(DroshPrimary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = DroshPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = DroshText,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = DroshTextMuted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            imageVector = DroshIcons.ChevronRight,
            contentDescription = null,
            tint = DroshTextMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * The device in the circle: its product image once one has been looked up and
 * cached, a monogram until then. Wikimedia may simply have nothing for the
 * model, so the monogram is the resting state rather than a failure.
 *
 * Shared with the settings header, which asks the same question.
 */
@Composable
fun DeviceBadge(
    imageUrl: String?,
    fallbackLetter: String,
    size: Dp = 36.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                // Tinted with the accent rather than a flat grey, so the
                // placeholder reads as deliberate instead of as a missing image.
                brush = Brush.linearGradient(
                    listOf(
                        DroshPrimary.copy(alpha = 0.22f),
                        DroshPrimary.copy(alpha = 0.10f),
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = fallbackLetter,
                color = DroshPrimary,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
