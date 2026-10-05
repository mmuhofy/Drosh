package dev.drosh.ui.workspace.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.drosh.design.system.DroshBackground
import dev.drosh.design.system.DroshOutline
import dev.drosh.design.system.DroshSurfaceHigh
import dev.drosh.design.system.DroshText
import dev.drosh.design.system.DroshTextMuted
import dev.drosh.design.system.DroshTextSecondary
import dev.drosh.domain.workspace.Workspace
import dev.drosh.ui.DroshIcons

/**
 * Move a session into a project, or out of one.
 *
 * The first row is always "no project" and it is *selected* rather than hidden
 * when the session has no workspace — moving something out of a project is a
 * normal thing to want, and a sheet whose list starts with the workspaces would
 * make it the one option with no row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceAssignSheet(
    sessionName: String,
    currentWorkspaceId: String?,
    workspaces: List<Workspace>,
    onSelect: (String?) -> Unit,
    onCreateProject: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = DroshBackground,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 18.dp),
        ) {
            Text(
                text = "Projeye taşı",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = DroshText,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = sessionName,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = DroshTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))

            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AssignRow(
                    label = "Projesiz bırak",
                    accent = null,
                    selected = currentWorkspaceId == null,
                    onClick = { onSelect(null) },
                )

                workspaces.forEach { workspace ->
                    AssignRow(
                        label = workspace.name,
                        sublabel = workspace.rootPath,
                        accent = workspaceSeedColor(workspace.colorSeed),
                        selected = workspace.id == currentWorkspaceId,
                        onClick = { onSelect(workspace.id) },
                    )
                }

                if (workspaces.isEmpty()) {
                    // Not a dead end. Filing a session is how people find out
                    // that projects exist at all, so the sheet that introduces
                    // them has to be able to make one — otherwise it says "create
                    // a project first" and the session they were filing stays
                    // unfiled until they find another way in.
                    Text(
                        text = "Henüz proje yok.",
                        fontSize = 12.sp,
                        color = DroshTextSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                    AssignRow(
                        label = "Yeni proje oluştur",
                        sublabel = "ve bu session'ı içine koy",
                        accent = DroshPrimary,
                        selected = false,
                        showCheck = false,
                        onClick = onCreateProject,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssignRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    sublabel: String? = null,
    accent: Color?,
    showCheck: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) DroshSurfaceHigh else DroshBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics {
                contentDescription = buildString {
                    append(label)
                    if (sublabel != null) append(", ").append(sublabel)
                    if (selected) append(", seçili")
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(accent ?: DroshOutline),
        )
        Spacer(Modifier.width(11.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 14.sp,
                color = DroshText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sublabel != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = sublabel,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = DroshTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (selected && showCheck) {
            Icon(
                imageVector = DroshIcons.Check,
                contentDescription = null,
                tint = DroshTextSecondary,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}