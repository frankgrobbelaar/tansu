package io.github.frankgrobbelaar.tansu.ui.kit

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.ui.theme.KiriCorners

/** The row of drawers under the top bar: the phone, then each server. */
@Composable
fun DrawerChips(
    drawers: List<Drawer>,
    selected: Drawer,
    onSelect: (Drawer) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        drawers.forEach { drawer ->
            DrawerChip(
                name = drawer.name,
                icon = drawer.kind.icon,
                selected = drawer.id == selected.id,
                onClick = { onSelect(drawer) },
            )
        }
    }
}

@Composable
private fun DrawerChip(
    name: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        modifier = modifier.height(36.dp),
        leadingIcon = {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
        },
        shape = KiriCorners.Chip,
        colors =
            FilterChipDefaults.filterChipColors(
                labelColor = colors.onSurfaceVariant,
                iconColor = colors.onSurfaceVariant,
                selectedContainerColor = colors.primaryContainer,
                selectedLabelColor = colors.onPrimaryContainer,
                selectedLeadingIconColor = colors.onPrimaryContainer,
            ),
        border =
            FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected,
                borderColor = colors.outlineVariant,
                selectedBorderColor = Color.Transparent,
            ),
    )
}

val Drawer.Kind.icon: ImageVector
    get() =
        when (this) {
            Drawer.Kind.Phone -> TansuIcons.Phone
            Drawer.Kind.Smb -> TansuIcons.Nas
            Drawer.Kind.Sftp -> TansuIcons.Server
        }
