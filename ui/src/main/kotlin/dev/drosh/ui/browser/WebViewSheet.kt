package dev.drosh.ui.browser

import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.drosh.core.copyToClipboard
import dev.drosh.core.toast
import dev.drosh.design.system.DroshDropdownMenu
import dev.drosh.design.system.DroshMenuItem
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.ui.DroshIcons
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How far the toolbar must be dragged down before the sheet closes. */
private const val DISMISS_DRAG_THRESHOLD = 96f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewSheet(
    url: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var currentUrl by remember { mutableStateOf(url) }
    var progress by remember { mutableStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val webView = remember { mutableStateOf<WebView?>(null) }

    // Material3 keeps its AnchoredDraggableState internal, so the sheet cannot
    // be handed a drag gesture from outside. The sheet's own drag is switched
    // off and the toolbar drives a drag itself: the content follows the finger
    // down, and releasing past the threshold dismisses.
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val settleOffset by animateFloatAsState(
        targetValue = if (dragging) dragOffset else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "sheetSettle",
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = DroshSurface,
        tonalElevation = 0.dp,
        dragHandle = null,
        sheetGesturesEnabled = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, settleOffset.roundToInt()) }
                .navigationBarsPadding(),
        ) {
            // The only drag surface. Scrolling a page or selecting text inside
            // the WebView below must never move the sheet.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DroshSurface)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragging = true
                                dragOffset = settleOffset
                            },
                            onVerticalDrag = { _, delta ->
                                dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                            },
                            onDragEnd = {
                                dragging = false
                                if (dragOffset > DISMISS_DRAG_THRESHOLD) {
                                    scope.launch {
                                        sheetState.hide()
                                        onDismiss()
                                    }
                                } else {
                                    dragOffset = 0f
                                }
                            },
                            onDragCancel = {
                                dragging = false
                                dragOffset = 0f
                            },
                        )
                    }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SheetToolbarButton(
                    icon = DroshIcons.ArrowLeft,
                    contentDescription = "Back",
                    enabled = canGoBack,
                    onClick = { webView.value?.goBack() },
                )
                SheetToolbarButton(
                    icon = DroshIcons.ArrowRight,
                    contentDescription = "Forward",
                    enabled = canGoForward,
                    onClick = { webView.value?.goForward() },
                )
                SheetToolbarButton(
                    icon = DroshIcons.RotateCw,
                    contentDescription = "Reload",
                    enabled = true,
                    onClick = { webView.value?.reload() },
                )

                Text(
                    text = currentUrl,
                    color = DroshTextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                )

                SheetToolbarButton(
                    icon = DroshIcons.X,
                    contentDescription = "Close browser",
                    enabled = true,
                    onClick = onDismiss,
                )
                SheetToolbarButton(
                    icon = DroshIcons.EllipsisVertical,
                    contentDescription = "Browser menu",
                    enabled = true,
                    onClick = { showMenu = true },
                )

                DroshDropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    items = listOf(
                        DroshMenuItem(label = "Copy URL", icon = DroshIcons.Copy),
                        DroshMenuItem(
                            label = "Open in Browser",
                            icon = DroshIcons.Globe,
                            dividerBefore = true,
                        ),
                        DroshMenuItem(label = "Reload", icon = DroshIcons.RotateCw),
                    ),
                    onItemClick = { item ->
                        showMenu = false
                        when (item.label) {
                            "Copy URL" -> {
                                context.copyToClipboard("URL", currentUrl)
                                context.toast("URL copied")
                            }
                            "Open in Browser" -> {
                                val intent =
                                    Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                            "Reload" -> {
                                webView.value?.reload()
                            }
                        }
                    },
                )
            }

            if (progress < 1f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = DroshPrimary,
                    trackColor = Color.Transparent,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize(),
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            settings.offscreenPreRaster = true
                            settings.setCacheMode(WebSettings.LOAD_DEFAULT)
                            settings.safeBrowsingEnabled = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.mixedContentMode =
                                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            settings.userAgentString = "Drosh/1.0"

                            setLayerType(View.LAYER_TYPE_HARDWARE, null)
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)

                            webView.value = this

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean {
                                    val newUrl = request.url.toString()
                                    currentUrl = newUrl
                                    view.loadUrl(newUrl)
                                    return true
                                }

                                override fun onPageFinished(
                                    view: WebView,
                                    loadedUrl: String,
                                ) {
                                    currentUrl = loadedUrl
                                    canGoBack = view.canGoBack()
                                    canGoForward = view.canGoForward()
                                    progress = 1f
                                }
                            }

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(
                                    view: WebView,
                                    newProgress: Int,
                                ) {
                                    progress = newProgress / 100f
                                    canGoBack = view.canGoBack()
                                    canGoForward = view.canGoForward()
                                }
                            }

                            loadUrl(url)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView.value?.destroy()
        }
    }
}

/**
 * The one toolbar button, used by back, forward, reload, close and the menu.
 *
 * These were two separate composables that had drifted apart: the navigation
 * icons were 22dp inside 10dp of padding and the trailing icons 20dp, so the
 * row showed two different circle sizes. All five now share one 40dp circle.
 */
@Composable
private fun SheetToolbarButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint =
        if (enabled) DroshText else DroshTextSecondary.copy(alpha = 0.35f)
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(DroshSurface.copy(alpha = 0.75f), CircleShape)
            .clickable(enabled = enabled) { onClick() }
            .padding(10.dp),
    )
}
