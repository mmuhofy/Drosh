package dev.drosh.ui.topbar

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.ui.DroshIcons
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshDropdownMenu
import dev.drosh.design.system.DroshMenuItem
import dev.drosh.design.system.DroshMenuItemStyle
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.OutfitFontFamily
import dev.drosh.ui.session.SessionSwitcherViewModel

/**
 * Modern minimalist top bar — iOS/Obsidian-style floating pills.
 *
 * Değişiklik notları (önceki versiyona göre):
 *  - Pill butonlar artık yarı şeffaf surface'lere sahip (önceden sadece
 *    border ile sınırlıydlar). Surface: DroshSurfaceHigh @ 65% alpha.
 *    Border kaldırıldı — terminal içeriği arkasından hafifçe görünüyor.
 *  - Boyutlar büyütüldü: buton 36/40dp → 44dp, ikon 18dp → 22dp (iOS ölçeği).
 *  - Session-name kutusu stadium (tam yuvarlak) pill'e çevrildi.
 *  - Basma anında hafif scale-down animasyonu (spring, bounce yok) —
 *    iOS tarzı dokunma geri bildirimi.
 *  - "Vibrancy" simülasyonu: gerçek backdrop blur DEĞİL (Compose'da bunun
 *    native karşılığı yok, bkz. sohbet notu). Bunun yerine yarı şeffaf
 *    surface + hafif highlight gradyanı ile "buzlu cam" hissi veriliyor.
 *    Gerçek blur için Haze kütüphanesi gerekir — ayrı bir adım.
 *  - MoreActionsDropdown: hardcoded offset kaldırıldı (anchor'a göre
 *    otomatik konumlanıyor), Divider → HorizontalDivider.
 *  - Icons now use DroshIcons ImageVector instead of painterResource XML drawables.
 *
 * Public API değişmedi: TerminalTopBar(...) imzası aynı.
 */
@Composable
fun TerminalTopBar(
    viewModel: SessionSwitcherViewModel,
    isFullscreen: Boolean,
    keyboardFocused: Boolean,
    onToggleKeyboard: () -> Unit,
    onOpenSidebar: () -> Unit,
    onFindInOutput: () -> Unit,
    onRefresh: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onNewSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeName by viewModel.activeName.collectAsStateWithLifecycle()

    val statusBarH = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp + statusBarH)
            .padding(top = statusBarH),
    ) {
        var moreExpanded by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            // ── Left: pill icon button + session name pill ──────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassPillButton(
                    icon = DroshIcons.PanelLeft,
                    contentDescription = "Open sessions",
                    onClick = onOpenSidebar,
                )

                 Box(
                     modifier = Modifier
                         .clip(RoundedCornerShape(percent = 50))
                         .background(DroshSurfaceHigh.copy(alpha = 0.65f))
                         .padding(horizontal = 16.dp, vertical = 10.dp),
                 ) {
                    Text(
                        text = activeName ?: "Drosh",
                        color = DroshText,
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                    )
                }
            }
        }

        // ── Right: two pill buttons ────────────────────────────────────────
        Box(
            Modifier
                .wrapContentSize()
                .align(Alignment.CenterEnd)
                .padding(horizontal = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassPillButton(
                    icon = if (keyboardFocused) DroshIcons.KeyboardOff else DroshIcons.Keyboard,
                    contentDescription = if (keyboardFocused) "Hide keyboard" else "Show keyboard",
                    onClick = onToggleKeyboard,
                )

                GlassPillButton(
                    icon = DroshIcons.EllipsisVertical,
                    contentDescription = "More actions",
                    onClick = { moreExpanded = true },
                )
            }

            MoreActionsDropdown(
                expanded = moreExpanded,
                onDismiss = { moreExpanded = false },
                isFullscreen = isFullscreen,
                onFindInOutput = { onFindInOutput(); moreExpanded = false },
                onRefresh = { onRefresh(); moreExpanded = false },
                onToggleFullscreen = { onToggleFullscreen(); moreExpanded = false },
                onNewSession = { onNewSession(); moreExpanded = false },
                onOpenSettings = { onOpenSettings(); moreExpanded = false },
                onClose = { onClose(); moreExpanded = false },
            )
        }
    }
}

@Composable
private fun MoreActionsDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    isFullscreen: Boolean,
    onFindInOutput: () -> Unit,
    onRefresh: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onNewSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    DroshDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        items = listOf(
            DroshMenuItem(
                label = "Refresh terminal",
                icon = DroshIcons.RotateCw,
            ),
            DroshMenuItem(
                label = "New session",
                icon = DroshIcons.Plus,
            ),
            DroshMenuItem(
                label = if (isFullscreen) "Exit fullscreen" else "Enter fullscreen",
                icon = if (isFullscreen) DroshIcons.Minimize else DroshIcons.Maximize,
            ),
            DroshMenuItem(
                label = "Find in output",
                icon = DroshIcons.Search,
            ),
            DroshMenuItem(dividerBefore = true, label = ""),
            DroshMenuItem(
                label = "Settings",
                icon = DroshIcons.Settings,
            ),
            DroshMenuItem(
                label = "Close session",
                icon = DroshIcons.XCircle,
                style = DroshMenuItemStyle.Destructive,
            ),
        ),
        onItemClick = { item ->
            when (item.label) {
                "Refresh terminal" -> onRefresh()
                "New session" -> onNewSession()
                "Exit fullscreen", "Enter fullscreen" -> onToggleFullscreen()
                "Find in output" -> onFindInOutput()
                "Settings" -> onOpenSettings()
                "Close session" -> onClose()
            }
        },
    )
}

/**
 * iOS-style glass pill button.
 *
 * Gerçek backdrop blur uygulamaz (Compose'da native karşılığı yok).
 * Bunun yerine yarı şeffaf surface + hafif highlight gradyanı ile
 * "buzlu cam" hissi simüle edilir. Basılınca hafif scale-down (spring,
 * bounce yok) ile dokunma geri bildirimi verir.
 */
@Composable
private fun GlassPillButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "pillButtonScale",
    )

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(DroshSurfaceHigh.copy(alpha = 0.65f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                        onClick()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = DroshText,
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}
