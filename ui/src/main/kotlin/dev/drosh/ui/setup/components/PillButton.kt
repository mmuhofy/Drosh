package dev.drosh.ui.setup.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshOnPrimary
import dev.drosh.design.system.DroshPrimary
import dev.drosh.design.system.OutfitFontFamily

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = DroshPrimary,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = DroshOnPrimary,
            disabledContainerColor = DroshPrimary.copy(alpha = 0.3f),
            disabledContentColor = DroshOnPrimary.copy(alpha = 0.5f),
        ),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            ),
        )
    }
}

@Composable
fun PillButtonWithIcon(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = DroshPrimary,
            contentColor = DroshOnPrimary,
            disabledContainerColor = DroshPrimary.copy(alpha = 0.3f),
            disabledContentColor = DroshOnPrimary.copy(alpha = 0.5f),
        ),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            ),
        )
        if (icon != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = DroshOnPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
fun PillButtonFull(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = DroshPrimary,
    contentPadding: PaddingValues = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = DroshOnPrimary,
            disabledContainerColor = DroshPrimary.copy(alpha = 0.3f),
            disabledContentColor = DroshOnPrimary.copy(alpha = 0.5f),
        ),
        contentPadding = contentPadding,
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontFamily = OutfitFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            ),
        )
    }
}
