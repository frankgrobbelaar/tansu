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
import io.github.frankgrobbelaar.tansu.files.Drawer
import io.github.frankgrobbelaar.tansu.files.Entry
import io.github.frankgrobbelaar.tansu.files.Fetcher
import io.github.frankgrobbelaar.tansu.files.FileSystem
import io.github.frankgrobbelaar.tansu.files.FileSystemException
import io.github.frankgrobbelaar.tansu.files.HostKey
import io.github.frankgrobbelaar.tansu.files.ListsShares
import io.github.frankgrobbelaar.tansu.files.Sort
import io.github.frankgrobbelaar.tansu.files.browseOrder
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
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowseUiState(
    val drawers: List<DrawerTab>,
    val drawer: DrawerTab,
    val crumbs: List<Crumb>,
    val entries: List<Entry> = emptyList(),
    val loading: Boolean = true,
    /** The listing is taking a while, as a server waking up does. */
    val slow: Boolean = false,
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
    /** Server files on their way to the phone, to open or share. */
    val fetching: Fetching? = null,
    /** Files fetched and ready to hand to another app, once. */
    val fetched: Fetched? = null,
    /** How every folder is ordered. */
    val sort: Sort = Sort(),
    /** This folder is shown as a grid rather than a list. */
    val grid: Boolean = false,
    val showHidden: Boolean = false,
    /** What the folder is being searched for; null when it isn't being searched. */
    val search: String? = null,
) {
    val searching: Boolean
        get() = search != null

    val selecting: Boolean
        get() = selected.isNotEmpty()

    val picked: List<Entry>
        get() = entries.filter { it.path in selected }

    /** The folder as a person reads it, such as "NAS › Media › Photos". */
    val label: String
        get() = crumbs.joinToString(" › ") { it.name }

    /** What [fetching] is for: opening one file, reading a PDF in tansu, or sharing them. */
    enum class Purpose {
        Open,
        Read,
        Share,
    }

    data class Fetching(val name: String, val bytesDone: Long, val bytesTotal: Long) {
        val fraction: Float
            get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f
    }

    data class Fetched(val files: List<File>, val purpose: Purpose)

    sealed interface Notice {
        data class JobEnded(val job: Job) : Notice

        /** A rename or new folder in [drawer] didn't happen, and why. */
        data class Refused(val reason: Failure.Reason, val drawer: String) : Notice
    }

    val title: String
        get() = crumbs.last().name

    /** The folder on show, by its path. */
    val folder: String
        get() = crumbs.last().path

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

        /** The server didn't answer, for [why]. */
        data class Unreachable(
            val host: String,
            val why: FileSystemException.Unreachable.Why =
                FileSystemException.Unreachable.Why.NoAnswer,
        ) : Problem

        /** The server turned the login down, or the private key can't be read. */
        data object LoginRefused : Problem

        /** The drawer's SMB share isn't on the server; [shares] are the ones it has, if it said. */
        data class ShareMissing(val share: String, val shares: List<String> = emptyList()) : Problem

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
    private val fetcher: Fetcher,
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
    private val fetching = MutableStateFlow<BrowseUiState.Fetching?>(null)
    private val fetched = MutableStateFlow<BrowseUiState.Fetched?>(null)
    /** What the folder is searched for: one source for the search bar and the listing, kept. */
    private val search = savedState.getMutableStateFlow<String?>(SEARCH, null)
    private var loading: kotlinx.coroutines.Job? = null

    /** A listing asked for while another ran, to make once it's through. */
    private var relist = false

    /** Whether Browse is on screen, watching its state. */
    private val onScreen = MutableStateFlow(false)
    private var fetchingJob: kotlinx.coroutines.Job? = null

    /** The drawer and folder on show. A drawer that's gone falls back to the phone. */
    private val here: StateFlow<Here> =
        combine(drawers, drawerId, paths, ::hereOf)
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                hereOf(drawers.value, drawerId.value, paths.value),
            )

    /**
     * The listing in the order chosen, sorted once for each listing and order. Sorting thousands of
     * names is real work, so it's done off the main thread.
     */
    private val sorted: StateFlow<Listing> =
        combine(listing, settings.sort) { listing, sort ->
                listing.copy(entries = listing.entries.sortedWith(browseOrder(sort)))
            }
            .flowOn(compute)
            .stateIn(viewModelScope, SharingStarted.Eagerly, listing.value)

    /**
     * The listing as Browse shows it: hidden files only when Settings says so, and what matches a
     * search, typed a letter at a time.
     */
    private val shown: StateFlow<Listing> =
        combine(sorted, settings.showHidden, search) { listing, showHidden, search ->
                val entries =
                    listing.entries.filter {
                        (showHidden || !it.isHidden) &&
                            (search.isNullOrEmpty() || it.name.contains(search, true))
                    }
                listing.copy(entries = entries)
            }
            .flowOn(compute)
            .stateIn(viewModelScope, SharingStarted.Eagerly, listing.value)

    /** How the folder on show is arranged: its order, list or grid, and any search. */
    private val arrangement =
        combine(here, settings.grids, settings.choices, search) { here, grids, choices, search ->
            Arrangement(
                sort = choices.sort,
                grid = gridKey(here) in grids,
                showHidden = choices.showHidden,
                search = search,
            )
        }

    /** Whether to offer a first server, and the name of one just added. */
    private val news =
        combine(servers.all, settings.serverOfferDismissed, added, drawers, troubled) {
            saved,
            dismissed,
            added,
            drawers,
            troubled ->
            News(
                offerServers = saved.isEmpty() && !dismissed,
                added = added,
                drawers = drawers,
                troubled = troubled,
            )
        }

    private val fetches = combine(fetching, fetched, ::Pair)

    /** Selection, the clipboard, the job under way, fetches, and anything to say. */
    private val tools =
        combine(picked, clipboard.contents, jobs.all, notice, fetches) {
            picked,
            held,
            all,
            notice,
            (fetching, fetched) ->
            Tools(picked, held, all.firstOrNull { it.active }, notice, fetching, fetched)
        }

    val state: StateFlow<BrowseUiState> =
        combine(here, shown, news, tools, arrangement) { here, listing, news, tools, arranged ->
                BrowseUiState(
                    drawers = news.drawers.map { tabOf(it, news.troubled) },
                    drawer = tabOf(here.drawer, news.troubled),
                    crumbs = crumbsOf(here),
                    entries = listing.entries,
                    loading = listing.loading,
                    slow = listing.slow,
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
                    fetching = tools.fetching,
                    fetched = tools.fetched,
                    sort = arranged.sort,
                    grid = arranged.grid,
                    showHidden = arranged.showHidden,
                    search = arranged.search,
                )
            }
            // On screen while something watches it, as Browse does only while it's in front.
            .onStart { onScreen.value = true }
            .onCompletion { onScreen.value = false }
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
            here.collectIndexed { index, folder ->
                picked.value = emptySet()
                // A search is of one folder: moving on ends it, but one brought back stays.
                if (index > 0) search.value = null
                load(folder, fresh = true)
            }
        }
        // Nothing stays picked that can't be seen.
        viewModelScope.launch { settings.showHidden.drop(1).collect { picked.value = emptySet() } }
        viewModelScope.launch {
            servers.added.collect { server ->
                drawerId.value = server.id
                added.value = server.name
            }
        }
        viewModelScope.launch { watchJobs() }
        viewModelScope.launch { watchLanding() }
    }

    /**
     * Goes to [folder] in the drawer [drawerId], as asked from outside: where a job's files went.
     */
    fun showFolder(drawerId: String, folder: String) {
        if (drawers.value.none { it.id == drawerId }) return
        paths.update { it + (drawerId to folder) }
        this.drawerId.value = drawerId
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

    /** Lists the folder again for what a job changed, once any listing on its way is through. */
    private fun catchUp() {
        if (loading?.isActive == true) relist = true else load(here.value, fresh = false)
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

    /**
     * Uses [share], one the server has, for this drawer in place of the share that's gone. The
     * drawer comes back with the server changed, and is listed.
     */
    fun useShare(share: String) {
        val serverId = here.value.drawer.id
        viewModelScope.launch { servers.useShare(serverId, share) }
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
        picked.value = shown.value.entries.map { it.path }.toSet()
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
        // The phone and SMB don't tell names apart by case, so neither does a clash.
        val here = listing.value.entries.associateBy { it.name.lowercase() }
        return held.picks.mapNotNull { pick ->
            here[pick.name.lowercase()]?.let { Clashing(it, pick) }
        }
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
        change { files -> renameOnce(files, entry.path, join(here.value.path, name)) }
    }

    /**
     * Renames [from] to [to]. When the connection drops, whether it went through is looked up: it's
     * done if it did, and tried once more if it plainly didn't, as on a connection that had died.
     */
    private suspend fun renameOnce(files: FileSystem, from: String, to: String) {
        try {
            files.rename(from, to)
        } catch (e: FileSystemException.Unreachable) {
            val left = files.stat(from) != null
            val there = files.stat(to) != null
            when {
                there && !left -> Unit
                left && !there -> files.rename(from, to)
                else -> throw e
            }
        }
    }

    fun createFolder(name: String) {
        change { files -> files.createFolder(join(here.value.path, name)) }
    }

    fun noticeShown() {
        notice.value = null
    }

    /** Fetches files from this drawer's server to the phone, to open or share them. */
    fun fetch(entries: List<Entry>, purpose: BrowseUiState.Purpose) {
        val files = here.value.drawer.fileSystem
        val drawer = here.value.drawer.name
        val total = entries.sumOf { it.size }
        fetchingJob?.cancel()
        fetchingJob = viewModelScope.launch {
            fetching.value = BrowseUiState.Fetching(entries.first().name, 0, total)
            try {
                val got =
                    fetcher.fetch(files, entries) { bytes ->
                        fetching.update { it?.copy(bytesDone = bytes) }
                    }
                ensureActive()
                fetched.value = BrowseUiState.Fetched(got, purpose)
            } catch (e: FileSystemException) {
                ensureActive()
                notice.value = BrowseUiState.Notice.Refused(reasonOf(e), drawer)
            } finally {
                fetching.value = null
            }
        }
    }

    fun cancelFetch() {
        fetchingJob?.cancel()
        fetching.value = null
    }

    fun fetchedHandled() {
        fetched.value = null
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
                if (job.from.drawerId == drawer || job.to?.drawerId == drawer) catchUp()
            }
        }
    }

    /**
     * Lists the folder on show again as a running job puts files in it or takes them away, at most
     * every couple of seconds, so pasted files show up as they land rather than when the job ends.
     */
    private suspend fun watchLanding() {
        // Only while Browse is on screen: in the background it would list a server for hours.
        combine(onScreen, jobs.all) { shown, all ->
                all.takeIf { shown }
                    ?.firstOrNull { it.status == Job.Status.Running && touchesHere(it) }
                    ?.let { it.id to it.progress.itemsDone }
            }
            .distinctUntilChanged()
            .collect { landed ->
                if (landed != null && landed.second > 0) {
                    if (loading?.isActive != true) load(here.value, fresh = false, quiet = true)
                    // A slow collector skips to the latest: at most one listing in this while.
                    delay(LANDING_EVERY_MILLIS)
                }
            }
    }

    /** Whether [job] puts files in the folder on show, or takes them from it, as a copy doesn't. */
    private fun touchesHere(job: Job): Boolean {
        val here = here.value
        val from = job.from.takeIf { job.kind != Job.Kind.Copy }
        return listOfNotNull(from, job.to).any {
            it.drawerId == here.drawer.id && here.path.isWithin(it.folder)
        }
    }

    fun dismissServerOffer() {
        viewModelScope.launch { settings.dismissServerOffer() }
    }

    fun addedShown() {
        added.value = null
    }

    // Arranging the folder

    /** Searches the folder for [text] in names; an empty one starts a search, null ends it. */
    fun search(text: String?) {
        search.value = text
    }

    fun setSort(sort: Sort) {
        viewModelScope.launch { settings.setSort(sort) }
    }

    /** Shows this folder as a grid, or as a list; each folder remembers which. */
    fun setGrid(on: Boolean) {
        val folder = gridKey(here.value)
        viewModelScope.launch { settings.setGrid(folder, on) }
    }

    fun setShowHidden(on: Boolean) {
        viewModelScope.launch { settings.setShowHidden(on) }
    }

    private fun goTo(path: String) {
        val drawerId = here.value.drawer.id
        paths.update { it + (drawerId to path) }
    }

    /**
     * Lists [here]: afresh, or keeping what's shown until the listing arrives. A [quiet] listing,
     * made only to catch up with a job, keeps what's shown if it fails.
     */
    private fun load(here: Here, fresh: Boolean, quiet: Boolean = false) {
        loading?.cancel()
        relist = false
        loading = viewModelScope.launch {
            listing.update { if (fresh) Listing(loading = true) else it.copy(loading = true) }
            // A server slow to answer is said to be, rather than left looking like an empty folder.
            val slow = launch {
                delay(SLOW_AFTER_MILLIS)
                listing.update { it.copy(slow = true) }
            }
            val result = fetch(here)
            slow.cancel()
            // smbj and sshj can't be stopped part-way: a listing someone moved on from may still
            // finish, or fail. It mustn't land on the folder they're looking at now.
            ensureActive()
            if (quiet && result.problem != null) {
                listing.update { it.copy(loading = false, slow = false) }
                return@launch
            }
            val drawerId = here.drawer.id
            troubled.update {
                if (result.problem.isServerWide()) it + drawerId else it - drawerId
            }
            // What's gone since can't stay picked.
            val there = result.entries.mapTo(HashSet()) { it.path }
            picked.update { it.intersect(there) }
            listing.value = result
            if (relist) load(here, fresh = false)
        }
    }

    private suspend fun fetch(here: Here): Listing =
        if (here.drawer.kind == Drawer.Kind.Phone && !access.granted()) {
            Listing(problem = BrowseUiState.Problem.NoAccess)
        } else {
            try {
                Listing(here.drawer.fileSystem.list(here.path))
            } catch (e: FileSystemException) {
                Listing(problem = problemOf(e).withShares(here.drawer.fileSystem))
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
        /** The drawers whose servers aren't answering. */
        val troubled: Set<String>,
    )

    private data class Arrangement(
        val sort: Sort,
        val grid: Boolean,
        val showHidden: Boolean,
        val search: String?,
    )

    private data class Tools(
        val picked: Set<String>,
        val held: Held?,
        val job: Job?,
        val notice: BrowseUiState.Notice?,
        val fetching: BrowseUiState.Fetching?,
        val fetched: BrowseUiState.Fetched?,
    )

    private data class Listing(
        val entries: List<Entry> = emptyList(),
        val loading: Boolean = false,
        val slow: Boolean = false,
        val problem: BrowseUiState.Problem? = null,
    )

    companion object {
        private const val DRAWER = "drawer"
        private const val PATHS = "paths"
        private const val SEARCH = "search"
        private const val STOP_AFTER_MILLIS = 5_000L
        private const val SLOW_AFTER_MILLIS = 3_000L
        private const val LANDING_EVERY_MILLIS = 2_000L

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
                    fetcher = container.fetcher,
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
        is FileSystemException.Unreachable -> BrowseUiState.Problem.Unreachable(e.host, e.why)
        is FileSystemException.AuthFailed,
        is FileSystemException.BadKey -> BrowseUiState.Problem.LoginRefused
        is FileSystemException.ShareMissing -> BrowseUiState.Problem.ShareMissing(e.share)
        is FileSystemException.HostKeyChanged ->
            BrowseUiState.Problem.KeyChanged(e.trusted, e.presented)
        is FileSystemException.HostKeyUnknown -> BrowseUiState.Problem.KeyChanged("", e.key)
    }

/** A share that's gone comes with the ones the server does have, to pick from. */
private suspend fun BrowseUiState.Problem.withShares(files: FileSystem): BrowseUiState.Problem =
    if (this is BrowseUiState.Problem.ShareMissing) copy(shares = sharesOn(files)) else this

/** The shares on [files]' server, or none when it can't say: Browse then offers Edit server. */
private suspend fun sharesOn(files: FileSystem): List<String> =
    try {
        (files as? ListsShares)?.shares().orEmpty()
    } catch (_: FileSystemException) {
        emptyList()
    }

/** Whether a problem is the whole server's, not just one folder's. */
private fun BrowseUiState.Problem?.isServerWide() =
    this is BrowseUiState.Problem.Unreachable ||
        this is BrowseUiState.Problem.LoginRefused ||
        this is BrowseUiState.Problem.ShareMissing ||
        this is BrowseUiState.Problem.KeyChanged

/** A folder as the grid setting names it: its drawer and path. */
private fun gridKey(here: Here) = "${here.drawer.id}:${here.path}"
