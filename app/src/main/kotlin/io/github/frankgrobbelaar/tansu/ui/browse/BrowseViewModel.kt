package io.github.frankgrobbelaar.tansu.ui.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.frankgrobbelaar.tansu.StorageAccess
import io.github.frankgrobbelaar.tansu.TansuApplication
import io.github.frankgrobbelaar.tansu.TansuSettings
import io.github.frankgrobbelaar.tansu.files.BrowseOrder
import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.FileSystemException
import io.github.frankgrobbelaar.tansu.files.HostKey
import io.github.frankgrobbelaar.tansu.files.isWithin
import io.github.frankgrobbelaar.tansu.files.join
import io.github.frankgrobbelaar.tansu.servers.SavedServers
import io.github.frankgrobbelaar.tansu.ui.kit.Crumb
import io.github.frankgrobbelaar.tansu.ui.kit.DrawerTab
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BrowseUiState(
    val drawers: List<DrawerTab>,
    val drawer: DrawerTab,
    val crumbs: List<Crumb>,
    val entries: List<Entry> = emptyList(),
    val loading: Boolean = true,
    val problem: Problem? = null,
    /** Offer to add a server: there are none yet, and the offer wasn't turned down. */
    val offerServers: Boolean = false,
    /** A server that was just added, to say so once. */
    val added: String? = null,
) {
    val title: String
        get() = crumbs.last().name

    val atRoot: Boolean
        get() = crumbs.size == 1

    val onServer: Boolean
        get() = drawer.kind != Drawer.Kind.Phone

    sealed interface Problem {
        /** Android hasn't given tansu All files access. */
        data object NoAccess : Problem

        /** This one folder is closed to tansu, such as Android/data or a private folder. */
        data object Locked : Problem

        /** The folder isn't there any more. */
        data object Missing : Problem

        /** The phone or server failed to read the folder. */
        data object Unreadable : Problem

        /** The server didn't answer. */
        data class Unreachable(val host: String) : Problem

        /** The server turned the login down, or the private key can't be read. */
        data object LoginRefused : Problem

        /** The drawer's SMB share isn't on the server. */
        data class ShareMissing(val share: String) : Problem

        /**
         * The SFTP server sent a key other than the one trusted; [trusted] is empty if none was.
         */
        data class KeyChanged(val trusted: String, val presented: HostKey) : Problem
    }
}

/** The drawers and their folders: where Browse is, what's there, and moving around. */
class BrowseViewModel(
    private val drawers: StateFlow<List<Drawer>>,
    private val access: StorageAccess,
    private val servers: SavedServers,
    private val settings: TansuSettings,
    savedState: SavedStateHandle,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val phone = drawers.value.first()
    private val selected = savedState.getMutableStateFlow(DRAWER, phone.id)
    private val paths = savedState.getMutableStateFlow(PATHS, emptyMap<String, String>())
    private val troubled = MutableStateFlow(emptySet<String>())
    private val listing = MutableStateFlow(Listing(loading = true))
    private val added = MutableStateFlow<String?>(null)
    private var loading: Job? = null

    /** The drawer and folder on show. A drawer that's gone falls back to the phone. */
    private val here: StateFlow<Here> =
        combine(drawers, selected, paths, ::hereOf)
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                hereOf(drawers.value, selected.value, paths.value),
            )

    /** Whether to offer a first server, and the name of one just added. */
    private val news =
        combine(servers.all, settings.serverOfferDismissed, added) { saved, dismissed, added ->
            News(offerServers = saved.isEmpty() && !dismissed, added = added)
        }

    val state: StateFlow<BrowseUiState> =
        combine(drawers, here, listing, troubled, news) { drawers, here, listing, troubled, news ->
                BrowseUiState(
                    drawers = drawers.map { tabOf(it, troubled) },
                    drawer = tabOf(here.drawer, troubled),
                    crumbs = crumbsOf(here),
                    entries = listing.entries,
                    loading = listing.loading,
                    problem = listing.problem,
                    offerServers = news.offerServers,
                    added = news.added,
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_AFTER_MILLIS),
                BrowseUiState(
                    drawers = drawers.value.map { tabOf(it, emptySet()) },
                    drawer = tabOf(here.value.drawer, emptySet()),
                    crumbs = crumbsOf(here.value),
                ),
            )

    init {
        viewModelScope.launch { here.collect { load(it, fresh = true) } }
        viewModelScope.launch {
            servers.added.collect { server ->
                selected.value = server.id
                added.value = server.name
            }
        }
    }

    fun select(drawerId: String) {
        selected.value = drawerId
    }

    fun open(entry: Entry) {
        if (entry.isFolder) goTo(entry.path)
    }

    /** Goes up one folder, or says there's nowhere higher to go. */
    fun up(): Boolean {
        val crumbs = crumbsOf(here.value)
        if (crumbs.size < 2) return false
        goTo(crumbs[crumbs.lastIndex - 1].path)
        return true
    }

    fun jumpTo(crumb: Crumb) {
        goTo(crumb.path)
    }

    /** Lists the folder again, keeping what's shown until the new listing arrives. */
    fun refresh() {
        if (loading?.isActive != true) load(here.value, fresh = false)
    }

    /** Pins the key the server sent, after the user chose to trust it. */
    fun trustNewKey() {
        val problem = listing.value.problem as? BrowseUiState.Problem.KeyChanged ?: return
        val serverId = here.value.drawer.id
        viewModelScope.launch { servers.trustKey(serverId, problem.presented.fingerprint) }
    }

    /** Leaves a server whose key changed blocked, and goes back to the phone. */
    fun keepBlocked() {
        selected.value = phone.id
    }

    fun removeServer() {
        val serverId = here.value.drawer.id
        if (serverId == phone.id) return
        selected.value = phone.id
        viewModelScope.launch { servers.remove(serverId) }
    }

    fun dismissServerOffer() {
        viewModelScope.launch { settings.dismissServerOffer() }
    }

    fun addedShown() {
        added.value = null
    }

    private fun goTo(path: String) {
        val drawerId = here.value.drawer.id
        paths.update { it + (drawerId to path) }
    }

    private fun load(here: Here, fresh: Boolean) {
        loading?.cancel()
        loading = viewModelScope.launch {
            listing.update { if (fresh) Listing(loading = true) else it.copy(loading = true) }
            val result = fetch(here)
            val drawerId = here.drawer.id
            troubled.update {
                if (result.problem.isServerWide()) it + drawerId else it - drawerId
            }
            listing.value = result
        }
    }

    private suspend fun fetch(here: Here): Listing =
        if (here.drawer.kind == Drawer.Kind.Phone && !access.granted()) {
            Listing(problem = BrowseUiState.Problem.NoAccess)
        } else {
            try {
                val entries = here.drawer.fileSystem.list(here.path)
                // Sorting thousands of names is real work: keep it off the main thread.
                withContext(compute) {
                    Listing(entries.filterNot { it.isHidden }.sortedWith(BrowseOrder))
                }
            } catch (e: FileSystemException) {
                Listing(problem = problemOf(e))
            }
        }

    private fun crumbsOf(here: Here): List<Crumb> {
        val root = here.drawer.root
        val names = here.path.removePrefix(root).split('/').filter { it.isNotEmpty() }
        val paths = names.runningFold(root, ::join).drop(1)
        return listOf(Crumb(here.drawer.name, root)) + names.zip(paths, ::Crumb)
    }

    private data class News(val offerServers: Boolean, val added: String?)

    private data class Listing(
        val entries: List<Entry> = emptyList(),
        val loading: Boolean = false,
        val problem: BrowseUiState.Problem? = null,
    )

    companion object {
        private const val DRAWER = "drawer"
        private const val PATHS = "paths"
        private const val STOP_AFTER_MILLIS = 5_000L

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as TansuApplication).container
                BrowseViewModel(
                    drawers = container.drawers.all,
                    access = container.storageAccess,
                    servers = container.servers,
                    settings = container.settings,
                    savedState = createSavedStateHandle(),
                )
            }
        }
    }
}

/** Where Browse is: a drawer, and a folder inside it. */
private data class Here(val drawer: Drawer, val path: String)

private fun hereOf(drawers: List<Drawer>, selected: String, paths: Map<String, String>): Here {
    val drawer = drawers.find { it.id == selected } ?: drawers.first()
    val path = paths[drawer.id]?.takeIf { it.isWithin(drawer.root) } ?: drawer.root
    return Here(drawer, path)
}

private fun tabOf(drawer: Drawer, troubled: Set<String>) =
    DrawerTab(drawer.id, drawer.name, drawer.kind, troubled = drawer.id in troubled)

/** Every failure the file systems can report, in the words Browse uses. */
private fun problemOf(e: FileSystemException): BrowseUiState.Problem =
    when (e) {
        is FileSystemException.AccessDenied -> BrowseUiState.Problem.Locked
        is FileSystemException.NotFound -> BrowseUiState.Problem.Missing
        is FileSystemException.Unreadable -> BrowseUiState.Problem.Unreadable
        is FileSystemException.Unreachable -> BrowseUiState.Problem.Unreachable(e.host)
        is FileSystemException.AuthFailed,
        is FileSystemException.BadKey -> BrowseUiState.Problem.LoginRefused
        is FileSystemException.ShareMissing -> BrowseUiState.Problem.ShareMissing(e.share)
        is FileSystemException.HostKeyChanged ->
            BrowseUiState.Problem.KeyChanged(e.trusted, e.presented)
        is FileSystemException.HostKeyUnknown -> BrowseUiState.Problem.KeyChanged("", e.key)
    }

/** Whether a problem is the whole server's, not just one folder's. */
private fun BrowseUiState.Problem?.isServerWide() =
    this is BrowseUiState.Problem.Unreachable ||
        this is BrowseUiState.Problem.LoginRefused ||
        this is BrowseUiState.Problem.ShareMissing ||
        this is BrowseUiState.Problem.KeyChanged
