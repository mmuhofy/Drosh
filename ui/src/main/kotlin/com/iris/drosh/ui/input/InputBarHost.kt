package com.iris.irisshell.ui.input

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.iris.irisshell.domain.input.InputIntent

/**
 * Hosts the keyboard handle and the floating Drosh Liquid Glass extra-key
 * surface immediately above the IME.
 *
 * terminalView is supplied only so the Liquid Glass surface can sample the
 * classic TerminalView behind it. Input handling remains entirely inside the
 * existing InputBarViewModel/InputIntent architecture.
 */
@Composable
fun InputBarHost(
    uiState: InputBarUiState,
    onToggle: () -> Unit,
    onIntent: (InputIntent) -> Unit,
    terminalView: View?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        if (uiState.renderBar) {
            KeyboardHandle(
                barVisible = true,
                onToggle = onToggle,
            )

            ExtraKeyBar(
                ctrlStuck = uiState.ctrlStuck,
                altStuck = uiState.altStuck,
                terminalView = terminalView,
                onIntent = onIntent,
            )
        } else {
            KeyboardHandle(
                barVisible = false,
                onToggle = onToggle,
            )
        }
    }
}