package dev.drosh.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.drosh.domain.session.ResourceSnapshot
import dev.drosh.theme.DroshPrimary
import dev.drosh.theme.DroshSurfaceHigh
import dev.drosh.theme.DroshSurfaceVariant
import dev.drosh.theme.DroshText
import dev.drosh.theme.DroshTextMuted
import dev.drosh.ui.R

/**
 * Bottom sheet with the numbers we actually have: CPU load, used/total RAM and
 * frame timing, all from the [ResourceSnapshot] published by
 * [SessionSwitcherViewModel]. Nullable samples render as an em dash rather
 * than a made-up zero. Stock Android exposes no GPU utilisation figure, so
 * that line says so instead of showing a fabricated percentage. The full
 * engine report stays behind [onOpenDiagnostics].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourceMonitorSheet(
    onDismiss: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val viewModel: SessionSwitcherViewModel = hiltViewModel()
    val resources by viewModel.resources.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val unavailable = stringResource(R.string.sidebar_resource_unavailable)
    val fpsText = resources.fps?.let { stringResource(R.string.sidebar_resource_fps_value, it) } ?: unavailable
    val frameText = resources.frameMs?.let { stringResource(R.string.sidebar_resource_frame_value, it) } ?: unavailable
    // Guard a zero/unknown total: an honest empty bar, never a divide by zero.
    val ramFraction = if (resources.ramTotalMb > 0) {
        (resources.ramUsedMb.toFloat() / resources.ramTotalMb.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = DroshSurfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.sidebar_resource_title),
                color = DroshText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.sidebar_resource_desc),
                color = DroshTextMuted,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard(
                    value = stringResource(R.string.sidebar_resource_cpu_value, resources.cpuPercent),
                    label = stringResource(R.string.sidebar_resource_cpu),
                    modifier = Modifier.weight(1f),
                )
                MetricCard(
                    value = fpsText,
                    label = stringResource(R.string.sidebar_resource_fps),
                    modifier = Modifier.weight(1f),
                )
            }

            Text(
                text = stringResource(
                    R.string.sidebar_resource_ram,
                    resources.ramUsedMb,
                    resources.ramTotalMb,
                ),
                color = DroshText,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
            )
            // The filled portion is the used fraction of the reported total.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(DroshSurfaceHigh),
            ) {
                if (ramFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(ramFraction)
                            .background(DroshPrimary),
                    )
                }
            }

            Text(
                text = stringResource(R.string.sidebar_resource_frame, frameText),
                color = DroshTextMuted,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = stringResource(R.string.sidebar_resource_gpu_unavailable),
                color = DroshTextMuted,
                fontSize = 12.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    // Dismiss first so the report is not opened behind the sheet.
                    onDismiss()
                    onOpenDiagnostics()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.sidebar_resource_diagnostics))
            }
            Text(
                text = stringResource(R.string.sidebar_resource_diagnostics_note),
                color = DroshTextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MetricCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(DroshSurfaceHigh)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            color = DroshText,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            color = DroshTextMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
