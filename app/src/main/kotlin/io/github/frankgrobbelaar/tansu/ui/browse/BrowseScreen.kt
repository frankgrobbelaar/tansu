package io.github.frankgrobbelaar.tansu.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.ui.format.Human
import io.github.frankgrobbelaar.tansu.ui.kit.Crumb
import io.github.frankgrobbelaar.tansu.ui.kit.DrawerChips
import io.github.frankgrobbelaar.tansu.ui.kit.DrawerTab
import io.github.frankgrobbelaar.tansu.ui.kit.EmptyState
import io.github.frankgrobbelaar.tansu.ui.kit.FileRow
import io.github.frankgrobbelaar.tansu.ui.kit.PathBar
import io.github.frankgrobbelaar.tansu.ui.kit.TansuIcons
import io.github.frankgrobbelaar.tansu.ui.kit.TansuTopBar
import io.github.frankgrobbelaar.tansu.ui.servers.rememberLocalNetworkAccess
import kotlinx.coroutines.launch

/** Browse, wired to its ViewModel: folders open in place, files open in another app. */
@Composable
fun BrowseRoute(
    onAddServer: () -> Unit,
    onEditServer: (DrawerTab) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BrowseViewModel = viewModel(factory = BrowseViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noApp = stringResource(R.string.browse_no_app)
    val notYet = stringResource(R.string.browse_server_files_not_yet)
    var removing by rememberSaveable { mutableStateOf(false) }
    val localNetwork = rememberLocalNetworkAccess()
    BrowseEffects(
        state = state,
        snackbar = snackbar,
        onResume = viewModel::refresh,
        onUp = { viewModel.up() },
        onAnnounce = viewModel::addedShown,
    )

    Box(modifier = modifier.fillMaxSize()) {
        BrowseScreen(
            state = state,
            actions =
                BrowseActions(
                    onSelectDrawer = viewModel::select,
                    onAddServer = onAddServer,
                    onEditServer = { onEditServer(state.drawer) },
                    onRemoveServer = { removing = true },
                    onDismissOffer = viewModel::dismissServerOffer,
                    onOpen = { entry ->
                        when {
                            entry.isFolder -> viewModel.open(entry)
                            state.onServer -> scope.launch { snackbar.showSnackbar(notYet) }
                            !openOutside(context, entry) ->
                                scope.launch { snackbar.showSnackbar(noApp) }
                        }
                    },
                    onCrumb = viewModel::jumpTo,
                    onUp = { viewModel.up() },
                    onRetry = viewModel::refresh,
                    onGrantAccess = { openAllFilesAccess(context) },
                    onTrustKey = viewModel::trustNewKey,
                    onKeepBlocked = viewModel::keepBlocked,
                    onAllowLocalNetwork = { localNetwork.ask(viewModel::refresh) },
                ),
            localNetworkBlocked = !localNetwork.allowed,
        )
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
    if (removing) {
        RemoveServerDialog(
            name = state.drawer.name,
            onRemove = {
                removing = false
                viewModel.removeServer()
            },
            onCancel = { removing = false },
        )
    }
}

/**
 * What Browse does around the screen: refreshing, going up on back, and saying a server was added.
 */
@Composable
private fun BrowseEffects(
    state: BrowseUiState,
    snackbar: SnackbarHostState,
    onResume: () -> Unit,
    onUp: () -> Unit,
    onAnnounce: () -> Unit,
) {
    val added = state.added?.let { stringResource(R.string.server_added, it) }
    val resume by rememberUpdatedState(onResume)
    val announce by rememberUpdatedState(onAnnounce)
    // Coming back to tansu lists the folder again: access may have changed, or the files.
    LifecycleResumeEffect(Unit) {
        resume()
        onPauseOrDispose {}
    }
    BackHandler(enabled = !state.atRoot, onBack = onUp)
    LaunchedEffect(added) {
        if (added != null) {
            announce()
            snackbar.showSnackbar(added)
        }
    }
}

/** Everything Browse lets you do, in one place so the screen's signature stays readable. */
class BrowseActions(
    val onSelectDrawer: (String) -> Unit,
    val onAddServer: () -> Unit,
    val onEditServer: () -> Unit,
    val onRemoveServer: () -> Unit,
    val onDismissOffer: () -> Unit,
    val onOpen: (Entry) -> Unit,
    val onCrumb: (Crumb) -> Unit,
    val onUp: () -> Unit,
    val onRetry: () -> Unit,
    val onGrantAccess: () -> Unit,
    val onTrustKey: () -> Unit,
    val onKeepBlocked: () -> Unit,
    val onAllowLocalNetwork: () -> Unit,
)

/** A drawer's folder: the top bar, the drawers, the path, then the files or why there are none. */
@Composable
fun BrowseScreen(
    state: BrowseUiState,
    actions: BrowseActions,
    modifier: Modifier = Modifier,
    localNetworkBlocked: Boolean = false,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TansuTopBar(
            title = state.title,
            navigationIcon = {
                if (!state.atRoot) {
                    IconButton(onClick = actions.onUp) {
                        Icon(TansuIcons.Back, contentDescription = stringResource(R.string.back))
                    }
                }
            },
            actions = { MoreMenu(onServer = state.onServer, actions = actions) },
        )
        DrawerChips(
            drawers = state.drawers,
            selected = state.drawer.id,
            onSelect = actions.onSelectDrawer,
            onAdd = actions.onAddServer,
        )
        AnimatedVisibility(visible = state.offerServers) {
            ServerOffer(onAdd = actions.onAddServer, onDismiss = actions.onDismissOffer)
        }
        if (state.problem == null) {
            PathBar(
                crumbs = state.crumbs,
                info =
                    if (state.loading && state.entries.isEmpty()) ""
                    else Human.count(state.entries.size),
                onCrumb = actions.onCrumb,
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            when {
                state.problem != null -> ProblemState(state, actions, localNetworkBlocked)
                !state.loading && state.entries.isEmpty() ->
                    EmptyState(
                        icon = TansuIcons.Folder,
                        title = stringResource(R.string.browse_empty_title),
                        body = stringResource(R.string.browse_empty_body),
                    )
                else -> Entries(state.entries, actions.onOpen)
            }
            if (state.loading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                )
            }
        }
    }
}

@Composable
private fun MoreMenu(onServer: Boolean, actions: BrowseActions, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(TansuIcons.More, contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_server)) },
                onClick = {
                    open = false
                    actions.onAddServer()
                },
                leadingIcon = { Icon(TansuIcons.Plus, contentDescription = null) },
            )
            if (onServer) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.edit_server)) },
                    onClick = {
                        open = false
                        actions.onEditServer()
                    },
                    leadingIcon = { Icon(TansuIcons.Rename, contentDescription = null) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.remove_server)) },
                    onClick = {
                        open = false
                        actions.onRemoveServer()
                    },
                    leadingIcon = { Icon(TansuIcons.Delete, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun Entries(entries: List<Entry>, onOpen: (Entry) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp),
    ) {
        items(entries, key = { it.path }) { entry ->
            FileRow(
                entry = entry,
                meta = remember(entry) { Human.meta(entry) },
                onClick = { onOpen(entry) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}
