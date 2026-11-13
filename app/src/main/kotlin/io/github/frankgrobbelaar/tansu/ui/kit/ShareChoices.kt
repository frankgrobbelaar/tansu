package io.github.frankgrobbelaar.tansu.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The shares a server has, as pills to tap, for when the one asked for isn't there. Browse centres
 * them under its message; the server form lines them up under its note.
 */
@Composable
fun ShareChoices(
    shares: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    arrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp),
) {
    val colors = MaterialTheme.colorScheme
    FlowRow(
        modifier = modifier,
        horizontalArrangement = arrangement,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        shares.forEach { share ->
            OutlinedButton(
                onClick = { onPick(share) },
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        containerColor = colors.surfaceContainerLowest,
                        contentColor = colors.onSurface,
                    ),
                border = BorderStroke(1.dp, colors.outlineVariant),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                Text(
                    text = share,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
