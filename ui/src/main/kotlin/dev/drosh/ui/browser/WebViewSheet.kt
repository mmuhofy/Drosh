package dev.drosh.ui.browser

import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.drosh.core.copyToClipboard
import dev.drosh.core.toast
import dev.drosh.design.system.DroshBorderSubtle
import dev.drosh.design.system.DroshDropdownMenu
import dev.drosh.design.system.DroshMenuItem
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.ui.DroshIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebViewSheet(
    url: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    var currentUrl by remember { mutableStateOf(url) }
    var progress by remember { mutableStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    val webView = remember { mutableStateOf<WebView?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = DroshSurface,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(DroshBorderSubtle.copy(alpha = 0.4f)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DroshSurface)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolbarIcon(
                    icon = DroshIcons.ArrowLeft,
                    contentDescription = "Back",
                    enabled = canGoBack,
                    onClick = { webView.value?.goBack() },
                )
                ToolbarIcon(
                    icon = DroshIcons.ArrowRight,
                    contentDescription = "Forward",
                    enabled = canGoForward,
                    onClick = { webView.value?.goForward() },
                )
                ToolbarIcon(
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

                IconButton(
                    icon = DroshIcons.X,
                    contentDescription = "Close browser",
                    onClick = onDismiss,
                )
                IconButton(
                    icon = DroshIcons.EllipsisVertical,
                    contentDescription = "Browser menu",
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
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            change.consume()
                        }
                    },
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.javaScriptCanOpenWindowsEnabled = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
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

@Composable
private fun ToolbarIcon(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint =
        if (enabled) DroshTextSecondary else DroshTextSecondary.copy(alpha = 0.35f)
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(DroshSurface.copy(alpha = 0.75f), CircleShape)
            .clickable(enabled = enabled) { onClick() }
            .padding(6.dp),
    )
}

@Composable
private fun IconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = DroshText,
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(DroshSurface.copy(alpha = 0.75f), CircleShape)
            .clickable { onClick() }
            .padding(7.dp),
    )
}
