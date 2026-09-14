package dev.drosh.ui.browser

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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.drosh.design.system.DroshBackground
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
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var currentUrl by remember { mutableStateOf(url) }

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
                    .background(DroshText.copy(alpha = 0.15f)),
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
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = currentUrl,
                    color = DroshTextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                )
                Box(
                    modifier = Modifier
                        .background(DroshBackground.copy(alpha = 0.7f), CircleShape)
                        .clickable { onDismiss() }
                        .padding(8.dp),
                ) {
                    Icon(
                        imageVector = DroshIcons.X,
                        contentDescription = "Close browser",
                        tint = DroshText,
                        modifier = Modifier.size(18.dp),
                    )
                }
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
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.loadWithOverviewMode = true
                            settings.mixedContentMode =
                                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            settings.userAgentString = "Drosh/1.0"

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
                                }
                            }

                            webChromeClient = WebChromeClient()
                            setBackgroundColor(
                                android.graphics.Color.parseColor("#000000"),
                            )
                            loadUrl(url)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
