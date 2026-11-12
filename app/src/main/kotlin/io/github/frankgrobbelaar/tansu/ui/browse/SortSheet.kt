package io.github.frankgrobbelaar.tansu.ui.browse

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.files.Sort
import io.github.frankgrobbelaar.tansu.files.SortBy
import io.github.frankgrobbelaar.tansu.ui.kit.TansuIcons

/**
 * Sort and view, in one sheet: every change applies the moment it's tapped. Settings shows it
 * without [grid] and [showHidden], which belong to Browse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortSheet(
    sort: Sort,
    onSort: (Sort) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    grid: Boolean? = null,
    onGrid: (Boolean) -> Unit = {},
    showHidden: Boolean? = null,
    onShowHiddenChange: (Boolean) -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        // All of it at once: it's short, and every part of it matters.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(if (grid != null) R.string.sort_and_view else R.string.sort),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            SortBys(sort, onSort)
            Part(stringResource(R.string.order)) {
                Choice(
                    options = listOf(false, true),
                    selected = sort.descending,
                    label = { orderLabel(sort.by, descending = it) },
                    onSelect = { onSort(sort.copy(descending = it)) },
                )
            }
            if (grid != null) {
                Part(stringResource(R.string.view)) {
                    Choice(
                        options = listOf(false, true),
                        selected = grid,
                        label = {
                            stringResource(if (it) R.string.view_grid else R.string.view_list)
                        },
                        icon = { if (it) TansuIcons.Grid else TansuIcons.List },
                        onSelect = onGrid,
                    )
                }
            }
            if (showHidden != null) HiddenFiles(showHidden, onShowHiddenChange)
        }
    }
}

@Composable
private fun SortBys(sort: Sort, onSort: (Sort) -> Unit) {
    Column(modifier = Modifier.selectableGroup()) {
        Label(stringResource(R.string.sort_by))
        SortBy.entries.forEach { by ->
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(
                            selected = by == sort.by,
                            role = Role.RadioButton,
                            onClick = { onSort(sort.copy(by = by)) },
                        ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                RadioButton(selected = by == sort.by, onClick = null)
                Text(
                    text = stringResource(by.label),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (by == sort.by) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun Part(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(title)
        content()
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One of two, side by side. */
@Composable
private fun Choice(
    options: List<Boolean>,
    selected: Boolean,
    label: @Composable (Boolean) -> String,
    onSelect: (Boolean) -> Unit,
    icon: ((Boolean) -> ImageVector)? = null,
) {
    val colors = MaterialTheme.colorScheme
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors =
                    SegmentedButtonDefaults.colors(
                        activeContainerColor = colors.primaryContainer,
                        activeContentColor = colors.onPrimaryContainer,
                        activeBorderColor = colors.outlineVariant,
                        inactiveContainerColor = Color.Transparent,
                        inactiveBorderColor = colors.outlineVariant,
                    ),
                icon = {
                    if (option == selected || icon == null) {
                        SegmentedButtonDefaults.Icon(active = option == selected)
                    } else {
                        Icon(icon(option), contentDescription = null, Modifier.size(18.dp))
                    }
                },
            ) {
                Text(label(option))
            }
        }
    }
}

@Composable
private fun HiddenFiles(on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = on, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.settings_show_hidden),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = on, onCheckedChange = null)
    }
}

@get:StringRes
internal val SortBy.label: Int
    get() =
        when (this) {
            SortBy.Name -> R.string.sort_name
            SortBy.Modified -> R.string.sort_modified
            SortBy.Size -> R.string.sort_size
            SortBy.Type -> R.string.sort_type
        }

/** Which way round, in the words that suit what's sorted: A to Z, newest first, largest first. */
@Composable
internal fun orderLabel(by: SortBy, descending: Boolean): String =
    stringResource(
        when (by) {
            SortBy.Name,
            SortBy.Type -> if (descending) R.string.order_z_a else R.string.order_a_z
            SortBy.Modified -> if (descending) R.string.order_newest else R.string.order_oldest
            SortBy.Size -> if (descending) R.string.order_largest else R.string.order_smallest
        }
    )
