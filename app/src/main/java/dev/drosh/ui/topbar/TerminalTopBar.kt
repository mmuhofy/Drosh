package dev.drosh.ui.topbar

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.ui.DroshIcons
import dev.drosh.design.system.DroshBorderSubtle
import dev.drosh.design.system.DroshError
import dev.drosh.design.system.DroshSurfaceContainerLowest
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.design.system.OutfitFontFamily
import dev.drosh.ui.session.SessionSwitcherViewModel

/**
 * Modern minimalist top bar — iOS/Obsidian-style floating pills.
 *
 * Değişiklik notları (önceki versiyona göre):
 *  - Pill butonlar artık gerçek bir surface'a sahip (önceden tamamen
 *    şeffaftı, sadece basılınca hafif alpha görünüyordu).
 *  - Boyutlar büyütüldü: buton 36/40dp → 44dp, ikon 18dp → 22dp (iOS ölçeği).
 *  - Session-name kutusu stadium (tam yuvarlak) pill'e çevrildi.
 *  - Basma anında hafif scale-down animasyonu (spring, bounce yok) —
 *    iOS tarzı dokunma geri bildirimi.
 *  - "Vibrancy" simülasyonu: gerçek backdrop blur DEĞİL (Compose'da bunun
 *    native karşılığı yok, bkz. sohbet notu). Bunun yerine yarı saydam
 *    surface + ince kenarlık + üstte hafif highlight gradyanı ile
 *    "buzlu cam" hissi veriliyor. Gerçek blur için Haze kütüphanesi
 *    gerekir — ayrı bir adım olarak ele alınmalı.
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
                         .border(
                             width = 1.dp,
                             color = DroshBorderSubtle,
                             shape = RoundedCornerShape(percent = 50),
                         )
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
    // Not: hardcoded offset kaldırıldı — DropdownMenu artık anchor'ı olan
    // composable'a (bu Box) göre Compose tarafından otomatik konumlanıyor.
     DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = DroshSurfaceContainerLowest,
        tonalElevation = 8.dp,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.border(
            width = 1.dp,
            color = DroshBorderSubtle.copy(alpha = 0.2f),
            shape = RoundedCornerShape(12.dp),
        ),
    ) {
        DropdownMenuItem(
            onClick = { onRefresh() },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = DroshIcons.RotateCw,
                        contentDescription = null,
                        tint = DroshTextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Refresh terminal",
                        color = DroshText,
                        fontFamily = OutfitFontFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
        )
        DropdownMenuItem(
            onClick = { onNewSession() },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = DroshIcons.Plus,
                        contentDescription = null,
                        tint = DroshTextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "New session",
                        color = DroshText,
                        fontFamily = OutfitFontFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
        )
        DropdownMenuItem(
            onClick = { onToggleFullscreen() },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = if (isFullscreen) DroshIcons.Minimize else DroshIcons.Maximize,
                        contentDescription = null,
                        tint = DroshTextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (isFullscreen) "Exit fullscreen" else "Enter fullscreen",
                        color = DroshText,
                        fontFamily = OutfitFontFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
        )
        DropdownMenuItem(
            onClick = { onFindInOutput() },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = DroshIcons.Search,
                        contentDescription = null,
                        tint = DroshTextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Find in output",
                        color = DroshText,
                        fontFamily = OutfitFontFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
        )
        HorizontalDivider(
            color = DroshBorderSubtle,
            thickness = 1.dp,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        DropdownMenuItem(
            onClick = { onOpenSettings() },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = DroshIcons.Settings,
                        contentDescription = null,
                        tint = DroshTextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Settings",
                        color = DroshText,
                        fontFamily = OutfitFontFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
        )
        DropdownMenuItem(
            onClick = { onClose() },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = DroshIcons.XCircle,
                        contentDescription = null,
                        tint = DroshError,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Close session",
                        color = DroshError,
                        fontFamily = OutfitFontFamily,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
        )
    }
}

/**
 * iOS-style glass pill button.
 *
 * Gerçek backdrop blur uygulamaz (Compose'da native karşılığı yok).
 * Bunun yerine yarı saydam surface + ince kenarlık + üstte hafif
 * highlight gradyanı ile "buzlu cam" hissi simüle edilir. Basılınca
 * hafif scale-down (spring, bounce yok) ile dokunma geri bildirimi verir.
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
            .border(
                width = 1.dp,
                color = DroshBorderSubtle,
                shape = CircleShape,
            )
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
