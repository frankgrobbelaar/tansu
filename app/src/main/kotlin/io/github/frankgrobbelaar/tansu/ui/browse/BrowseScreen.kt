package io.github.frankgrobbelaar.tansu.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.appContainer
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.ui.format.Human
import io.github.frankgrobbelaar.tansu.ui.kit.Crumb
import io.github.frankgrobbelaar.tansu.ui.kit.DrawerChips
import io.github.frankgrobbelaar.tansu.ui.kit.EmptyState
import io.github.frankgrobbelaar.tansu.ui.kit.FileRow
import io.github.frankgrobbelaar.tansu.ui.kit.PathBar
import io.github.frankgrobbelaar.tansu.ui.kit.TansuIcons
import io.github.frankgrobbelaar.tansu.ui.kit.TansuTopBar
import kotlinx.coroutines.launch

/** Browse, wired to its ViewModel: folders open in place, files open in another app. */
@Composable
fun BrowseRoute(
    modifier: Modifier = Modifier,
    viewModel: BrowseViewModel = viewModel(factory = BrowseViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val phone = appContainer().phone
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val noApp = stringResource(R.string.browse_no_app)

    // Coming back to tansu lists the folder again: access may have changed, or the files.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    BackHandler(enabled = !state.atRoot) { viewModel.up() }

    Box(modifier = modifier.fillMaxSize()) {
        BrowseScreen(
            state = state,
            drawerChips = { DrawerChips(drawers = listOf(phone), selected = phone, onSelect = {}) },
            onOpen = { entry ->
                if (entry.isFolder) viewModel.open(entry)
                else if (!openOutside(context, entry)) scope.launch { snackbar.showSnackbar(noApp) }
            },
            onCrumb = viewModel::jumpTo,
            onUp = { viewModel.up() },
            onGrantAccess = { context.startActivity(allFilesAccessIntent(context)) },
        )
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** A drawer's folder: the top bar, the drawers, the path, then the files or why there are none. */
@Composable
fun BrowseScreen(
    state: BrowseUiState,
    drawerChips: @Composable () -> Unit,
    onOpen: (Entry) -> Unit,
    onCrumb: (Crumb) -> Unit,
    onUp: () -> Unit,
    onGrantAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        TansuTopBar(
            title = state.title,
            navigationIcon = {
                if (!state.atRoot) {
                    IconButton(onClick = onUp) {
                        Icon(TansuIcons.Back, contentDescription = stringResource(R.string.back))
                    }
                }
            },
        )
        drawerChips()
        PathBar(
            crumbs = state.crumbs,
            info = if (state.problem == null) Human.count(state.entries.size) else "",
            onCrumb = onCrumb,
        )
        Box(modifier = Modifier.weight(1f)) {
            when {
                state.problem != null -> ProblemState(state.problem, onGrantAccess, onUp)
                !state.loading && state.entries.isEmpty() ->
                    EmptyState(
                        icon = TansuIcons.Folder,
                        title = stringResource(R.string.browse_empty_title),
                        body = stringResource(R.string.browse_empty_body),
                    )
                else -> Entries(state.entries, onOpen)
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

@Composable
private fun ProblemState(
    problem: BrowseUiState.Problem,
    onGrantAccess: () -> Unit,
    onUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (problem) {
        BrowseUiState.Problem.NoAccess ->
            EmptyState(
                icon = TansuIcons.Lock,
                title = stringResource(R.string.browse_no_access_title),
                body = stringResource(R.string.browse_no_access_body),
                modifier = modifier,
                action = {
                    Button(onClick = onGrantAccess) { Text(stringResource(R.string.grant_access)) }
                },
            )
        BrowseUiState.Problem.Locked ->
            EmptyState(
                icon = TansuIcons.Lock,
                title = stringResource(R.string.browse_locked_title),
                body = stringResource(R.string.browse_locked_body),
                modifier = modifier,
                action = { Button(onClick = onUp) { Text(stringResource(R.string.go_up)) } },
            )
        BrowseUiState.Problem.Missing ->
            EmptyState(
                icon = TansuIcons.MissingFolder,
                title = stringResource(R.string.browse_missing_title),
                body = stringResource(R.string.browse_missing_body),
                modifier = modifier,
                action = { Button(onClick = onUp) { Text(stringResource(R.string.go_up)) } },
            )
    }
}
