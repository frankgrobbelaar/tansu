package io.github.frankgrobbelaar.tansu.ui.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
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
import io.github.frankgrobbelaar.tansu.files.isWithin
import io.github.frankgrobbelaar.tansu.files.join
import io.github.frankgrobbelaar.tansu.jobs.Jobs
import io.github.frankgrobbelaar.tansu.jobs.Where
import io.github.frankgrobbelaar.tansu.ui.kit.Crumb
import io.github.frankgrobbelaar.tansu.ui.kit.DrawerTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Save to: what was shared, and the drawer and folder it's going into. */
data class SaveToUiState(
    val picks: List<Entry> = emptyList(),
    /** The app the files came from, if Android says. */
    val from: String,
    val drawers: List<DrawerTab>,
    val drawer: DrawerTab,
    val crumbs: List<Crumb>,
    val folders: List<Entry> = emptyList(),
    val loading: Boolean = true,
    /** The folder can't be listed: no All files access, or the server can't be reached. */
    val unreachable: Boolean = false,
    /** The job started, by its id: the screen can go. */
    val savedAs: Long? = null,
) {
    val bytes: Long
        get() = picks.sumOf { it.size }

    val folderName: String
        get() = crumbs.last().name

    val ready: Boolean
        get() = picks.isNotEmpty() && !loading && !unreachable && savedAs == null
}

/** What Save to lets you do. */
class SaveToActions(
    val onClose: () -> Unit,
    val onSelectDrawer: (String) -> Unit,
    val onOpen: (Entry) -> Unit,
    val onCrumb: (Crumb) -> Unit,
    val onNewFolder: (String) -> Unit,
    val onSave: () -> Unit,
)

/** Picks a drawer and folder for files shared with tansu, then saves them there as a job. */
class SaveToViewModel(
    private val drawers: StateFlow<List<Drawer>>,
    private val access: StorageAccess,
    private val jobs: Jobs,
    private val settings: TansuSettings,
    private val shared: FileSystem,
    private val read: suspend () -> List<Entry>,
    from: String,
) : ViewModel() {

    private var drawer = drawers.value.first()
    private var path = drawer.root
    private val _state =
        MutableStateFlow(
            SaveToUiState(
                from = from,
                drawers = drawers.value.map(::tabOf),
                drawer = tabOf(drawer),
                crumbs = crumbsOf(drawer, path),
            )
        )
    val state: StateFlow<SaveToUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val picks = read()
            _state.update { it.copy(picks = picks) }
            // Start where the last share was saved, if that drawer is still here.
            settings.lastSave.first()?.let { (drawerId, folder) ->
                drawers.value
                    .find { it.id == drawerId }
                    ?.let { last ->
                        drawer = last
                        path = folder.takeIf { it.isWithin(last.root) } ?: last.root
                    }
            }
            show()
        }
    }

    fun select(drawerId: String) {
        drawer = drawers.value.find { it.id == drawerId } ?: return
        path = drawer.root
        show()
    }

    fun open(folder: Entry) {
        path = folder.path
        show()
    }

    fun jumpTo(crumb: Crumb) {
        path = crumb.path
        show()
    }

    fun createFolder(name: String) {
        viewModelScope.launch {
            try {
                drawer.fileSystem.createFolder(join(path, name))
                path = join(path, name)
            } catch (_: FileSystemException) {
                // It stays where it was; the listing says what's there.
            }
            show()
        }
    }

    /** Starts copying the shared files into the folder on show, and remembers it for next time. */
    fun save() {
        val picks = _state.value.picks
        // Once is enough: a second tap would save them all again.
        if (picks.isEmpty() || _state.value.savedAs != null) return
        val crumbs = crumbsOf(drawer, path)
        val from = Where(SHARED + System.nanoTime(), "", _state.value.from, _state.value.from)
        val to = Where(drawer.id, path, drawer.name, crumbs.joinToString(" › ") { it.name })
        val id = jobs.copyIn(shared, picks, from, to)
        viewModelScope.launch { settings.rememberSave(drawer.id, path) }
        _state.update { it.copy(savedAs = id) }
    }

    private fun show() {
        val drawer = drawer
        val path = path
        _state.update {
            it.copy(
                drawers = drawers.value.map(::tabOf),
                drawer = tabOf(drawer),
                crumbs = crumbsOf(drawer, path),
                folders = emptyList(),
                loading = true,
                unreachable = false,
            )
        }
        viewModelScope.launch {
            val showHidden = settings.showHidden.first()
            val folders =
                if (drawer.kind == Drawer.Kind.Phone && !access.granted()) {
                    null
                } else {
                    try {
                        drawer.fileSystem
                            .list(path)
                            .filter { it.isFolder && (showHidden || !it.isHidden) }
                            .sortedWith(BrowseOrder)
                    } catch (_: FileSystemException) {
                        null
                    }
                }
            // Only the folder still on show takes the listing.
            if (this@SaveToViewModel.drawer == drawer && this@SaveToViewModel.path == path) {
                _state.update {
                    it.copy(
                        folders = folders.orEmpty(),
                        loading = false,
                        unreachable = folders == null,
                    )
                }
            }
        }
    }

    companion object {
        private const val SHARED = "shared-"

        fun factory(
            shared: FileSystem,
            read: suspend () -> List<Entry>,
            from: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as TansuApplication).container
                SaveToViewModel(
                    drawers = container.drawers.all,
                    access = container.storageAccess,
                    jobs = container.jobs,
                    settings = container.settings,
                    shared = shared,
                    read = read,
                    from = from,
                )
            }
        }
    }
}

private fun tabOf(drawer: Drawer) = DrawerTab(drawer.id, drawer.name, drawer.kind)

private fun crumbsOf(drawer: Drawer, path: String): List<Crumb> {
    val names = path.removePrefix(drawer.root).split('/').filter { it.isNotEmpty() }
    val paths = names.runningFold(drawer.root, ::join).drop(1)
    return listOf(Crumb(drawer.name, drawer.root)) + names.zip(paths, ::Crumb)
}
