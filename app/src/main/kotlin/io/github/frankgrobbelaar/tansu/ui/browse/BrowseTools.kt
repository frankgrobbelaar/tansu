package io.github.frankgrobbelaar.tansu.ui.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.jobs.Held
import io.github.frankgrobbelaar.tansu.jobs.Job
import io.github.frankgrobbelaar.tansu.ui.format.Human
import io.github.frankgrobbelaar.tansu.ui.format.JobWords
import io.github.frankgrobbelaar.tansu.ui.kit.TansuIcons
import io.github.frankgrobbelaar.tansu.ui.theme.KiriCorners

/** The top bar while selecting: how many and how big, Select all, and a way out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(
    count: Int,
    bytes: Long,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = pluralStringResource(R.plurals.selection_count, count, count),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = Human.size(bytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(TansuIcons.Close, contentDescription = stringResource(R.string.stop_selecting))
            }
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(TansuIcons.SelectAll, contentDescription = stringResource(R.string.select_all))
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
    )
}

/** What can be done with the selection, within thumb reach. Rename and Details wait in More. */
@Composable
internal fun SelectionBar(
    single: Boolean,
    actions: SelectionActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        BarAction(TansuIcons.Cut, stringResource(R.string.action_cut), actions.onCut)
        BarAction(TansuIcons.Copy, stringResource(R.string.action_copy), actions.onCopy)
        BarAction(TansuIcons.Share, stringResource(R.string.action_share), actions.onShare)
        BarAction(TansuIcons.Delete, stringResource(R.string.action_delete), actions.onDelete)
        MoreActions(single, actions)
    }
}

/** The selection's actions, gathered so the bar's signature stays readable. */
internal class SelectionActions(
    val onCut: () -> Unit,
    val onCopy: () -> Unit,
    val onShare: () -> Unit,
    val onDelete: () -> Unit,
    val onRename: () -> Unit,
    val onDetails: () -> Unit,
    val onOpenOutside: () -> Unit,
)

@Composable
private fun MoreActions(single: Boolean, actions: SelectionActions, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        BarAction(
            TansuIcons.MoreHorizontal,
            stringResource(R.string.action_more),
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (single) {
                MenuAction(TansuIcons.Rename, R.string.action_rename) {
                    open = false
                    actions.onRename()
                }
                MenuAction(TansuIcons.Info, R.string.action_details) {
                    open = false
                    actions.onDetails()
                }
            }
            MenuAction(TansuIcons.OpenOutside, R.string.action_open_outside) {
                open = false
                actions.onOpenOutside()
            }
        }
    }
}

@Composable
private fun MenuAction(icon: ImageVector, label: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null) },
    )
}

@Composable
private fun BarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(KiriCorners.Tile)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

/** What Cut or Copy picked up, following you from folder to folder until it's pasted. */
@Composable
internal fun PasteBar(
    held: Held,
    onPaste: () -> Unit,
    onDrop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = held.picks.size
    val what =
        if (held.cut) pluralStringResource(R.plurals.held_move, count, count)
        else pluralStringResource(R.plurals.held_copy, count, count)
    val folder = held.from.label.substringAfterLast(" › ")
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(KiriCorners.Card)
                .background(MaterialTheme.colorScheme.inverseSurface)
                .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDrop) {
            Icon(
                TansuIcons.Close,
                contentDescription = stringResource(R.string.drop_held),
                tint = MaterialTheme.colorScheme.inverseOnSurface,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = what,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.inverseOnSurface,
            )
            Text(
                text = stringResource(R.string.held_from, Human.size(held.bytes), folder),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(onClick = onPaste) { Text(stringResource(R.string.paste_here)) }
    }
}

/** The job under way, at the foot of Browse: what, how far, how long. Tap for Transfers. */
@Composable
internal fun JobCard(job: Job, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(KiriCorners.Card)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = JobWords.title(job, resources),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = JobWords.detail(job, resources),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (job.progress.counting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
        } else {
            LinearProgressIndicator(
                progress = { job.progress.fraction },
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
        }
    }
}
