package dev.drosh.ui.setup.onboarding.scenes

import android.os.Build
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshSurface
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.OutfitFontFamily
import dev.drosh.ui.DroshIcons
import dev.drosh.ui.setup.onboarding.components.CheckStatus
import dev.drosh.ui.setup.onboarding.components.DeviceCheckItem
import dev.drosh.ui.setup.onboarding.components.SetupButton
import dev.drosh.ui.setup.onboarding.components.SkipAnchor
import kotlinx.coroutines.delay

@Composable
fun DeviceCheckScene(
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val archIcon = DroshIcons.Terminal
    val androidIcon = DroshIcons.Square
    val storageIcon = DroshIcons.Download
    val ramIcon = DroshIcons.SquareTerminal
    val batteryIcon = DroshIcons.SquareTerminal

    val checks = remember { mutableStateListOf<DeviceCheck>() }
    var isScanning by remember { mutableStateOf(true) }
    var canContinue by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        checks.clear()

        val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val archLabel = when (arch) {
            "arm64-v8a" -> "arm64-v8a"
            "armeabi-v7a" -> "armv7"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> "unknown"
        }
        checks.add(DeviceCheck("Mimari", archLabel, CheckStatus.Ok, archIcon))

        checks.add(DeviceCheck("Android", "Android ${Build.VERSION.SDK_INT}", CheckStatus.Ok, androidIcon))

        delay(200)

        val storageStats = context.getExternalFilesDir(null)?.let { dir ->
            val stat = android.os.StatFs(dir.absolutePath)
            val available = stat.availableBlocksLong * stat.blockSizeLong
            (available / (1024 * 1024 * 1024))
        } ?: 0L

        val storageLabel = if (storageStats >= 2) "$storageStats GB boş — yeterli" else "$storageStats GB — yeterli değil"
        val storageStatus = if (storageStats >= 2) CheckStatus.Ok else CheckStatus.Error
        checks.add(DeviceCheck("Depolama", storageLabel, storageStatus, storageIcon))

        val ramSize = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        checks.add(DeviceCheck("RAM", "${ramSize / 1024} GB", CheckStatus.Ok, ramIcon))

        delay(200)

        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val batteryPct = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryLabel = if (batteryPct >= 0) "%$batteryPct" else "bilinmiyor"
        val batteryStatus = when {
            batteryPct < 0 -> CheckStatus.Unknown
            batteryPct < 20 -> CheckStatus.Warn
            else -> CheckStatus.Ok
        }
        checks.add(DeviceCheck("Pil", batteryLabel, batteryStatus, batteryIcon))

        isScanning = false
        canContinue = checks.none { it.status == CheckStatus.Error }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "Cihaz Kontrolü",
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                ),
                color = DroshText,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isScanning) "Tarama yapılıyor..." else "Hazır",
                style = TextStyle(
                    fontFamily = OutfitFontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 14.sp,
                ),
                color = DroshTextMuted,
                modifier = Modifier.padding(horizontal = 28.dp),
            )

            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                shape = RoundedCornerShape(16.dp),
                color = DroshSurface,
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    checks.forEach { check ->
                        DeviceCheckItem(
                            icon = check.icon,
                            label = check.label,
                            value = check.value,
                            status = check.status,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            SetupButton(
                text = "Devam",
                onClick = onContinue,
                enabled = canContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
            )
            Spacer(modifier = Modifier.height(32.dp))
        }

        SkipAnchor(onSkip = onSkip)
    }
}

private data class DeviceCheck(
    val label: String,
    val value: String,
    val status: CheckStatus,
    val icon: ImageVector,
)
