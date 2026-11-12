package io.github.frankgrobbelaar.tansu.ui.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.ui.browse.NameDialog
import io.github.frankgrobbelaar.tansu.ui.format.Human
import io.github.frankgrobbelaar.tansu.ui.kit.DrawerChips
import io.github.frankgrobbelaar.tansu.ui.kit.EmptyState
import io.github.frankgrobbelaar.tansu.ui.kit.FileRow
import io.github.frankgrobbelaar.tansu.ui.kit.PathBar
import io.github.frankgrobbelaar.tansu.ui.kit.TansuIcons

/** Pick a drawer and a folder for what was shared, then Save here. */
@Composable
fun SaveToScreen(state: SaveToUiState, actions: SaveToActions, modifier: Modifier = Modifier) {
    var naming by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize()) {
        SaveToTopBar(state, actions.onClose)
        DrawerChips(
            drawers = state.drawers,
            selected = state.drawer.id,
            onSelect = actions.onSelectDrawer,
        )
        PathBar(
            crumbs = state.crumbs,
            info = stringResource(R.string.save_to_pick),
            onCrumb = actions.onCrumb,
        )
        Box(modifier = Modifier.weight(1f)) {
            if (state.unreachable) {
                EmptyState(
                    icon = TansuIcons.Offline,
                    title = stringResource(R.string.save_to_unreachable_title, state.drawer.name),
                    body = stringResource(R.string.save_to_unreachable_body),
                )
            } else if (!state.loading) {
                // Drawn once it's listed, so the list starts at its top.
                Folders(state.folders, onOpen = actions.onOpen, onNewFolder = { naming = true })
            }
            if (state.loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                )
            }
        }
        SaveBar(state, actions.onSave)
    }
    if (naming) {
        NameDialog(
            title = stringResource(R.string.new_folder),
            confirm = stringResource(R.string.create),
            onConfirm = { name ->
                naming = false
                actions.onNewFolder(name)
            },
            onCancel = { naming = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveToTopBar(state: SaveToUiState, onClose: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text =
                        pluralStringResource(
                            R.plurals.save_to_title,
                            state.picks.size,
                            state.picks.size,
                        ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    // Not every app says how big a shared file is.
                    text =
                        if (state.bytes > 0) {
                            stringResource(R.string.held_from, Human.size(state.bytes), state.from)
                        } else {
                            stringResource(R.string.held_from_only, state.from)
                        },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(TansuIcons.Close, contentDescription = stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun Folders(
    folders: List<Entry>,
    onOpen: (Entry) -> Unit,
    onNewFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp),
    ) {
        items(folders, key = { it.path }) { folder ->
            FileRow(
                entry = folder,
                meta = remember(folder) { Human.meta(folder) },
                onClick = { onOpen(folder) },
            )
        }
        item(key = "new folder") {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .height(56.dp)
                        .clickable(role = Role.Button, onClick = onNewFolder)
                        .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    TansuIcons.NewFolder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.new_folder),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun SaveBar(state: SaveToUiState, onSave: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
        Column(
            modifier =
                Modifier.navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onSave,
                enabled = state.ready,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(stringResource(R.string.save_here))
            }
            Text(
                text =
                    if (state.crumbs.size == 1)
                        stringResource(R.string.save_into_drawer, state.drawer.name)
                    else stringResource(R.string.save_into, state.folderName, state.drawer.name),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
