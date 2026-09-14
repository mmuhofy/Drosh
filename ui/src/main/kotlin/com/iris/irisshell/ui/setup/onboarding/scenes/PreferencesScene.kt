package com.iris.irisshell.ui.setup.onboarding.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.iris.irisshell.design.system.DroshBackground
import com.iris.irisshell.design.system.DroshBorderSubtle
import com.iris.irisshell.design.system.DroshPrimary
import com.iris.irisshell.design.system.DroshSurface
import com.iris.irisshell.design.system.DroshText
import com.iris.irisshell.design.system.DroshTextMuted
import com.iris.irisshell.design.system.DroshTextSecondary
import com.iris.irisshell.design.system.OutfitFontFamily
import com.iris.irisshell.domain.terminal.PackageProfile
import com.iris.irisshell.domain.terminal.ShellChoice
import com.iris.irisshell.ui.setup.onboarding.components.PackageProfileSelector
import com.iris.irisshell.ui.setup.onboarding.components.ShellSelector
import com.iris.irisshell.ui.setup.onboarding.components.SetupButton

/**
 * Sayfa 3 — User Preferences.
 *
 * Collects three pieces of state:
 *  - userName: display name used as shell prompt (e.g. "muhofy" → "muhofy$")
 *  - shellChoice: Zsh (recommended) or Bash
 *  - packageProfile: Minimal, Developer, or Custom (with checkbox grid)
 *
 * All state is hoisted via [PreferencesState] so the parent [OnboardingScreen]
 * can pass it to [ShellSetupScene] and eventually to the bootstrap step.
 */
@Composable
fun PreferencesScene(
    state: PreferencesState,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DroshBackground)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Text(
            text = "Tercihlerin",
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
            text = "Kurulumunu özelleştir. İstersek geç, istersen derinle.",
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
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Sana ne diyelim?",
                    style = TextStyle(
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    ),
                    color = DroshTextMuted,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.userName,
                    onValueChange = { newValue ->
                        if (newValue.length <= 20) state.onUserNameChange(newValue)
                    },
                    placeholder = {
                        Text(
                            text = "Muhofy",
                            style = TextStyle(
                                fontFamily = OutfitFontFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = 14.sp,
                            ),
                            color = DroshTextMuted,
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        autoCorrect = false,
                    ),
                    textStyle = TextStyle(
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 16.sp,
                        color = DroshText,
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = DroshPrimary,
                        unfocusedBorderColor = DroshBorderSubtle,
                        focusedPlaceholderColor = DroshTextMuted,
                        unfocusedPlaceholderColor = DroshTextMuted,
                        cursorColor = DroshPrimary,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Prompt: ${if (state.userName.isNotBlank()) "${state.userName.lowercase()}$" else "—$"}",
                    style = TextStyle(
                        fontFamily = OutfitFontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = 12.sp,
                    ),
                    color = DroshTextSecondary,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            shape = RoundedCornerShape(16.dp),
            color = DroshSurface,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                ShellSelector(
                    selected = state.shellChoice,
                    onSelected = state.onShellChoiceChange,
                )
                Spacer(modifier = Modifier.height(16.dp))
                PackageProfileSelector(
                    selected = state.packageProfile,
                    customPackages = state.customPackages,
                    onProfileSelected = state.onPackageProfileChange,
                    onCustomPackageToggled = state.onCustomPackageToggled,
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        SetupButton(
            text = "Devam",
            onClick = onContinue,
            enabled = state.userName.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
        )
        Spacer(modifier = Modifier.height(32.dp))
    }
}

data class PreferencesState(
    val userName: String,
    val shellChoice: ShellChoice,
    val packageProfile: PackageProfile,
    val customPackages: Set<String>,
    val onUserNameChange: (String) -> Unit,
    val onShellChoiceChange: (ShellChoice) -> Unit,
    val onPackageProfileChange: (PackageProfile) -> Unit,
    val onCustomPackageToggled: (String) -> Unit,
)
