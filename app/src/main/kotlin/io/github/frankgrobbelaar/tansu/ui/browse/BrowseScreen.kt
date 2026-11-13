package io.github.frankgrobbelaar.tansu.ui.browse

import android.content.res.Resources
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.appContainer
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.Sort
import io.github.frankgrobbelaar.tansu.jobs.Clash
import io.github.frankgrobbelaar.tansu.ui.format.Human
import io.github.frankgrobbelaar.tansu.ui.format.JobWords
import io.github.frankgrobbelaar.tansu.ui.kit.Crumb
import io.github.frankgrobbelaar.tansu.ui.kit.DrawerChips
import io.github.frankgrobbelaar.tansu.ui.kit.DrawerTab
import io.github.frankgrobbelaar.tansu.ui.kit.EmptyState
import io.github.frankgrobbelaar.tansu.ui.kit.FileRow
import io.github.frankgrobbelaar.tansu.ui.kit.PathBar
import io.github.frankgrobbelaar.tansu.ui.kit.TansuIcons
import io.github.frankgrobbelaar.tansu.ui.kit.TansuTopBar
import io.github.frankgrobbelaar.tansu.ui.kit.icon
import io.github.frankgrobbelaar.tansu.ui.rememberNotificationAsk
import io.github.frankgrobbelaar.tansu.ui.servers.LocalNetworkAccess
import io.github.frankgrobbelaar.tansu.ui.servers.rememberLocalNetworkAccess
import io.github.frankgrobbelaar.tansu.ui.viewer.Viewer
import io.github.frankgrobbelaar.tansu.ui.viewer.Viewing
import io.github.frankgrobbelaar.tansu.ui.viewer.viewingOf
import java.io.File
import kotlinx.coroutines.launch

/**
 * Browse, wired to its ViewModel: folders open in place; pictures, videos and PDFs in tansu's
 * viewers; anything else in another app.
 */
/** Goes to a folder asked for from outside, such as where a finished job's files went, once. */
@Composable
private fun ShowRequestedFolder(onShow: (drawerId: String, folder: String) -> Unit) {
    val requests = appContainer().folderRequests
    val request by requests.collectAsStateWithLifecycle()
    val show by rememberUpdatedState(onShow)
    LaunchedEffect(request) {
        val asked = request ?: return@LaunchedEffect
        show(asked.drawerId, asked.folder)
        requests.compareAndSet(asked, null)
    }
}

@Composable
fun BrowseRoute(
    onAddServer: () -> Unit,
    onEditServer: (DrawerTab) -> Unit,
    onOpenTransfers: () -> Unit,
    onView: (Viewer) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BrowseViewModel = viewModel(factory = BrowseViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<BrowseDialog?>(null) }
    val outside = rememberOutside(snackbar)
    val localNetwork = rememberLocalNetworkAccess()
    val askNotifications = rememberNotificationAsk()
    BrowseEffects(
        state = state,
        snackbar = snackbar,
        onResume = viewModel::refresh,
        onUp = { viewModel.up() },
        onClearSelection = viewModel::clearSelection,
        onEndSearch = { viewModel.search(null) },
        onAnnounce = viewModel::addedShown,
        onClearNotice = viewModel::noticeShown,
    )
    ShowRequestedFolder(onShow = viewModel::showFolder)
    val places = Places(onAddServer, { onEditServer(state.drawer) }, onOpenTransfers, onView)
    FetchedEffect(
        fetched = state.fetched,
        outside = outside,
        onRead = { file -> onView(Viewer.Pdf(file.path, file.name, state.label)) },
        onConsume = viewModel::fetchedHandled,
    )
    Box(modifier = modifier.fillMaxSize()) {
        BrowseScreen(
            state = state,
            actions =
                viewModel.actions(state, places, outside, localNetwork, askNotifications) {
                    dialog = it
                },
            selectionActions = viewModel.selectionActions(state, outside) { dialog = it },
            localNetworkBlocked = !localNetwork.allowed && state.serverLooksLocal,
        )
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
    state.fetching?.let { FetchingDialog(it, onCancel = viewModel::cancelFetch) }
    BrowseDialogHost(
        dialog = dialog,
        state = state,
        decisions =
            DialogDecisions(
                onRemoveServer = viewModel::removeServer,
                onCreateFolder = viewModel::createFolder,
                onDelete = { askNotifications(viewModel::delete) },
                onRename = viewModel::rename,
                onPaste = { choices -> askNotifications { viewModel.paste(choices) } },
                onSort = viewModel::setSort,
                onGrid = viewModel::setGrid,
                onShowHidden = viewModel::setShowHidden,
            ),
        onDialog = { dialog = it },
    )
}

/** What Browse's dialogs can decide. */
internal class DialogDecisions(
    val onRemoveServer: () -> Unit,
    val onCreateFolder: (String) -> Unit,
    val onDelete: () -> Unit,
    val onRename: (Entry, String) -> Unit,
    val onPaste: (Map<String, Clash>) -> Unit,
    val onSort: (Sort) -> Unit,
    val onGrid: (Boolean) -> Unit,
    val onShowHidden: (Boolean) -> Unit,
)

/** Hands fetched server files to another app, once, as they arrive. */
@Composable
private fun FetchedEffect(
    fetched: BrowseUiState.Fetched?,
    outside: Outside,
    onRead: (File) -> Unit,
    onConsume: () -> Unit,
) {
    val consume by rememberUpdatedState(onConsume)
    val read by rememberUpdatedState(onRead)
    val hand by rememberUpdatedState(outside)
    LaunchedEffect(fetched) {
        if (fetched != null) {
            when (fetched.purpose) {
                BrowseUiState.Purpose.Open -> hand.open(fetched.files.first())
                BrowseUiState.Purpose.Read -> read(fetched.files.first())
                BrowseUiState.Purpose.Share -> hand.share(fetched.files)
            }
            consume()
        }
    }
}

/** Where Browse's tasks lead: other screens and places. */
private class Places(
    val onAddServer: () -> Unit,
    val onEditServer: () -> Unit,
    val onOpenTransfers: () -> Unit,
    val onView: (Viewer) -> Unit,
)

/** Handing files to other apps, saying so on [snackbar] when no app takes one. */
@Composable
private fun rememberOutside(snackbar: SnackbarHostState): Outside {
    val scope = rememberCoroutineScope()
    return Outside(
        context = LocalContext.current,
        say = { text -> scope.launch { snackbar.showSnackbar(text) } },
        noApp = stringResource(R.string.browse_no_app),
        chooser = stringResource(R.string.share_chooser),
    )
}

/** Handing files to other apps, and what to say when that can't be done. */
private class Outside(
    val context: android.content.Context,
    val say: (String) -> Unit,
    val noApp: String,
    val chooser: String,
) {
    fun open(file: File) {
        if (!openOutside(context, file)) say(noApp)
    }

    fun share(files: List<File>) {
        shareOutside(context, files, chooser)
    }
}

private fun BrowseViewModel.actions(
    state: BrowseUiState,
    places: Places,
    outside: Outside,
    localNetwork: LocalNetworkAccess,
    askNotifications: (then: () -> Unit) -> Unit,
    onDialog: (BrowseDialog) -> Unit,
) =
    BrowseActions(
        onSelectDrawer = ::select,
        onAddServer = places.onAddServer,
        onEditServer = places.onEditServer,
        onRemoveServer = { onDialog(BrowseDialog.RemoveServer) },
        onNewFolder = { onDialog(BrowseDialog.NewFolder) },
        onSortAndView = { onDialog(BrowseDialog.SortAndView) },
        onSearch = ::search,
        onDismissOffer = ::dismissServerOffer,
        onOpen = { entry ->
            when {
                state.selecting -> toggle(entry)
                entry.isFolder -> open(entry)
                else -> view(entry, state, places.onView, outside)
            }
        },
        onPick = ::toggle,
        onCrumb = ::jumpTo,
        onUp = { up() },
        onRetry = ::refresh,
        onGrantAccess = { openAllFilesAccess(outside.context) },
        onTrustKey = ::trustNewKey,
        onKeepBlocked = ::keepBlocked,
        onUseShare = ::useShare,
        onAllowLocalNetwork = { localNetwork.allow(::refresh) },
        onClearSelection = ::clearSelection,
        onSelectAll = ::selectAll,
        onPaste = {
            val clashes = clashes()
            if (clashes.isEmpty()) askNotifications { paste(emptyMap()) }
            else onDialog(BrowseDialog.Clashes(clashes))
        },
        onDropHeld = ::dropHeld,
        onOpenTransfers = places.onOpenTransfers,
    )

/** Opens [entry]: pictures, videos and PDFs in tansu, anything else in another app. */
private fun BrowseViewModel.view(
    entry: Entry,
    state: BrowseUiState,
    onView: (Viewer) -> Unit,
    outside: Outside,
) {
    val drawer = state.drawer.id
    when (viewingOf(entry)) {
        Viewing.Picture -> onView(Viewer.Picture(drawer, state.folder, entry.path))
        Viewing.Video -> onView(Viewer.Video(drawer, entry.path, entry.name, state.label))
        // Android draws PDFs from a file on the phone: a server's comes down first.
        Viewing.Pdf ->
            if (state.onServer) fetch(listOf(entry), BrowseUiState.Purpose.Read)
            else onView(Viewer.Pdf(entry.path, entry.name, state.label))
        // Another app can't read a server: the file comes to the phone first.
        null ->
            if (state.onServer) fetch(listOf(entry), BrowseUiState.Purpose.Open)
            else outside.open(File(entry.path))
    }
}

private fun BrowseViewModel.selectionActions(
    state: BrowseUiState,
    outside: Outside,
    onDialog: (BrowseDialog) -> Unit,
) =
    SelectionActions(
        onCut = ::cut,
        onCopy = ::copy,
        onShare = {
            val files = state.picked.filterNot { it.isFolder }
            when {
                files.isEmpty() -> Unit
                state.onServer -> fetch(files, BrowseUiState.Purpose.Share)
                else -> outside.share(files.map { File(it.path) })
            }
            clearSelection()
        },
        onDelete = { onDialog(BrowseDialog.Delete) },
        onRename = { state.picked.singleOrNull()?.let { onDialog(BrowseDialog.Rename(it)) } },
        onDetails = { state.picked.singleOrNull()?.let { onDialog(BrowseDialog.Details(it)) } },
        onOpenOutside = {
            state.picked
                .firstOrNull { !it.isFolder }
                ?.let { entry ->
                    if (state.onServer) fetch(listOf(entry), BrowseUiState.Purpose.Open)
                    else outside.open(File(entry.path))
                }
            clearSelection()
        },
    )

/** The dialog Browse has open, if any. */
internal sealed interface BrowseDialog {
    data object RemoveServer : BrowseDialog

    data object NewFolder : BrowseDialog

    data object SortAndView : BrowseDialog

    data object Delete : BrowseDialog

    data class Rename(val entry: Entry) : BrowseDialog

    data class Details(val entry: Entry) : BrowseDialog

    /** Clashes still to settle before pasting, and the choices made so far. */
    data class Clashes(
        val left: List<Clashing>,
        val choices: Map<String, Clash> = emptyMap(),
    ) : BrowseDialog
}

/**
 * What Browse does around the screen: refreshing, going up or leaving selection on back, and saying
 * what happened once.
 */
@Composable
private fun BrowseEffects(
    state: BrowseUiState,
    snackbar: SnackbarHostState,
    onResume: () -> Unit,
    onUp: () -> Unit,
    onClearSelection: () -> Unit,
    onEndSearch: () -> Unit,
    onAnnounce: () -> Unit,
    onClearNotice: () -> Unit,
) {
    val resources = LocalResources.current
    val added = state.added?.let { stringResource(R.string.server_added, it) }
    val notice = state.notice?.let { noticeText(it, resources) }
    val resume by rememberUpdatedState(onResume)
    val announce by rememberUpdatedState(onAnnounce)
    val clearNotice by rememberUpdatedState(onClearNotice)
    // Coming back to tansu lists the folder again: access may have changed, or the files.
    LifecycleResumeEffect(Unit) {
        resume()
        onPauseOrDispose {}
    }
    BackHandler(enabled = !state.atRoot, onBack = onUp)
    // Registered after going up, so they win: back first leaves selection, then the search.
    BackHandler(enabled = state.searching, onBack = onEndSearch)
    BackHandler(enabled = state.selecting, onBack = onClearSelection)
    // Shown first and cleared after: clearing changes the key, which would end the snackbar early.
    LaunchedEffect(added) {
        if (added != null) {
            snackbar.showSnackbar(added)
            announce()
        }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            snackbar.showSnackbar(notice)
            clearNotice()
        }
    }
}

private fun noticeText(notice: BrowseUiState.Notice, resources: Resources) =
    when (notice) {
        is BrowseUiState.Notice.JobEnded ->
            JobWords.title(notice.job, resources) + " · " + JobWords.detail(notice.job, resources)
        is BrowseUiState.Notice.Refused -> JobWords.reason(notice.reason, notice.drawer, resources)
    }

/** Everything Browse lets you do, in one place so the screen's signature stays readable. */
class BrowseActions(
    val onSelectDrawer: (String) -> Unit,
    val onAddServer: () -> Unit,
    val onEditServer: () -> Unit,
    val onRemoveServer: () -> Unit,
    val onNewFolder: () -> Unit,
    val onSortAndView: () -> Unit,
    /** Searches the folder: "" starts, null ends. */
    val onSearch: (String?) -> Unit,
    val onDismissOffer: () -> Unit,
    val onOpen: (Entry) -> Unit,
    val onPick: (Entry) -> Unit,
    val onCrumb: (Crumb) -> Unit,
    val onUp: () -> Unit,
    val onRetry: () -> Unit,
    val onGrantAccess: () -> Unit,
    val onTrustKey: () -> Unit,
    val onKeepBlocked: () -> Unit,
    /** Uses one of the server's shares in place of the drawer's, which is gone. */
    val onUseShare: (String) -> Unit,
    val onAllowLocalNetwork: () -> Unit,
    val onClearSelection: () -> Unit,
    val onSelectAll: () -> Unit,
    val onPaste: () -> Unit,
    val onDropHeld: () -> Unit,
    val onOpenTransfers: () -> Unit,
)

/** A drawer's folder: the top bar, the drawers, the path, then the files or why there are none. */
@Composable
internal fun BrowseScreen(
    state: BrowseUiState,
    actions: BrowseActions,
    selectionActions: SelectionActions,
    modifier: Modifier = Modifier,
    localNetworkBlocked: Boolean = false,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (state.selecting) {
            SelectionTopBar(
                count = state.selected.size,
                bytes = state.picked.sumOf { it.size },
                onClose = actions.onClearSelection,
                onSelectAll = actions.onSelectAll,
            )
        } else {
            if (state.searching) SearchTopBar(folder = state.title, onSearch = actions.onSearch)
            else BrowseTopBar(state, actions)
            DrawerChips(
                drawers = state.drawers,
                selected = state.drawer.id,
                onSelect = actions.onSelectDrawer,
                onAdd = actions.onAddServer,
            )
        }
        AnimatedVisibility(visible = state.offerServers && !state.selecting) {
            ServerOffer(onAdd = actions.onAddServer, onDismiss = actions.onDismissOffer)
        }
        if (state.problem == null) {
            PathBar(crumbs = state.crumbs, info = pathInfo(state), onCrumb = actions.onCrumb)
        }
        Refreshable(state, actions.onRetry, modifier = Modifier.weight(1f)) {
            FolderBody(state, actions, localNetworkBlocked)
        }
        BrowseFoot(state, actions, selectionActions)
    }
}

/**
 * The folder, pulled down to list it again. A pull shows its own spinner; any other listing shows a
 * thin bar along the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Refreshable(
    state: BrowseUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading) { if (!state.loading) pulled = false }
    PullToRefreshBox(
        isRefreshing = pulled && state.loading,
        onRefresh = {
            pulled = true
            onRefresh()
        },
        modifier = modifier,
    ) {
        content()
        if (state.loading && !pulled) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

/** How much the folder holds, or how much of it matches a search. */
@Composable
private fun pathInfo(state: BrowseUiState): String =
    when {
        state.loading && state.entries.isEmpty() -> ""
        !state.search.isNullOrEmpty() ->
            pluralStringResource(R.plurals.browse_matches, state.entries.size, state.entries.size)
        else -> Human.count(state.entries.size)
    }

/** The files, as a list or a grid, or why there are none to show. */
@Composable
private fun FolderBody(state: BrowseUiState, actions: BrowseActions, localNetworkBlocked: Boolean) {
    val empty = !state.loading && state.entries.isEmpty()
    when {
        state.problem != null -> ProblemState(state, actions, localNetworkBlocked)
        state.loading && state.slow && state.entries.isEmpty() && state.onServer ->
            EmptyState(
                icon = state.drawer.kind.icon,
                title = stringResource(R.string.browse_waiting_title, state.drawer.name),
                body = stringResource(R.string.browse_waiting_body),
            )
        empty && !state.search.isNullOrEmpty() ->
            EmptyState(
                icon = TansuIcons.Search,
                title = stringResource(R.string.search_empty_title),
                body = stringResource(R.string.search_empty_body, state.search),
            )
        empty ->
            EmptyState(
                icon = TansuIcons.Folder,
                title = stringResource(R.string.browse_empty_title),
                body = stringResource(R.string.browse_empty_body),
            )
        state.grid -> EntryGrid(state, actions)
        else -> Entries(state, actions)
    }
}

/** The job under way, then the selection's actions or the paste bar. */
@Composable
private fun BrowseFoot(
    state: BrowseUiState,
    actions: BrowseActions,
    selectionActions: SelectionActions,
) {
    state.job?.let { JobCard(job = it, onClick = actions.onOpenTransfers) }
    val held = state.held
    when {
        state.selecting ->
            SelectionBar(single = state.selected.size == 1, actions = selectionActions)
        held != null && state.problem == null ->
            PasteBar(held = held, onPaste = actions.onPaste, onDrop = actions.onDropHeld)
    }
}

@Composable
private fun BrowseTopBar(state: BrowseUiState, actions: BrowseActions) {
    TansuTopBar(
        title = state.title,
        navigationIcon = {
            if (!state.atRoot) {
                IconButton(onClick = actions.onUp) {
                    Icon(TansuIcons.Back, contentDescription = stringResource(R.string.back))
                }
            }
        },
        actions = {
            IconButton(onClick = { actions.onSearch("") }) {
                Icon(TansuIcons.Search, contentDescription = stringResource(R.string.search))
            }
            MoreMenu(onServer = state.onServer, actions = actions)
        },
    )
}

@Composable
private fun MoreMenu(onServer: Boolean, actions: BrowseActions, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val choose: (() -> Unit) -> () -> Unit = { action ->
        {
            open = false
            action()
        }
    }
    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(TansuIcons.More, contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem(TansuIcons.Sort, R.string.sort_and_view, choose(actions.onSortAndView))
            MenuItem(TansuIcons.NewFolder, R.string.new_folder, choose(actions.onNewFolder))
            MenuItem(TansuIcons.Plus, R.string.add_server, choose(actions.onAddServer))
            if (onServer) {
                MenuItem(TansuIcons.Rename, R.string.edit_server, choose(actions.onEditServer))
                MenuItem(TansuIcons.Delete, R.string.remove_server, choose(actions.onRemoveServer))
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: Int, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = onClick,
        leadingIcon = { Icon(icon, contentDescription = null) },
    )
}

@Composable
private fun Entries(state: BrowseUiState, actions: BrowseActions, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp),
    ) {
        items(state.entries, key = { it.path }) { entry ->
            FileRow(
                entry = entry,
                meta = remember(entry) { Human.meta(entry) },
                onClick = { actions.onOpen(entry) },
                onLongClick = { actions.onPick(entry) },
                selecting = state.selecting,
                selected = entry.path in state.selected,
                name = marked(entry.name, state.search),
                modifier = Modifier.animateItem(),
            )
        }
        if (!state.search.isNullOrEmpty()) item(key = "search note") { SearchNote() }
    }
}
