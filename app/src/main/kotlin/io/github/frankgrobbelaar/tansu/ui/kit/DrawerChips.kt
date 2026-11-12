package io.github.frankgrobbelaar.tansu.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.ui.theme.KiriCorners

/** The row of drawers under the top bar: the phone, each server, then Add a server. */
@Composable
fun DrawerChips(
    drawers: List<DrawerTab>,
    selected: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
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
                drawer = drawer,
                selected = drawer.id == selected,
                onClick = { onSelect(drawer.id) },
            )
        }
        AddChip(onClick = onAdd)
    }
}

@Composable
private fun DrawerChip(
    drawer: DrawerTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val troubled = stringResource(R.string.drawer_troubled, drawer.name)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = drawer.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        modifier =
            modifier.height(CHIP_HEIGHT).semantics {
                if (drawer.troubled) contentDescription = troubled
            },
        leadingIcon = {
            Icon(
                imageVector = drawer.kind.icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        trailingIcon =
            if (drawer.troubled) {
                {
                    Box(modifier = Modifier.size(7.dp).background(colors.error, CircleShape))
                }
            } else {
                null
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

/** Add a server, drawn with a dashed edge: a drawer that isn't there yet. */
@Composable
private fun AddChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val edge = colors.outline
    AssistChip(
        onClick = onClick,
        label = {
            Text(
                text = stringResource(R.string.add_server),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
        modifier =
            modifier.height(CHIP_HEIGHT).drawBehind {
                // The chip's touch target is taller than the chip; the edge follows the chip.
                val stroke = 1.dp.toPx()
                val height = CHIP_HEIGHT.toPx()
                val dashes = floatArrayOf(4.dp.toPx(), 3.dp.toPx())
                drawRoundRect(
                    color = edge,
                    topLeft = Offset(stroke / 2, (size.height - height + stroke) / 2),
                    size = Size(size.width - stroke, height - stroke),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(dashes)),
                )
            },
        leadingIcon = {
            Icon(
                imageVector = TansuIcons.Plus,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        },
        shape = KiriCorners.Chip,
        colors =
            AssistChipDefaults.assistChipColors(
                labelColor = colors.primary,
                leadingIconContentColor = colors.primary,
            ),
        border = null,
    )
}

private val CHIP_HEIGHT = 36.dp

val Drawer.Kind.icon: ImageVector
    get() =
        when (this) {
            Drawer.Kind.Phone -> TansuIcons.Phone
            Drawer.Kind.Smb -> TansuIcons.Nas
            Drawer.Kind.Sftp -> TansuIcons.Server
        }
