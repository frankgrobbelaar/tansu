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
import io.github.frankgrobbelaar.tansu.files.FileSystem
import io.github.frankgrobbelaar.tansu.files.FileSystemException
import io.github.frankgrobbelaar.tansu.files.HostKey
import io.github.frankgrobbelaar.tansu.files.isWithin
import io.github.frankgrobbelaar.tansu.files.join
import io.github.frankgrobbelaar.tansu.jobs.Clash
import io.github.frankgrobbelaar.tansu.jobs.Clipboard
import io.github.frankgrobbelaar.tansu.jobs.Failure
import io.github.frankgrobbelaar.tansu.jobs.Held
import io.github.frankgrobbelaar.tansu.jobs.Job
import io.github.frankgrobbelaar.tansu.jobs.Jobs
import io.github.frankgrobbelaar.tansu.jobs.Where
import io.github.frankgrobbelaar.tansu.jobs.reasonOf
import io.github.frankgrobbelaar.tansu.servers.SavedServers
import io.github.frankgrobbelaar.tansu.servers.Server
import io.github.frankgrobbelaar.tansu.ui.kit.Crumb
import io.github.frankgrobbelaar.tansu.ui.kit.DrawerTab
import io.github.frankgrobbelaar.tansu.ui.servers.LocalNetwork
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
    /** The paths picked in this folder; picking anything turns on selection. */
    val selected: Set<String> = emptySet(),
    /** What Cut or Copy picked up, waiting to be pasted. */
    val held: Held? = null,
    /** The job running now, or next. */
    val job: Job? = null,
    /** Something to say once, such as how a job ended. */
    val notice: Notice? = null,
    /** How the drawer's files are addressed outside tansu, such as smb://nas/Media. */
    val address: String = "",
    /** Whether the drawer's server is on the home network, by its name. */
    val serverLooksLocal: Boolean = false,
) {
    val selecting: Boolean
        get() = selected.isNotEmpty()

    val picked: List<Entry>
        get() = entries.filter { it.path in selected }

    /** The folder as a person reads it, such as "NAS › Media › Photos". */
    val label: String
        get() = crumbs.joinToString(" › ") { it.name }

    sealed interface Notice {
        data class JobEnded(val job: Job) : Notice

        /** A rename or new folder in [drawer] didn't happen, and why. */
        data class Refused(val reason: Failure.Reason, val drawer: String) : Notice
    }

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
    private val jobs: Jobs,
    private val clipboard: Clipboard,
    savedState: SavedStateHandle,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val phone = drawers.value.first()
    private val drawerId = savedState.getMutableStateFlow(DRAWER, phone.id)
    private val paths = savedState.getMutableStateFlow(PATHS, emptyMap<String, String>())
    private val troubled = MutableStateFlow(emptySet<String>())
    private val listing = MutableStateFlow(Listing(loading = true))
    private val added = MutableStateFlow<String?>(null)
    private val picked = MutableStateFlow(emptySet<String>())
    private val notice = MutableStateFlow<BrowseUiState.Notice?>(null)
    private var loading: kotlinx.coroutines.Job? = null

    /** The drawer and folder on show. A drawer that's gone falls back to the phone. */
    private val here: StateFlow<Here> =
        combine(drawers, drawerId, paths, ::hereOf)
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                hereOf(drawers.value, drawerId.value, paths.value),
            )

    /** Whether to offer a first server, and the name of one just added. */
    private val news =
        combine(servers.all, settings.serverOfferDismissed, added, drawers) {
            saved,
            dismissed,
            added,
            drawers ->
            News(offerServers = saved.isEmpty() && !dismissed, added = added, drawers = drawers)
        }

    /** Selection, the clipboard, the job under way, and anything to say. */
    private val tools =
        combine(picked, clipboard.contents, jobs.all, notice) { picked, held, all, notice ->
            Tools(picked, held, all.firstOrNull { it.active }, notice)
        }

    val state: StateFlow<BrowseUiState> =
        combine(here, listing, troubled, news, tools) { here, listing, troubled, news, tools ->
                BrowseUiState(
                    drawers = news.drawers.map { tabOf(it, troubled) },
                    drawer = tabOf(here.drawer, troubled),
                    crumbs = crumbsOf(here),
                    entries = listing.entries,
                    loading = listing.loading,
                    problem = listing.problem,
                    offerServers = news.offerServers,
                    added = news.added,
                    selected = tools.picked,
                    held = tools.held,
                    job = tools.job,
                    notice = tools.notice,
                    address = addressOf(here.drawer),
                    serverLooksLocal =
                        here.drawer.server?.let { LocalNetwork.looksLocal(it.host) } == true,
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
        viewModelScope.launch {
            here.collect {
                picked.value = emptySet()
                load(it, fresh = true)
            }
        }
        viewModelScope.launch {
            servers.added.collect { server ->
                drawerId.value = server.id
                added.value = server.name
            }
        }
        viewModelScope.launch { watchJobs() }
    }

    fun select(drawerId: String) {
        this.drawerId.value = drawerId
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
        drawerId.value = phone.id
    }

    fun removeServer() {
        val serverId = here.value.drawer.id
        if (serverId == phone.id) return
        drawerId.value = phone.id
        viewModelScope.launch { servers.remove(serverId) }
    }

    // Selecting and managing files

    fun toggle(entry: Entry) {
        picked.update { if (entry.path in it) it - entry.path else it + entry.path }
    }

    fun selectAll() {
        picked.value = listing.value.entries.map { it.path }.toSet()
    }

    fun clearSelection() {
        picked.value = emptySet()
    }

    fun cut() = hold(cut = true)

    fun copy() = hold(cut = false)

    fun dropHeld() {
        clipboard.clear()
    }

    /** What's held whose name is already in this folder: the user settles each before pasting. */
    fun clashes(): List<Clashing> {
        val held = clipboard.contents.value ?: return emptyList()
        val here = listing.value.entries.associateBy { it.name }
        return held.picks.mapNotNull { pick -> here[pick.name]?.let { Clashing(it, pick) } }
    }

    /** Starts copying or moving what's held into this folder, settling clashes as [choices] say. */
    fun paste(choices: Map<String, Clash>) {
        val held = clipboard.contents.value ?: return
        val to = whereHere()
        if (held.cut) jobs.move(held.picks, held.from, to, choices)
        else jobs.copy(held.picks, held.from, to, choices)
        clipboard.clear()
    }

    fun delete() {
        jobs.delete(state.value.picked, whereHere())
        clearSelection()
    }

    /** Renames [entry] to [name] in its folder. The extension is the caller's to keep. */
    fun rename(entry: Entry, name: String) {
        clearSelection()
        change { files -> files.rename(entry.path, join(here.value.path, name)) }
    }

    fun createFolder(name: String) {
        change { files -> files.createFolder(join(here.value.path, name)) }
    }

    fun noticeShown() {
        notice.value = null
    }

    private fun hold(cut: Boolean) {
        clipboard.hold(state.value.picked, whereHere(), cut)
        clearSelection()
    }

    /** A quick change to this folder, then a fresh listing; a refusal is said once. */
    private fun change(block: suspend (FileSystem) -> Unit) {
        viewModelScope.launch {
            try {
                block(here.value.drawer.fileSystem)
            } catch (e: FileSystemException) {
                notice.value = BrowseUiState.Notice.Refused(reasonOf(e), here.value.drawer.name)
            }
            load(here.value, fresh = false)
        }
    }

    private fun whereHere(): Where {
        val here = here.value
        return Where(here.drawer.id, here.path, here.drawer.name, state.value.label)
    }

    /** Lists the folder again when a job that touched this drawer ends, and says how it went. */
    private suspend fun watchJobs() {
        var ended = jobs.all.value.filterNot { it.active }.map { it.id }.toSet()
        jobs.all.collect { all ->
            val newlyEnded = all.filter { !it.active && it.id !in ended }
            ended = ended + newlyEnded.map { it.id }
            newlyEnded.lastOrNull()?.let { job ->
                if (job.status == Job.Status.Done) notice.value = BrowseUiState.Notice.JobEnded(job)
                val drawer = here.value.drawer.id
                if (job.from.drawerId == drawer || job.to?.drawerId == drawer) refresh()
            }
        }
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
            // smbj and sshj can't be stopped part-way: a listing someone moved on from may still
            // finish, or fail. It mustn't land on the folder they're looking at now.
            ensureActive()
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

    private data class News(
        val offerServers: Boolean,
        val added: String?,
        val drawers: List<Drawer>,
    )

    private data class Tools(
        val picked: Set<String>,
        val held: Held?,
        val job: Job?,
        val notice: BrowseUiState.Notice?,
    )

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
                    jobs = container.jobs,
                    clipboard = container.clipboard,
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

/** A name already in the folder, [here], and the picked one that would land on it. */
data class Clashing(val here: Entry, val incoming: Entry)

/** How a drawer's files are addressed outside tansu: smb://host/share, sftp://host, or nothing. */
private fun addressOf(drawer: Drawer): String {
    val server = drawer.server ?: return ""
    val port = if (server.port != server.kind.defaultPort) ":${server.port}" else ""
    return when (server.kind) {
        Server.Kind.Smb -> "smb://${server.host}$port/${server.share}"
        Server.Kind.Sftp -> "sftp://${server.host}$port"
    }
}

private fun tabOf(drawer: Drawer, troubled: Set<String>) =
    DrawerTab(drawer.id, drawer.name, drawer.kind, troubled = drawer.id in troubled)

/** Every failure the file systems can report, in the words Browse uses. */
private fun problemOf(e: FileSystemException): BrowseUiState.Problem =
    when (e) {
        is FileSystemException.AccessDenied -> BrowseUiState.Problem.Locked
        is FileSystemException.NotFound -> BrowseUiState.Problem.Missing
        is FileSystemException.Unreadable,
        is FileSystemException.Exists,
        is FileSystemException.NoSpace -> BrowseUiState.Problem.Unreadable
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
